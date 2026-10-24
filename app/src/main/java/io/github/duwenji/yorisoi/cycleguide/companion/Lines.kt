package io.github.duwenji.yorisoi.cycleguide.companion

import io.github.duwenji.yorisoi.kit.geo.weather.WeatherChangeKind

/**
 * Fixed lines for each decision: what the companion says without the AI, and always for safety.
 * Each fits the shortest riding length ([SpeechBudget]).
 */
object Lines {
    fun of(decision: RideDecision): String? = when (decision) {
        is RideDecision.WeatherTurn -> {
            val change = decision.change
            val soon = if (change.minutes <= 5) "まもなく" else "${change.minutes}分ほどで"
            when (change.kind) {
                WeatherChangeKind.THUNDER -> "${soon}雷雨の予報です。早めに建物に入りましょう。"
                WeatherChangeKind.HEAVY_RAIN -> "${soon}強い雨の予報です。雨宿りできるところを探しましょう。"
                WeatherChangeKind.RAIN -> "${soon}${change.description}の予報です。路面がすべりやすくなります。"
            }
        }
        is RideDecision.Sunset -> "日の入りまであと${decision.minutesLeft}分です。ライトをつけましょう。"
        is RideDecision.Greeting -> decision.line
        is RideDecision.Spot -> "この先、${decision.poi.name}があります。"
        is RideDecision.Deferred -> "さきほど通った${decision.talk.poi.name}は、${decision.talk.poi.category}です。"
        is RideDecision.Rest -> "ひと休みですね。水分をとっておきましょう。"
        is RideDecision.Milestone -> "ここまで順調です。"
        is RideDecision.Defer, RideDecision.Nothing -> null
    }

    /** Before the first ride and when earphones connect (requirements S-3). */
    const val EARPHONE_NOTICE = "周りの音が聞こえる状態で使ってください。イヤホンで両耳をふさぐと、車や人の近づく音が聞こえません。"

    fun start(): String = "出発しましょう。周りの音が聞こえる状態で、安全に走ってください。"

    fun finish(distanceM: Double): String = "おつかれさまでした。${"%.1f".format(distanceM / 1000)}キロ走りました。"
}
