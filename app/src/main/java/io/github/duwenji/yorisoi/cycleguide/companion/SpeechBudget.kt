package io.github.duwenji.yorisoi.cycleguide.companion

import io.github.duwenji.yorisoi.cycleguide.style.StyleItem
import io.github.duwenji.yorisoi.cycleguide.style.StyleValues

/**
 * How long the companion may talk now (requirements F-4, D-6): the ride style's seconds for the
 * rider's state, in characters. A line can't be timed before it is read out, so it is measured by
 * its length at [CHARS_PER_SEC] (to be checked against the developer mode's records).
 */
object SpeechBudget {
    const val CHARS_PER_SEC = 6

    fun seconds(values: StyleValues, state: MotionState): Int = values[
        when (state) {
            MotionState.MOVING -> StyleItem.SPEECH_MOVING_SEC
            MotionState.SIGNAL_STOP -> StyleItem.SPEECH_SIGNAL_SEC
            MotionState.REST -> StyleItem.SPEECH_REST_SEC
        },
    ]

    fun maxChars(values: StyleValues, state: MotionState): Int = seconds(values, state) * CHARS_PER_SEC

    /** The characters read out: spaces and line breaks take no time. */
    fun chars(text: String): Int = text.count { !it.isWhitespace() }

    fun fits(text: String, maxChars: Int): Boolean = chars(text) <= maxChars
}
