package io.github.duwenji.yorisoi.cycleguide.companion

import io.github.duwenji.yorisoi.cycleguide.style.Amenity
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
        is RideDecision.AmenityNear -> amenity(decision.advice)
        is RideDecision.Rest -> "ひと休みですね。水分をとっておきましょう。"
        is RideDecision.Milestone -> "ここまで順調です。"
        is RideDecision.Defer, RideDecision.Nothing -> null
    }

    /**
     * A facility: ahead, how far (by 50m); parking while resting, which side. Its name when it has
     * one ("コンビニ「…」").
     */
    fun amenity(a: AmenityAdvice): String {
        val what = a.facility.kind.label + (a.facility.name?.let { "「$it」" } ?: "")
        val metres = maxOf(50, (a.distanceM + 25) / 50 * 50)
        return if (a.amenity == Amenity.BICYCLE_PARKING) "近くの${a.side}${metres}メートルに、${what}があります。"
        else "${metres}メートル先に、${what}があります。"
    }

    /** Read out when earphones connect, once a ride (requirements S-3). */
    const val EARPHONE_NOTICE = "周りの音が聞こえる状態で使ってください。イヤホンで両耳をふさぐと、車や人の近づく音が聞こえません。"

    /**
     * Shown before the first ride (requirements S-3, S-5): the rules as checked for the release
     * (docs/release.md「法令の確認」). Shown, not read out.
     */
    const val SAFETY_NOTICE = "自転車を運転しながらスマートフォンを手に持って使うことや、画面を見続けることは、道路交通法で禁止されています（16 歳以上は反則金 12,000 円）。" +
        "このアプリは画面を見ずに使えます。操作は止まってからにしてください。\n\n" +
        "周りの音が聞こえない状態でのイヤホンの使用も、多くの都道府県の規則で禁止されています。片耳や骨伝導のイヤホンの扱いは都道府県によって違います。" +
        "周りの音が聞こえる状態で使ってください。"

    fun start(): String = "出発しましょう。周りの音が聞こえる状態で、安全に走ってください。"

    fun finish(distanceM: Double): String = "おつかれさまでした。${"%.1f".format(distanceM / 1000)}キロ走りました。"
}
