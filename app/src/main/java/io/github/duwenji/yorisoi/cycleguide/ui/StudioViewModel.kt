package io.github.duwenji.yorisoi.cycleguide.ui

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.duwenji.yorisoi.kit.studio.Block
import io.github.duwenji.yorisoi.kit.studio.MyNote
import io.github.duwenji.yorisoi.kit.studio.NoteRecognizer
import io.github.duwenji.yorisoi.kit.studio.NoteTtl
import io.github.duwenji.yorisoi.kit.studio.Reading
import io.github.duwenji.yorisoi.kit.studio.ReportReason
import io.github.duwenji.yorisoi.kit.studio.StudioException
import io.github.duwenji.yorisoi.kit.studio.StudioSettings
import io.github.duwenji.yorisoi.kit.studio.Terms
import io.github.duwenji.yorisoi.cycleguide.CycleApp
import io.github.duwenji.yorisoi.kit.geo.LatLon
import io.github.duwenji.yorisoi.kit.geo.RouteGeometry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where placing a note is (platform REQ-STUDIO F-1, D-24). */
sealed interface NoteStep {
    data object Idle : NoteStep
    /** The note terms to agree to first. */
    data class Terms(val terms: io.github.duwenji.yorisoi.kit.studio.Terms) : NoteStep
    data object Listening : NoteStep
    /** What was heard, to check before it is sent. */
    data class Confirm(val text: String, val spotName: String?) : NoteStep
    data object Sending : NoteStep
}

/**
 * The studio on the screens (platform kit-studio README「画面」): placing a note, what the rider does
 * with a note or placement read out, and the studio's settings, own notes and blocks. The screens
 * are shown only while stopped, so all of it is done standing (kit-studio README「アプリごとの違い」).
 */
class StudioViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CycleApp
    private val hub = app.studio

    val settings: StateFlow<StudioSettings> = hub.settings.settings
    val available: Boolean get() = hub.available
    val canPlace: Boolean get() = hub.canPlace

    private val _step = MutableStateFlow<NoteStep>(NoteStep.Idle)
    val step: StateFlow<NoteStep> = _step.asStateFlow()

    private val _mine = MutableStateFlow<Pair<List<MyNote>, Int>?>(null)
    val mine: StateFlow<Pair<List<MyNote>, Int>?> = _mine.asStateFlow()

    private val _blocks = MutableStateFlow<List<Pair<Block, String?>>?>(null)
    val blocks: StateFlow<List<Pair<Block, String?>>?> = _blocks.asStateFlow()

    private fun tell(text: String) = Toast.makeText(app, text, Toast.LENGTH_SHORT).show()

    private fun failure(e: Exception): String = (e as? StudioException)?.message ?: "スタジオに接続できませんでした"

    // ---- placing a note ----

    /** Agreeing and listening, until a note is heard; cancelled with the note. */
    private var noteJob: Job? = null

    /** Starts placing a note: the terms first when they are new, then listening. */
    fun startNote() {
        noteJob?.cancel()
        noteJob = viewModelScope.launch { termsOrListen() }
    }

    private suspend fun termsOrListen() {
        try {
            val terms = hub.termsToAccept()
            if (terms != null) _step.value = NoteStep.Terms(terms) else listen()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _step.value = NoteStep.Idle
            tell(failure(e))
        }
    }

    fun acceptTerms(terms: Terms) {
        hub.acceptTerms(terms.version)
        noteJob?.cancel()
        noteJob = viewModelScope.launch { listen() }
    }

    private suspend fun listen() {
        _step.value = NoteStep.Listening
        val text = hub.recognizer.listen()
        if (text == null) {
            _step.value = NoteStep.Idle
            tell("聞き取れませんでした")
            return
        }
        _step.value = NoteStep.Confirm(NoteRecognizer.fit(text), nearSpot())
    }

    /** The name of the nearest spot within [SPOT_NEAR_M], for the note to say where it is. */
    private fun nearSpot(): String? {
        val here = app.spots.location.value?.let { LatLon(it.latitude, it.longitude) } ?: return null
        return app.spots.spots.value
            .map { it to RouteGeometry.distanceM(here, LatLon(it.lat, it.lon)) }
            .filter { it.second <= SPOT_NEAR_M }
            .minByOrNull { it.second }?.first?.name
    }

    /** Stops placing a note; while listening, the recognizer stops too and nothing is sent. */
    fun cancelNote() {
        noteJob?.cancel()
        _step.value = NoteStep.Idle
    }

    fun send(text: String, spotName: String?, ttl: NoteTtl) = viewModelScope.launch {
        val location = app.spots.location.value
        if (location == null) {
            _step.value = NoteStep.Idle
            tell("今の場所が分かりません")
            return@launch
        }
        _step.value = NoteStep.Sending
        try {
            val placed = hub.place(text.trim(), location.latitude, location.longitude, spotName, ttl)
            tell("一言を置きました（今日はあと ${placed.remainingToday} 件）")
        } catch (e: Exception) {
            tell(failure(e))
        }
        _step.value = NoteStep.Idle
    }

    // ---- what is read out ----

    fun liked(reading: Reading.OfNote): Boolean = hub.liked(reading.note)

    fun like(reading: Reading.OfNote) {
        hub.like(reading.note)
        tell("いいねを送ります（走行の終わりに）")
    }

    /** Opens a placement: its audio again, counted as opened (API-STUDIO `opened`). */
    fun open(reading: Reading) {
        hub.opened(reading)
        val audio = (reading as? Reading.OfPlacement)?.audio ?: return
        viewModelScope.launch { hub.player.play(audio) }
    }

    fun report(reading: Reading, reason: ReportReason) = viewModelScope.launch {
        try {
            hub.report(reading, reason)
            tell("知らせました。運営が確かめます")
        } catch (e: Exception) {
            tell(failure(e))
        }
    }

    fun block(reading: Reading.OfNote) = viewModelScope.launch {
        try {
            hub.block(reading.note)
            tell("この人の一言は、これから届きません")
        } catch (e: Exception) {
            tell(failure(e))
        }
    }

    // ---- settings ----

    fun setListenNotes(on: Boolean) = hub.settings.update { it.copy(listenNotes = on, explained = it.explained || on) }

    fun setListenPlacements(on: Boolean) = hub.settings.update { it.copy(listenPlacements = on, explained = it.explained || on) }

    fun loadMine() = viewModelScope.launch {
        try {
            _mine.value = hub.myNotes()
        } catch (e: Exception) {
            tell(failure(e))
        }
    }

    fun deleteNote(id: String) = viewModelScope.launch {
        try {
            hub.deleteNote(id)
            _mine.value = hub.myNotes()
        } catch (e: Exception) {
            tell(failure(e))
        }
    }

    fun loadBlocks() = viewModelScope.launch {
        try {
            _blocks.value = hub.blocks()
        } catch (e: Exception) {
            tell(failure(e))
        }
    }

    fun unblock(id: String) = viewModelScope.launch {
        try {
            hub.unblock(id)
            _blocks.value = hub.blocks()
        } catch (e: Exception) {
            tell(failure(e))
        }
    }

    companion object {
        private const val SPOT_NEAR_M = 150.0
        const val TERMS_URL = "https://github.com/duwenji/yorisoi-platform/blob/main/docs/policies/studio-note-terms.md"
    }
}
