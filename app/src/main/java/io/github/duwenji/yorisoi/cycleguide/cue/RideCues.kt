package io.github.duwenji.yorisoi.cycleguide.cue

import io.github.duwenji.yorisoi.core.format.PresentAs
import io.github.duwenji.yorisoi.core.format.Trigger
import io.github.duwenji.yorisoi.core.format.material.BuiltInProducers
import io.github.duwenji.yorisoi.core.format.material.MaterialType
import io.github.duwenji.yorisoi.cycleguide.guide.Line
import io.github.duwenji.yorisoi.cycleguide.guide.Narrator
import io.github.duwenji.yorisoi.cycleguide.guide.PoiSpot
import io.github.duwenji.yorisoi.kit.channel.Station
import io.github.duwenji.yorisoi.kit.channel.material.Content
import io.github.duwenji.yorisoi.kit.channel.material.Credit
import io.github.duwenji.yorisoi.kit.channel.material.CueFinder
import io.github.duwenji.yorisoi.kit.channel.material.CueFire
import io.github.duwenji.yorisoi.kit.channel.material.CuePresenter
import io.github.duwenji.yorisoi.kit.channel.material.Material
import io.github.duwenji.yorisoi.kit.channel.material.MaterialContext
import io.github.duwenji.yorisoi.kit.channel.material.MaterialLog
import io.github.duwenji.yorisoi.kit.channel.material.MaterialRequest
import io.github.duwenji.yorisoi.kit.channel.material.MaterialResult
import io.github.duwenji.yorisoi.kit.channel.material.Producer
import io.github.duwenji.yorisoi.kit.channel.material.Producers
import io.github.duwenji.yorisoi.kit.channel.material.Situation
import io.github.duwenji.yorisoi.kit.channel.material.Subject
import io.github.duwenji.yorisoi.kit.channel.remote.ChannelSync
import io.github.duwenji.yorisoi.kit.geo.Poi

/**
 * The ride as this app's built-in producers read it (platform ADR-ECOSYSTEM-PLATFORM E-16):
 * whether the rider is moving, how many characters may be said now (F-4), and, for a point cue,
 * the nearest spot inside it, which the AI narrates.
 */
data class RideContext(val riding: Boolean, val maxChars: Int, val pointSpot: PoiSpot? = null) : Situation

/** `builtin:ai-guide`: the AI's spot narration ([Narrator.spot]) on the grounds the request carries. */
class AiGuideProducer(private val narrator: Narrator) : Producer {
    override val descriptor = BuiltInProducers.AI_GUIDE

    override suspend fun produce(request: MaterialRequest): MaterialResult {
        val ride = request.context.situation as? RideContext ?: return MaterialResult.None
        val spot = when (val s = request.subject) {
            is PoiSpot -> s
            is Subject.Point -> ride.pointSpot ?: return MaterialResult.None
            else -> return MaterialResult.None
        }
        val line = narrator.spot(spot.poi, spot.distanceM, ride.riding, request.context.station, ride.maxChars, request.grounding)
            ?: return MaterialResult.Unavailable(MaterialResult.Reason.FAILED)
        return MaterialResult.Ready(Material(MaterialType.TEXT, Content.Text(line.text), descriptor.id, Credit(line.credit, "generated")))
    }
}

/** `builtin:fallback-text`: the fixed spot line, without the AI. A point has no name to say. */
class FallbackTextProducer : Producer {
    override val descriptor = BuiltInProducers.FALLBACK_TEXT

    override suspend fun produce(request: MaterialRequest): MaterialResult {
        val spot = request.subject as? PoiSpot ?: return MaterialResult.None
        val riding = (request.context.situation as? RideContext)?.riding ?: true
        val text = if (riding) "この先、${spot.name}があります。" else "近くに、${spot.name}があります。"
        return MaterialResult.Ready(Material(MaterialType.TEXT, Content.Text(text), descriptor.id))
    }
}

/**
 * A channel's spot and point cues on a ride (requirements F-32): which fire, and what they say.
 * Only the spoken presentation is made ([CuePresenter] with `{speak}`), so a card's AI is never
 * asked; the line is its first text. Counted for `repeat` in [shown] (the ride's) and in the
 * finder's memory.
 */
class RideCues(narrator: Narrator, private val finder: CueFinder, log: MaterialLog) {
    private val presenter = CuePresenter(
        Producers(listOf(AiGuideProducer(narrator), FallbackTextProducer()), log),
        ChannelSync.MEDIA_STAGE, log, shows = setOf(PresentAs.SPEAK),
    )

    /** The cue that fires on [poi] now, if [station] names it by its id or name (or its kind). */
    fun spot(station: Station, poi: Poi, distanceM: Int?, shown: Set<String>, now: Long): CueFire? {
        val media = station.materials?.media ?: return null
        return finder.spot(station.id, media, PoiSpot(poi, distanceM), shown, now)
    }

    /** Whether a cue names [poi] itself (not only its kind): the spot rule then picks it first. */
    fun names(station: Station, poi: Poi, shown: Set<String>, now: Long): Boolean {
        return spot(station, poi, null, shown, now)?.cue?.trigger is Trigger.Spot
    }

    /** The point cue the rider at [lat], [lon] is inside, if it may still fire. */
    fun point(station: Station, lat: Double, lon: Double, shown: Set<String>, now: Long): CueFire? {
        val media = station.materials?.media ?: return null
        return finder.point(station.id, media, lat, lon, shown, now)
    }

    /** What [fire] says on this ride: its first spoken text, with its credit; null when it has none. */
    suspend fun speak(fire: CueFire, station: Station, ride: RideContext): Line? {
        val shown = presenter.present(fire, station, MaterialContext(station, ride))
        val m = shown.firstOrNull { it.presentAs == PresentAs.SPEAK }?.items?.firstOrNull() ?: return null
        val text = (m.content as? Content.Text)?.text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Line(text, m.credit?.author)
    }

    fun record(fire: CueFire, shown: MutableSet<String>, now: Long) = finder.record(fire, shown, now)
}
