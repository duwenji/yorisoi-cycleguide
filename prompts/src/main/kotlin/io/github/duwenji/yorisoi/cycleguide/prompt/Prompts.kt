package io.github.duwenji.yorisoi.cycleguide.prompt

import io.github.duwenji.yorisoi.core.format.Slot
import io.github.duwenji.yorisoi.core.format.TalkEventKind
import io.github.duwenji.yorisoi.core.prompt.PromptTemplates

/**
 * The app's prompt files, kept as resources under `cycleguide/prompts/`. They live in this prompt
 * pack (`:prompts`, plain JVM) so the channel management system renders the very prompts the app
 * sends. Paths are listed here so code and files can't drift apart silently; a unit test renders
 * every one of them.
 */
object Prompts {
    const val RESOURCE_DIR = "cycleguide/prompts"

    object Guide {
        const val SYSTEM = "guide/system"
        const val USER = "guide/user"
    }

    object Talk {
        const val SYSTEM = "companion/system"
        const val SITUATION = "companion/situation"
        /** The channel's extra instructions for an event, appended after the event's prompt. */
        const val STATION_EVENT = "companion/station_event"
    }

    /** One file per ride event in `companion/events/`, and the channel event it may add instructions to. */
    enum class Event(val file: String, val stationEvent: TalkEventKind) {
        START("start", TalkEventKind.START),
        REST("rest", TalkEventKind.REST),
        MILESTONE("milestone", TalkEventKind.MILESTONE),
        FINISH("finish", TalkEventKind.FINISH),
    }

    fun event(e: Event) = "companion/events/${e.file}"

    /** Variables for `guide/system`: the channel's slots, and the length the ride allows now. */
    fun guideSystemVars(slot: (Slot) -> String?, maxChars: Int): Map<String, Any?> = mapOf(
        "persona" to slot(Slot.GUIDE_PERSONA),
        "focus" to slot(Slot.GUIDE_FOCUS),
        "max_chars" to maxChars,
    )

    /** Variables for `companion/system`. */
    fun companionSystemVars(slot: (Slot) -> String?, maxChars: Int): Map<String, Any?> = mapOf(
        "persona" to slot(Slot.COMPANION_PERSONA),
        "topics" to slot(Slot.COMPANION_TOPICS),
        "max_chars" to maxChars,
    )

    /**
     * The user prompt for a companion event: the situation, the event's own prompt and, if the
     * channel has any for this event, its extra instructions.
     */
    fun companionUser(
        prompts: PromptTemplates,
        situationVars: Map<String, Any?>,
        event: Event,
        eventVars: Map<String, Any?>,
        slot: (Slot) -> String?,
    ): String {
        val extra = Slot.forEvent(event.stationEvent)?.let(slot)
            ?.let { "\n\n" + prompts.render(Talk.STATION_EVENT, mapOf("text" to it)) }.orEmpty()
        return prompts.render(Talk.SITUATION, situationVars) + "\n\n" + prompts.render(event(event), eventVars) + extra
    }

    /** The prompt files bundled with this library (in the app, inside the APK). */
    fun fromResources() = PromptTemplates { name ->
        val path = "$RESOURCE_DIR/$name.md"
        val stream = Prompts::class.java.classLoader.getResourceAsStream(path)
            ?: throw IllegalArgumentException("No prompt file $path")
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
