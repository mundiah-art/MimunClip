package com.mundiah.mimunclip.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mundiah.mimunclip.data.GroqClient
import com.mundiah.mimunclip.data.HistoryStore
import com.mundiah.mimunclip.data.QwenPlanner
import com.mundiah.mimunclip.data.SettingsStore
import com.mundiah.mimunclip.model.ClipCandidate
import com.mundiah.mimunclip.model.ClipResult
import com.mundiah.mimunclip.model.RenderSettings
import com.mundiah.mimunclip.model.Segment
import com.mundiah.mimunclip.service.ProcessingService
import com.mundiah.mimunclip.video.FfmpegRenderer
import com.mundiah.mimunclip.video.MediaStoreSaver
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

enum class Screen { HOME, SETTINGS, HOOKS, RESULTS }

data class UiState(
    val screen: Screen = Screen.HOME,
    val settings: RenderSettings = RenderSettings(),
    val groqKeysRaw: String = "",
    val groqKeysMasked: String = "",
    val videoUri: Uri? = null,
    val videoName: String? = null,
    val videoDurationSec: Double = 0.0,
    val segments: List<Segment> = emptyList(),
    val candidates: List<ClipCandidate> = emptyList(),
    val selectedStarts: Set<Int> = emptySet(),
    val results: List<ClipResult> = emptyList(),
    val history: List<ClipResult> = emptyList(),
    val isBusy: Boolean = false,
    val progress: Float = 0f,
    val progressLabel: String = "",
    val log: List<String> = emptyList(),
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val context: Context get() = getApplication()
    private val settingsStore = SettingsStore(context)
    private val historyStore = HistoryStore(context)
    private val renderer = FfmpegRenderer(context)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null
    private var inputFile: File? = null
    private var workDir: File? = null

    init {
        viewModelScope.launch {
            try {
                _state.update {
                    it.copy(
                        settings = settingsStore.load(),
                        groqKeysRaw = settingsStore.loadGroqKeysRaw(),
                        groqKeysMasked = settingsStore.maskedKeysSummary(),
                        history = historyStore.loadAll(),
                    )
                }
            } catch (e: Exception) {
                // Pengaturan/riwayat gagal dibaca (mis. penyimpanan korup):
                // app tetap jalan dengan nilai default, bukan force close.
                _state.update { it.copy(error = "Pengaturan gagal dibaca, memakai default: ${e.message}") }
            }
        }
    }

    private fun log(line: String) {
        _state.update { it.copy(log = (it.log + line).takeLast(40)) }
    }

    private fun setProgress(label: String, progress: Float) {
        _state.update { it.copy(progressLabel = label, progress = progress) }
        ProcessingService.updateProgress(
            context, "MimunClip", label, (progress * 100).toInt()
        )
    }

    fun goTo(screen: Screen) {
        _state.update { it.copy(screen = screen, error = null) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun cancelWork() {
        job?.cancel()
        job = null
        ProcessingService.stop(context)
        _state.update { it.copy(isBusy = false, progressLabel = "Dibatalkan") }
    }

    fun pickVideo(uri: Uri, displayName: String) {
        viewModelScope.launch {
            try {
                val info = renderer.probe(uri)
                _state.update {
                    it.copy(
                        videoUri = uri, videoName = displayName,
                        videoDurationSec = info.durationSec,
                        segments = emptyList(), candidates = emptyList(),
                        selectedStarts = emptySet(), error = null,
                    )
                }
                log("Video dipilih: $displayName (${info.durationSec.toInt()} dtk)")
            } catch (e: Exception) {
                _state.update { it.copy(error = "Video tidak bisa dibaca: ${e.message}") }
            }
        }
    }

    fun updateSettings(settings: RenderSettings) {
        _state.update { it.copy(settings = settings) }
        viewModelScope.launch { settingsStore.save(settings) }
    }

    fun saveGroqKeys(raw: String) {
        settingsStore.saveGroqKeysRaw(raw)
        _state.update {
            it.copy(groqKeysRaw = raw, groqKeysMasked = settingsStore.maskedKeysSummary())
        }
    }

    fun refreshHistory() {
        viewModelScope.launch {
            _state.update { it.copy(history = historyStore.loadAll()) }
        }
    }

    fun removeHistory(id: String) {
        viewModelScope.launch {
            historyStore.remove(id) // hanya catatan; file video TIDAK dihapus
            _state.update { it.copy(history = historyStore.loadAll()) }
        }
    }

    /** Langkah 1: ekstrak audio -> Whisper -> Qwen rapikan + usulkan 4 hook. */
    fun startTranscription() {
        val uri = _state.value.videoUri ?: return
        val keys = GroqClient.parseKeys(_state.value.groqKeysRaw)
        if (keys.isEmpty()) {
            _state.update { it.copy(error = "Isi dulu Groq API key di Pengaturan.") }
            return
        }
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(isBusy = true, progress = 0f, log = emptyList(), error = null) }
            try {
                if (!ProcessingService.start(context, "MimunClip — Transkripsi")) {
                    log("Notifikasi progres tidak bisa dimulai (sistem menolak); proses tetap jalan.")
                }
                setProgress("Menyalin video…", 0.02f)
                inputFile = renderer.copyInputToCache(uri)
                val source = inputFile!!
                val duration = renderer.probe(Uri.fromFile(source)).durationSec
                _state.update { it.copy(videoDurationSec = duration) }

                workDir = File(context.cacheDir, "work_${System.currentTimeMillis()}").apply { mkdirs() }
                setProgress("Ekstrak audio…", 0.08f)
                log("Ekstrak audio (mono 16 kHz)…")
                val audio = renderer.extractAudioForWhisper(source, workDir!!) { pct ->
                    setProgress("Ekstrak audio…", 0.08f + pct / 100f * 0.12f)
                }

                val groq = GroqClient(keys)
                val settings = _state.value.settings
                setProgress("Transkripsi Whisper…", 0.22f)
                log("Groq Whisper (${com.mundiah.mimunclip.model.QwenDefaults.WHISPER_MODEL})…")
                val segments = groq.transcribe(audio) { note -> log(note) }
                log("Whisper selesai: ${segments.size} segmen")
                _state.update { it.copy(segments = segments) }

                setProgress("Qwen merapikan transkrip…", 0.55f)
                val planner = QwenPlanner(groq)
                val cleaned = try {
                    planner.cleanTranscript(segments, settings.qwenModel, settings.qwenFallbackModel)
                } catch (e: Exception) {
                    log("Rapikan transkrip gagal, pakai teks mentah: ${e.message}")
                    segments
                }
                _state.update { it.copy(segments = cleaned) }

                setProgress("Qwen memilih hook…", 0.7f)
                log("Qwen memilih kandidat hook…")
                val candidates = planner.planCandidates(
                    cleaned, duration, settings.qwenModel, settings.qwenFallbackModel
                )
                if (candidates.isEmpty()) {
                    throw IllegalStateException(
                        "Tidak ada segmen bersih yang memenuhi syarat klip minimal 28 detik. " +
                            "Coba video lain yang ucapannya lebih panjang."
                    )
                }
                log("Ditemukan ${candidates.size} kandidat hook")
                _state.update {
                    it.copy(
                        candidates = candidates, selectedStarts = emptySet(),
                        screen = Screen.HOOKS, progress = 1f, progressLabel = "Selesai",
                    )
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Throwable (bukan hanya Exception) supaya Error dari library
                // native (mis. FFmpeg gagal dimuat) jadi pesan error di layar,
                // bukan force close. Pesan error TIDAK boleh "null": sebagian
                // exception (mis. NullPointerException) tidak punya message,
                // jadi tampilkan nama kelasnya supaya penyebabnya terlacak.
                val detail = e.message?.takeIf { it.isNotBlank() }
                    ?: e.javaClass.simpleName
                _state.update { it.copy(error = "Transkripsi gagal: $detail") }
                log("GAGAL: ${e.javaClass.simpleName}: $detail")
            } finally {
                ProcessingService.stop(context)
                _state.update { it.copy(isBusy = false) }
            }
        }
    }

    fun toggleCandidate(startSegmentId: Int) {
        val max = _state.value.settings.clipCount
        _state.update { state ->
            val selected = state.selectedStarts.toMutableSet()
            if (startSegmentId in selected) {
                selected.remove(startSegmentId)
            } else if (selected.size < max) {
                selected.add(startSegmentId)
            }
            state.copy(selectedStarts = selected)
        }
    }

    /** Langkah 2: render klip yang dipilih user, simpan ke Downloads/MimunClip. */
    fun renderSelected() {
        val state = _state.value
        val source = inputFile ?: return
        val chosen = state.candidates
            .filter { it.startSegmentId in state.selectedStarts }
            .sortedBy { it.startSegmentId }
        if (chosen.isEmpty()) {
            _state.update { it.copy(error = "Pilih dulu hook yang mau di-render.") }
            return
        }
        if (chosen.size != state.settings.clipCount) {
            _state.update {
                it.copy(error = "Pilih tepat ${state.settings.clipCount} hook (baru ${chosen.size}).")
            }
            return
        }
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(isBusy = true, progress = 0f, error = null) }
            val dir = workDir ?: File(context.cacheDir, "work_${System.currentTimeMillis()}").apply { mkdirs() }
            workDir = dir
            val results = mutableListOf<ClipResult>()
            try {
                if (!ProcessingService.start(context, "MimunClip — Render")) {
                    log("Notifikasi progres tidak bisa dimulai (sistem menolak); proses tetap jalan.")
                }
                chosen.forEachIndexed { index, candidate ->
                    val label = "Render klip ${index + 1}/${chosen.size}: ${candidate.title}"
                    setProgress(label, index.toFloat() / chosen.size)
                    log(label)
                    val outcome = renderer.renderClip(
                        source, candidate, index + 1, state.settings, dir
                    ) { pct ->
                        val overall = (index + pct / 100f) / chosen.size
                        setProgress(label, overall)
                    }
                    log("Klip ${index + 1}: ${outcome.slsrNote}")
                    val fileName = MediaStoreSaver.fileNameFor(candidate.title)
                    val uri = MediaStoreSaver.saveToDownloads(context, outcome.file, fileName)
                    val result = ClipResult(
                        id = UUID.randomUUID().toString(),
                        title = candidate.title,
                        hookQuote = candidate.hookQuote,
                        caption = candidate.caption,
                        fileName = fileName,
                        uri = uri.toString(),
                        durationSec = candidate.durationSec,
                        slsrApplied = outcome.slsrApplied,
                        slsrNote = outcome.slsrNote,
                        createdAtMillis = System.currentTimeMillis(),
                    )
                    historyStore.add(result)
                    results += result
                    log("Tersimpan: Downloads/MimunClip/$fileName")
                }
                dir.deleteRecursively()
                workDir = null
                val history = historyStore.loadAll()
                _state.update {
                    it.copy(
                        results = results, history = history,
                        screen = Screen.RESULTS, progress = 1f,
                        progressLabel = "Selesai — ${results.size} klip tersimpan",
                    )
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val detail = e.message?.takeIf { it.isNotBlank() }
                    ?: e.javaClass.simpleName
                _state.update { it.copy(error = "Render gagal: $detail") }
                log("GAGAL: ${e.javaClass.simpleName}: $detail")
            } finally {
                ProcessingService.stop(context)
                _state.update { it.copy(isBusy = false) }
            }
        }
    }
}
