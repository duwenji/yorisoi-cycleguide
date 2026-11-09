package io.github.duwenji.yorisoi.cycleguide.companion

/**
 * What a word between the other lines is about (platform REQ-NEARBY-TALK F-15), chosen by the app
 * in turn, so that the AI doesn't talk of the weather every time. [label] goes in the prompt.
 */
enum class ChatTopic(val label: String) {
    SEASON_WEATHER("季節と天気"),
    TIME_OF_DAY("時間帯"),
    ROUTE("走ってきた道と距離"),
    NEARBY("周りの見どころ"),
    BODY("体のこと（水分・休憩）"),
    CHANNEL("このチャンネルらしい話題"),
    ;

    companion object {
        /** The body comes up after this long, not at the start. */
        const val BODY_AFTER_MS = 30 * 60_000L

        /** None of the last this many again. */
        const val NOT_AGAIN = 2

        /**
         * The next topic after [recent] (oldest first), in the order above: not one of the last
         * [NOT_AGAIN], nor one there is nothing for ([nearbyKnown]: spots around; the body before
         * [BODY_AFTER_MS]). Null when none is left.
         */
        fun next(recent: List<ChatTopic>, elapsedMs: Long, nearbyKnown: Boolean): ChatTopic? {
            val usable = entries.filter {
                (it != NEARBY || nearbyKnown) && (it != BODY || elapsedMs >= BODY_AFTER_MS) && it !in recent.takeLast(NOT_AGAIN)
            }
            val after = recent.lastOrNull()?.ordinal ?: -1
            return usable.firstOrNull { it.ordinal > after } ?: usable.firstOrNull()
        }
    }
}
