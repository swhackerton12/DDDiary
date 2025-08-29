package com.woohyun.dddiary

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
import kotlin.math.max

/**
 * ScrollWatchService — 방향락(Direction Lock) + 스냅윈도우 + 반동필터 + 암시적 세션
 *
 * - dy 계산 우선순위: scrollDeltaY(API28+) → scrollY 차이 → 앵커(top) 좌표 변화
 * - 세션: TOUCH_START/END 없더라도 SCROLLED가 오면 암시적 시작
 * - Direction Lock: 초반 강한 dy로 세션 방향 잠금, 잠깐 반대부호는 무시
 * - Pair Cancel: 짧은 시간 내 ±대칭 튐은 무시(앵커 교체/리바인드 노이즈)
 */

class ScrollWatchService : AccessibilityService() {

    // ===== 튜닝 파라미터 =====
    private companion object {
        private const val JITTER_PX = 2
        private const val MIN_TOTAL_PX = 48

        private const val QUIET_MIN_MS = 80L
        private const val QUIET_MAX_MS = 260L
        private const val LINGER_AFTER_END_MS = 60L
        private const val MIN_DURATION_MS = 60L

        // 빠른 플릭 허용(짧아도 빠르면 인정)
        private const val FAST_MIN_PX = 12
        private const val FAST_VEL = 0.9
        private const val ACCEL_SPIKE = 0.015

        // 암시적 세션 허용(TOUCH_START 없어도 SCROLLED로 시작)
        private const val REQUIRE_TOUCH_START = false

        // 반동 가드(세션 직후 역방향 큰 한 방 컷)
        private const val ENABLE_POST_BOUNCE_GUARD = true
        private const val BOUNCE_GUARD_MS = 180L
        private const val BOUNCE_MATCH_RATIO = 0.5

        // === 방향 락 & 스냅 윈도우 ===
        private const val LOCK_PX = 180            // 단일 dy가 이 이상이면 방향락 후보
        private const val LOCK_SUM_PX = 240        // 누적 |dy|가 이 이상이면 락 후보
        private const val LOCK_WINDOW_MS = 220L    // 세션 시작 후 이 안에서 락을 주로 결정
        private const val SNAP_WINDOW_MS = 250L    // 락 직후 이 시간 동안 반대부호 무시

        // === 페어 캔슬(대칭 튐 제거) ===
        private const val PAIR_MS = 140L           // 연속 이벤트 간 시간
        private const val PAIR_TOL_PX = 120        // | |dy1|-|dy2| | 가 이내면 대칭으로 간주
    }

    // ===== 상태 =====
    private val lastYBySource = mutableMapOf<Int, Int>()
    private val lastAnchorTopByWin = mutableMapOf<Int, Int>()
    private val activeTouchWins = mutableSetOf<Int>()

    private data class VSession(
        val windowId: Int,
        var startTime: Long,
        var lastTime: Long,

        var sumDy: Int = 0,
        var absSumDy: Long = 0,
        var posVotes: Int = 0,
        var negVotes: Int = 0,

        var lastToIndex: Int = -1,
        var lastItemCount: Int = 0,

        var velEma: Double = 0.0,
        var lastVel: Double = 0.0,

        // Direction Lock
        var lockedSign: Int = 0,      // +1: content DOWN(손가락 UP), -1: content UP(손가락 DOWN)
        var lockedAt: Long = 0L,
        var sameAbsSinceLock: Long = 0,
        var oppAbsSinceLock: Long = 0,

        // 페어 캔슬용
        var lastDyForPair: Int = 0,
        var lastDyForPairAt: Long = 0,

        // 메타
        var touchStarted: Boolean = false,
        var touchEnded: Boolean = false,
        var implicit: Boolean = false
    )

    private data class PostGuard(
        var until: Long = 0L,
        var sign: Int = 0,
        var magnitude: Long = 0
    )

    private val sessions = mutableMapOf<Int, VSession>()
    private val finishRunnables = mutableMapOf<Int, Runnable>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val postGuards = mutableMapOf<Int, PostGuard>()

