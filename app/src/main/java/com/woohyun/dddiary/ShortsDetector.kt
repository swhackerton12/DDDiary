package com.woohyun.dddiary

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.max
import kotlin.math.min

enum class FeedKind { YOUTUBE_SHORTS, INSTAGRAM_REELS, OTHER }

data class FeedDetectResult(
    val kind: FeedKind,
    val confidence: Double,         // 0.0 ~ 1.0 (근사)
    val reasons: List<String> = emptyList()
)

/**
 * 접근성 트리만으로 Shorts/Reels 판별.
 * - 패키지 단서: YouTube/Instagram/Chrome
 * - 텍스트 단서: "Shorts", "Reels"
 * - 레이아웃 단서: 오른쪽 세로 액션 스택(좋아요/댓글/공유/구독·팔로우)
 */
object ShortsDetector {

    // 패키지
    private const val PKG_YT = "com.google.android.youtube"
    private const val PKG_IG = "com.instagram.android"
    private const val PKG_CH = "com.android.chrome"

    // 키워드 (소문자 비교)
    private val YT_WORD = listOf("shorts")
    private val IG_WORD = listOf("reels", "릴스")

    // 액션 스택 키워드(우측 버튼군)
    private val ACTION_WORDS = listOf(
        "dislike","dislikes","싫어요",
        "comment","comments","댓글",
        "share","공유",
        "subscribe","구독",
        "original sound","원본 사운드",
        "like","likes","좋아요",
        "follow","팔로우",
        "audio","오디오",
    )

    // 점수 임계
    private const val SCORE_TITLE_HIT = 2      // 뷰ID/클래스에 short|reels
    private const val SCORE_TEXT_HIT   = 1      // 텍스트/콘텐츠 설명 키워드
    private const val SCORE_LAYOUT_HIT = 7      // 레이아웃 단서(우측 액션 스택/세로 페이지)
    private const val THRESHOLD_SCORE  = 8      // 이 이상이면 해당 피드로 판정
    private const val MAX_NODES        = 900    // BFS 상한

    /** 진입점 */
    fun detect(root: AccessibilityNodeInfo?): FeedDetectResult {
        if (root == null) return FeedDetectResult(FeedKind.OTHER, 0.0)

        // 루트 패키지 선필터
        val rootPkg = root.packageName?.toString()?.lowercase().orEmpty()
        Log.d("ShortsDetector", "Root package: $rootPkg")
        val isYT = rootPkg.startsWith(PKG_YT) || rootPkg.startsWith(PKG_CH)
        val isIG = rootPkg.startsWith(PKG_IG) || rootPkg.startsWith(PKG_CH)
        if (!isYT && !isIG) {
            return FeedDetectResult(FeedKind.OTHER, 0.0, listOf("pkg=$rootPkg"))
        }

        // 화면 크기(우측 액션 스택 판별용)
        val screen = Rect().also { root.getBoundsInScreen(it) }
        val w = (screen.right - screen.left).coerceAtLeast(1)
        val h = (screen.bottom - screen.top).coerceAtLeast(1)
        val rightBandX = screen.right - (w * 0.22f) // 오른쪽 22% 영역
        val midTop = screen.top + (h * 0.12f)
        val midBot = screen.top + (h * 0.88f)

        var scoreYT = 0
        var scoreIG = 0
        val reasons = mutableListOf<String>()

        // 패키지 자체 가점
        if (isYT) { scoreYT += 2; reasons += "yt:pkg" }
        if (isIG) { scoreIG += 2; reasons += "ig:pkg" }

        // 우측 액션 스택/세로 페이지 단서 집계
        var rightStackCount = 0

        // BFS
        val q = ArrayDeque<AccessibilityNodeInfo>()
        val visited = ArrayList<AccessibilityNodeInfo>(256)
        q.add(root)
        var steps = 0

        var hasYTTitle = false
        var hasYTText = false
        var hasIGTitle = false
        var hasIGText = false
        while (q.isNotEmpty() && steps < MAX_NODES) {
            val n = q.removeFirst()
            steps++
            visited.add(n)

            val id  = n.viewIdResourceName?.lowercase().orEmpty()
            val text = (n.text?.toString().orEmpty() + " " + n.contentDescription?.toString().orEmpty())
                .lowercase()

            // ---------- 공통: 우측 액션 스택 감지 ----------
            run {
                if (text.isNotBlank()) {
                    val r = Rect(); n.getBoundsInScreen(r)
                    val cx = (r.left + r.right) / 2f
                    val cy = (r.top + r.bottom) / 2f
                    val rightBand = cx >= rightBandX && cy in midTop..midBot
                    if (rightBand && ACTION_WORDS.any { text.contains(it) }) {
                        rightStackCount++
                    }
                }
            }

            // ---------- YT 휴리스틱 ----------
            if (isYT) {
                if (id.contains("shorts")) hasYTTitle = true
                if (YT_WORD.any { text.contains(it) }) hasYTText = true
            }

            // ---------- IG 휴리스틱 ----------
            if (isIG) {
                if (id.contains("reels")) hasIGTitle = true
                if (IG_WORD.any { text.contains(it) }) hasIGText = true
            }

            // 자식 enqueue
            val childCount = min(n.childCount, 24)
            for (i in 0 until childCount) n.getChild(i)?.let { q.add(it) }
        }

        // 정리
        for (node in visited) if (node !== root) node.recycle()

        // ---------- YT 휴리스틱 ----------
        if (isYT) {
            if (hasYTTitle) { scoreYT += SCORE_TITLE_HIT; reasons += "yt:id|cls:short*" }
            if (hasYTText) {
                scoreYT += SCORE_TEXT_HIT
                reasons += "yt:text:keyword"
            }
        }

        // ---------- IG 휴리스틱 ----------
        if (isIG) {
            if (hasIGTitle) { scoreIG += SCORE_TITLE_HIT; reasons += "ig:id|cls:reel*" }
            if (hasIGText) {
                scoreIG += SCORE_TEXT_HIT
                reasons += "ig:text:keyword"
            }
        }

        // 레이아웃 단서 점수 반영
        if (rightStackCount >= 4) {
            scoreYT += SCORE_LAYOUT_HIT; scoreIG += SCORE_LAYOUT_HIT
            reasons += "layout:rightStack=$rightStackCount"
        }

        // 최종 판정
        val ytPass = isYT && scoreYT >= THRESHOLD_SCORE
        val igPass = isIG && scoreIG >= THRESHOLD_SCORE

        val (kind, score) = when {
            ytPass && !igPass -> FeedKind.YOUTUBE_SHORTS to scoreYT
            igPass && !ytPass -> FeedKind.INSTAGRAM_REELS to scoreIG
            ytPass && igPass -> if (scoreYT >= scoreIG) FeedKind.YOUTUBE_SHORTS to scoreYT else FeedKind.INSTAGRAM_REELS to scoreIG
            else -> FeedKind.OTHER to max(scoreYT, scoreIG)
        }

        val conf = scoreToConfidence(score)
        return FeedDetectResult(kind, conf, reasons)
    }

    private fun scoreToConfidence(score: Int): Double {
        // 간단 포화함수: 0→0.0, 5→0.75, 8→0.9, 10+→0.96
        return when {
            score <= 0 -> 0.0
            score >= 10 -> 0.96
            else -> 0.5 + (score.toDouble() / 10.0) * 0.46
        }
    }

    /** 디버깅 로그 */
    fun log(result: FeedDetectResult) {
        Log.i("ShortsDetector", "detect => ${result.kind} p=${"%.2f".format(result.confidence)} reasons=${result.reasons.joinToString(",")}")
    }
}
