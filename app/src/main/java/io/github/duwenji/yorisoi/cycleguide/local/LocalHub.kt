package io.github.duwenji.yorisoi.cycleguide.local

import android.content.Context
import io.github.duwenji.yorisoi.kit.geo.Geohash
import io.github.duwenji.yorisoi.kit.llm.RelayClient
import io.github.duwenji.yorisoi.kit.local.Block
import io.github.duwenji.yorisoi.kit.local.ClipCache
import io.github.duwenji.yorisoi.kit.local.ClipPlayer
import io.github.duwenji.yorisoi.kit.local.MyNote
import io.github.duwenji.yorisoi.kit.local.Note
import io.github.duwenji.yorisoi.kit.local.NoteRecognizer
import io.github.duwenji.yorisoi.kit.local.NoteTtl
import io.github.duwenji.yorisoi.kit.local.PlacedNote
import io.github.duwenji.yorisoi.kit.local.Reading
import io.github.duwenji.yorisoi.kit.local.RelayTokens
import io.github.duwenji.yorisoi.kit.local.ReportReason
import io.github.duwenji.yorisoi.kit.local.LocalClient
import io.github.duwenji.yorisoi.kit.local.LocalFeed
import io.github.duwenji.yorisoi.kit.local.LocalMemory
import io.github.duwenji.yorisoi.kit.local.LocalSettingsStore
import io.github.duwenji.yorisoi.kit.local.Terms
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
class LocalHub(context: Context, relay: RelayClient?, baseUrl: String, speaking: StateFlow<Boolean>) {
    val settings = LocalSettingsStore(context)
    private val memory = LocalMemory(File(context.filesDir, "studio.json"))
    private val client: LocalClient? = relay?.let { LocalClient(baseUrl, RelayTokens(it)) }
    private val clips = ClipCache(File(context.cacheDir, "studio-clips"))
    val player = ClipPlayer(speaking)
    val recognizer = NoteRecognizer(context)

    private val _feed = MutableStateFlow<LocalFeed?>(null)
    /** The feed of the ride in progress; null outside a ride. */
    val feed: StateFlow<LocalFeed?> = _feed.asStateFlow()

    val available: Boolean get() = client != null

    /** Whether this device can place notes: the studio, and on-device speech recognition (REQ-NOTES F-2). */
    val canPlace: Boolean get() = client != null && recognizer.available

    /** A ride starts: a new feed, which first sends what an earlier ride couldn't. */
    fun startRide(): LocalFeed? {
        val c = client ?: return null
        return LocalFeed(c, memory, clips, { settings.settings.value }).also { _feed.value = it }
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

    /** Places a note where the user is, rounded to its geohash7 (REQ-NOTES D-4). */
    suspend fun place(text: String, lat: Double, lon: Double, spotName: String?, ttl: NoteTtl): PlacedNote =
        client!!.placeNote(text, Geohash.encode(lat, lon, 7), spotName, ttl, settings.settings.value.termsVersion)

    fun liked(note: Note): Boolean = memory.isLiked(note.id)

    fun like(note: Note) {
        _feed.value?.like(note) ?: memory.like(note.id, Instant.now())
    }

    fun opened(reading: Reading) {
        _feed.value?.opened(reading) ?: memory.markOpened(LocalFeed.refOf(reading), Instant.now())
    }

    suspend fun report(reading: Reading, reason: ReportReason) = client!!.report(LocalFeed.refOf(reading), reason)

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