    // ===== 생명주기 =====
    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = (AccessibilityEvent.TYPE_VIEW_SCROLLED
                    or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    or AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
                    or AccessibilityEvent.TYPE_TOUCH_INTERACTION_END
                    or AccessibilityEvent.TYPE_GESTURE_DETECTION_START
                    or AccessibilityEvent.TYPE_GESTURE_DETECTION_END)
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 50
            flags = (AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS)
        }
        Log.i("ScrollWatch", "Service connected")
    }

    override fun onInterrupt() { Log.w("ScrollWatch", "Service interrupted") }

    // ===== 이벤트 처리 =====
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                    val win = event.windowId
                    activeTouchWins.add(win)
                    val now = event.eventTime
                    val s = sessions.getOrPut(win) { VSession(win, now, now) }
                    s.touchStarted = true
                    s.implicit = false
                    s.startTime = now
                    s.lastTime = now
                    finishRunnables.remove(win)?.let { handler.removeCallbacks(it) }
                    postGuards.remove(win)
                    Log.d("ScrollWatch", "TOUCH_START win=$win")
                }

                AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                    val win = event.windowId
                    if (REQUIRE_TOUCH_START) {
                        if (!activeTouchWins.contains(win)) {
                            maybeFilterBounceOutsideSession(event)
                            return
                        }
                    } else {
                        if (!activeTouchWins.contains(win)) {
                            val now = event.eventTime
                            val s = sessions.getOrPut(win) { VSession(win, now, now) }
                            s.touchStarted = false
                            s.implicit = true
                            s.startTime = now
                            s.lastTime = now
                            activeTouchWins.add(win)
                            finishRunnables.remove(win)?.let { handler.removeCallbacks(it) }
                            postGuards.remove(win)
                            Log.w("ScrollWatch", "IMPLICIT_START win=$win (no TOUCH_START)")
                        }
                    }
                    handleViewScrolled(event)
                }

                AccessibilityEvent.TYPE_TOUCH_INTERACTION_END,
                AccessibilityEvent.TYPE_GESTURE_DETECTION_END -> {
                    val win = event.windowId
                    sessions[win]?.touchEnded = true
                    activeTouchWins.remove(win)
                    handler.postDelayed({ finalizeVertical(win) }, LINGER_AFTER_END_MS)
                    Log.d("ScrollWatch", "TOUCH_END win=$win (finalize scheduled)")
                }
            }
        } catch (t: Throwable) {
            Log.e("ScrollWatch", "onAccessibilityEvent crash", t)
        }
    }

    private fun maybeFilterBounceOutsideSession(event: AccessibilityEvent) {
        if (!ENABLE_POST_BOUNCE_GUARD) return
        val src = event.source ?: return
        try {
            val dy = computeDy(event, src)
            val g = postGuards[event.windowId]
            if (g != null && SystemClock.uptimeMillis() <= g.until) {
                val opp = dy.sign() == -g.sign
                val big = abs(dy).toLong() >= (g.magnitude * BOUNCE_MATCH_RATIO).toLong()
                if (opp && big) {
                    Log.d("ScrollWatch", "BOUNCE_FILTERED dy=$dy (guard)")
                }
            }
        } finally { src.recycle() }
    }

    private fun handleViewScrolled(event: AccessibilityEvent) {
        val src = event.source ?: return
        try {
            val dyRaw = computeDy(event, src)
            Log.d("ScrollWatch",
                "SCROLLED pkg=${event.packageName} host=${event.className} dy=$dyRaw idx=${event.toIndex}/${event.itemCount}")

            aggregateVertical(event, dyRaw)
        } catch (t: Throwable) {
            Log.e("ScrollWatch", "handleViewScrolled crash", t)
        } finally { src.recycle() }
    }

    // ===== dy 계산: deltaY → scrollY → 앵커 top =====
    private fun computeDy(event: AccessibilityEvent, src: AccessibilityNodeInfo): Int {
        if (Build.VERSION.SDK_INT >= 28) {
            val dy = event.scrollDeltaY
            if (dy != 0) return dy
        }
        val key = System.identityHashCode(src)
        val curY = event.scrollY
        val prevY = lastYBySource.put(key, curY)
        if (prevY != null) {
            val dy = curY - prevY
            if (dy != 0) return dy
        }
        return inferDyFromAnchor(event)
    }

    private fun inferDyFromAnchor(event: AccessibilityEvent): Int {
        val root = rootInActiveWindow ?: return 0
        val top = try { pickAnchorTop(root, event.packageName) } finally { /* nodes recycled inside */ } ?: return 0
        val prev = lastAnchorTopByWin.put(event.windowId, top) ?: return 0
        val raw = top - prev
        return -raw // 콘텐츠 DOWN → dy > 0
    }

    private fun pickAnchorTop(root: AccessibilityNodeInfo, targetPkg: CharSequence?): Int? {
        val q = ArrayDeque<AccessibilityNodeInfo>()
        val visited = ArrayList<AccessibilityNodeInfo>(128)
        var bestTop: Int? = null

        q.add(root)
        var steps = 0
        while (q.isNotEmpty() && steps < 200) {
            val n = q.removeFirst(); steps++; visited.add(n)
            val samePkg = targetPkg == null || n.packageName == targetPkg
            if (samePkg && n.isVisibleToUser &&
                ((n.text?.isNotBlank() == true) || (n.contentDescription?.isNotBlank() == true))) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (bestTop == null || r.top < bestTop!!) bestTop = r.top
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        for (node in visited) if (node !== root) node.recycle()
        return bestTop
    }

    // ===== 세션 집계(+ 방향락/스냅/페어캔슬) =====
    private fun aggregateVertical(event: AccessibilityEvent, dyRaw: Int) {
        val win = event.windowId
        val now = event.eventTime
        val s = sessions.getOrPut(win) { VSession(win, now, now) }

        var dy = dyRaw
        if (abs(dy) < JITTER_PX) {
            s.lastTime = now
            scheduleFinish(win, dynamicQuietMs(s))
            return
        }

        val sign = dy.sign()

        // --- 페어 캔슬: 직전과 반대부호 & 짧은 간격 & 크기 유사면 노이즈로 무시 ---
        if (s.lastDyForPair != 0 && sign == -s.lastDyForPair.sign()
            && (now - s.lastDyForPairAt) <= PAIR_MS
        ) {
            val diff = abs(abs(dy) - abs(s.lastDyForPair))
            if (diff <= PAIR_TOL_PX) {
                // 현재 이벤트 무시 (앵커 스위치/리바인드 노이즈)
                Log.v("ScrollWatch", "PAIR_CANCEL dy=$dy vs last=${s.lastDyForPair}")
                s.lastDyForPair = dy // 갱신은 해둔다
                s.lastDyForPairAt = now
                scheduleFinish(win, dynamicQuietMs(s))
                return
            }
        }

        // --- Direction Lock: 초반 강한 움직임으로 방향 잠금 ---
        val sinceStart = now - s.startTime
        val willLock = (abs(dy) >= LOCK_PX) || (s.absSumDy + abs(dy) >= LOCK_SUM_PX)
        if (s.lockedSign == 0 && sinceStart <= LOCK_WINDOW_MS && willLock) {
            s.lockedSign = sign // 콘텐츠 방향 부호로 잠금(+1: DOWN → 손가락 UP)
            s.lockedAt = now
            s.sameAbsSinceLock = 0
            s.oppAbsSinceLock = 0
            Log.d("ScrollWatch", "DIR_LOCK sign=${s.lockedSign} by dy=$dy sumAbs=${s.absSumDy}")
        }

        // --- 스냅 윈도우: 락 직후 잠깐 반대부호 큰 변화 무시 (앵커 바뀌는 시점) ---
        if (s.lockedSign != 0 && (now - s.lockedAt) <= SNAP_WINDOW_MS && sign == -s.lockedSign) {
            s.oppAbsSinceLock += abs(dy).toLong()
            // 같은 방향 누적 대비 일정 비율 미만이면 무시
            val threshold = max(1L, (s.sameAbsSinceLock * 0.85).toLong()) // 85% 이하면 노이즈로
            if (s.oppAbsSinceLock <= threshold) {
                Log.v("ScrollWatch", "SNAP_SUPPRESS dy=$dy oppAbs=${s.oppAbsSinceLock} <= thr=$threshold")
                // 페어 기준 갱신만 하고 합산은 하지 않음
                s.lastDyForPair = dy
                s.lastDyForPairAt = now
                scheduleFinish(win, dynamicQuietMs(s))
                return
            }
        }

        // --- 합산/표결/속도 ---
        s.sumDy += dy
        s.absSumDy += abs(dy)

        if (dy > 0) s.posVotes++ else s.negVotes++

        val dt = (now - s.lastTime).coerceAtLeast(1L)
        val v = abs(dy).toDouble() / dt.toDouble()
        val emaAlpha = 0.25
        val accel = abs(v - s.lastVel) / dt.toDouble()
        s.velEma = if (s.velEma == 0.0) v else (emaAlpha * v + (1 - emaAlpha) * s.velEma)
        s.lastVel = v

        // 락 이후 동일/반대 누적량 갱신
        if (s.lockedSign != 0) {
            if (sign == s.lockedSign) s.sameAbsSinceLock += abs(dy).toLong()
            else s.oppAbsSinceLock += abs(dy).toLong()
        }

        s.lastTime = now
        s.lastToIndex = event.toIndex
        s.lastItemCount = event.itemCount

        // 페어 기준 업데이트
        s.lastDyForPair = dy
        s.lastDyForPairAt = now

        // 빠른 플릭: 부호 안정 + 속도/가속도 높음 + 최소거리
        val stableSign = (s.posVotes >= 3 && s.negVotes == 0) || (s.negVotes >= 3 && s.posVotes == 0)
        if (stableSign && (s.velEma >= FAST_VEL || accel >= ACCEL_SPIKE) && s.absSumDy >= FAST_MIN_PX) {
            finalizeVertical(win); return
        }

        scheduleFinish(win, dynamicQuietMs(s))
    }

    private fun dynamicQuietMs(s: VSession): Long {
        val v = s.velEma
        val ms = when {
            v < 0.15 -> 240L
            v < 0.60 -> 170L
            else -> 110L
        }
        return ms.coerceIn(QUIET_MIN_MS, QUIET_MAX_MS)
    }

    private fun scheduleFinish(windowId: Int, quietMs: Long) {
        finishRunnables.remove(windowId)?.let { handler.removeCallbacks(it) }
        val r = Runnable { finalizeVertical(windowId) }
        finishRunnables[windowId] = r
        handler.postDelayed(r, quietMs)
    }

    private fun finalizeVertical(windowId: Int) {
        val s = sessions.remove(windowId) ?: return
        finishRunnables.remove(windowId)?.let { handler.removeCallbacks(it) }
        activeTouchWins.remove(windowId)

        val durationMs = (s.lastTime - s.startTime).coerceAtLeast(0L)
        val fastFlick = (s.velEma >= FAST_VEL && s.absSumDy >= FAST_MIN_PX)

        if (!fastFlick && (durationMs < MIN_DURATION_MS || s.absSumDy < MIN_TOTAL_PX)) {
            Log.i("ScrollWatch",
                "INTENT: UNKNOWN (short/too small) dur=${durationMs}ms absDy=${s.absSumDy} vel=${"%.3f".format(s.velEma)} implicit=${s.implicit}")
            return
        }

        // 1순위: 방향 락
        var verdictSign = s.lockedSign

        // 2순위: 합계 부호 → 표결 → 인덱스(보조)
        if (verdictSign == 0) {
            verdictSign = when {
                s.sumDy > 0 -> 1
                s.sumDy < 0 -> -1
                s.posVotes > s.negVotes -> 1
                s.negVotes > s.posVotes -> -1
                (s.lastToIndex >= 0 && s.lastItemCount > 0) -> 1
                else -> 0
            }
        }

        // 로그 + 반동 가드 설치
        when (verdictSign) {
            1 -> Log.i("ScrollWatch",
                "INTENT: SWIPE_UP (content=DOWN) sumDy=${s.sumDy} absDy=${s.absSumDy} vel=${"%.3f".format(s.velEma)} votes=${s.posVotes}/${s.negVotes} implicit=${s.implicit} lock=+")
            -1 -> Log.i("ScrollWatch",
                "INTENT: SWIPE_DOWN (content=UP) sumDy=${s.sumDy} absDy=${s.absSumDy} vel=${"%.3f".format(s.velEma)} votes=${s.posVotes}/${s.negVotes} implicit=${s.implicit} lock=-")
            else -> {
                Log.i("ScrollWatch",
                    "INTENT: UNKNOWN (tie) absDy=${s.absSumDy} vel=${"%.3f".format(s.velEma)} implicit=${s.implicit} lock=0")
            }
        }

        if (ENABLE_POST_BOUNCE_GUARD && verdictSign != 0) {
            postGuards[windowId] = PostGuard(
                until = SystemClock.uptimeMillis() + BOUNCE_GUARD_MS,
                sign = verdictSign,
                magnitude = s.absSumDy
            )
        }
    }

    // ===== 유틸 =====
    private fun Int.sign() = when {
        this > 0 -> 1
        this < 0 -> -1
        else -> 0
    }
}
