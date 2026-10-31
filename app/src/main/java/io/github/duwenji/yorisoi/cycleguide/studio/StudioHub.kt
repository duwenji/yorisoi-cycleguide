package io.github.duwenji.yorisoi.cycleguide.studio

import android.content.Context
import io.github.duwenji.yorisoi.kit.geo.Geohash
import io.github.duwenji.yorisoi.kit.llm.RelayClient
import io.github.duwenji.yorisoi.kit.studio.Block
import io.github.duwenji.yorisoi.kit.studio.ClipCache
import io.github.duwenji.yorisoi.kit.studio.ClipPlayer
import io.github.duwenji.yorisoi.kit.studio.MyNote
import io.github.duwenji.yorisoi.kit.studio.Note
import io.github.duwenji.yorisoi.kit.studio.NoteRecognizer
import io.github.duwenji.yorisoi.kit.studio.NoteTtl
import io.github.duwenji.yorisoi.kit.studio.PlacedNote
import io.github.duwenji.yorisoi.kit.studio.Reading
import io.github.duwenji.yorisoi.kit.studio.RelayTokens
import io.github.duwenji.yorisoi.kit.studio.ReportReason
import io.github.duwenji.yorisoi.kit.studio.StudioClient
import io.github.duwenji.yorisoi.kit.studio.StudioFeed
import io.github.duwenji.yorisoi.kit.studio.StudioMemory
import io.github.duwenji.yorisoi.kit.studio.StudioSettingsStore
import io.github.duwenji.yorisoi.kit.studio.Terms
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.Instant

/**
 * The studio in CycleGuide (platform kit-studio): its settings, what the device keeps, the feed of
 * the ride in progress, and what the screens do with notes and placements. Without the operator's
 * relay there is no studio ([available] is false).
 */
class StudioHub(context: Context, relay: RelayClient?, baseUrl: String, speaking: StateFlow<Boolean>) {
    val settings = StudioSettingsStore(context)
    private val memory = StudioMemory(File(context.filesDir, "studio.json"))
    private val client: StudioClient? = relay?.let { StudioClient(baseUrl, RelayTokens(it)) }
    private val clips = ClipCache(File(context.cacheDir, "studio-clips"))
    val player = ClipPlayer(speaking)
    val recognizer = NoteRecognizer(context)

    private val _feed = MutableStateFlow<StudioFeed?>(null)
    /** The feed of the ride in progress; null outside a ride. */
    val feed: StateFlow<StudioFeed?> = _feed.asStateFlow()

    val available: Boolean get() = client != null

    /** Whether this device can place notes: the studio, and on-device speech recognition (REQ-STUDIO F-2). */
    val canPlace: Boolean get() = client != null && recognizer.available

    /** A ride starts: a new feed, which first sends what an earlier ride couldn't. */
    fun startRide(): StudioFeed? {
        val c = client ?: return null
        return StudioFeed(c, memory, clips, { settings.settings.value }).also { _feed.value = it }
    }

    fun endRide() {
        _feed.value = null
    }

    /** The note terms to agree to before placing a note, or null when the current ones are agreed to. */
    suspend fun termsToAccept(): Terms? {
        val terms = client?.terms() ?: return null
        return terms.takeIf { it.version > settings.settings.value.termsVersion }
    }

    fun acceptTerms(version: Int) = settings.update { it.copy(termsVersion = version) }

    /** Places a note where the user is, rounded to its geohash7 (REQ-STUDIO D-4). */
    suspend fun place(text: String, lat: Double, lon: Double, spotName: String?, ttl: NoteTtl): PlacedNote =
        client!!.placeNote(text, Geohash.encode(lat, lon, 7), spotName, ttl, settings.settings.value.termsVersion)

    fun liked(note: Note): Boolean = memory.isLiked(note.id)

    fun like(note: Note) {
        _feed.value?.like(note) ?: memory.like(note.id, Instant.now())
    }

    fun opened(reading: Reading) {
        _feed.value?.opened(reading) ?: memory.markOpened(StudioFeed.refOf(reading), Instant.now())
    }

    suspend fun report(reading: Reading, reason: ReportReason) = client!!.report(StudioFeed.refOf(reading), reason)

    /** Blocks the author of [note]; its text is kept on the device to show which block it is. */
    suspend fun block(note: Note) {
        val block = client!!.block(note.id)
        memory.rememberBlock(block.id, note.text, Instant.now())
        _feed.value?.dropNote(note.id)
    }

    /** The blocks, each with the text of the note it was made from when the device still has it. */
    suspend fun blocks(): List<Pair<Block, String?>> = client!!.blocks().map { it to memory.blockLabel(it.id) }

    suspend fun unblock(id: String) {
        client!!.unblock(id)
        memory.forgetBlock(id, Instant.now())
    }

    suspend fun myNotes(): Pair<List<MyNote>, Int> = client!!.myNotes()

    suspend fun deleteNote(id: String) = client!!.deleteNote(id)
}
