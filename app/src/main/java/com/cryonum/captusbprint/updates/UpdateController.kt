package com.cryonum.captusbprint.updates

import android.os.Build
import android.os.SystemClock
import java.io.IOException
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

internal enum class UpdateStatus { IDLE, CHECKING, AVAILABLE, CURRENT, NETWORK_ERROR, INVALID_RESPONSE, INTERNAL_DISABLED, DOWNLOADING, READY, DOWNLOAD_ERROR, INVALID_APK, INSTALL_ERROR }
internal data class UpdateUiState(val status: UpdateStatus = UpdateStatus.IDLE, val release: UpdateManifest.Release? = null, val received: Long = 0, val total: Long? = null, val apk: File? = null)

internal interface UpdatePreferences {
    var lastAutomaticAttempt: Long
    var deferredCode: Int
}

internal class UpdateController(
    private val scope: CoroutineScope,
    private val source: UpdateSource,
    private val preferences: UpdatePreferences,
    private val enabled: Boolean,
    private val installedCode: Long,
    private val sdk: Int,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monotonicClock: () -> Long = SystemClock::elapsedRealtime,
    private val downloader: UpdateDownloader? = null,
) {
    private val mutableState = MutableStateFlow(UpdateUiState())
    val state = mutableState.asStateFlow()
    private var active = false
    private var transferJob: Job? = null
    @Volatile private var transferGeneration = 0L
    private var generation = 0L
    private var job: Job? = null
    private var lastManualAttempt: Long? = null
    private var cached: UpdateSnapshot? = null

    fun enterMain() {
        active = true
        if (!enabled || mutableState.value.status in transferStatuses) return
        val now = wallClock()
        val last = preferences.lastAutomaticAttempt
        // Clock rollback must not create a request loop or suppress checks forever.
        if (last != 0L && now < last) { preferences.lastAutomaticAttempt = now; return }
        if (last == 0L || now - last >= AUTOMATIC_INTERVAL) check(manual = false)
    }

    fun leaveMain() {
        active = false
        generation++
        job?.cancel()
        job = null
        if (mutableState.value.status !in transferStatuses) mutableState.value = UpdateUiState()
    }

    fun check(manual: Boolean = true) {
        if (!active || job?.isActive == true || mutableState.value.status in transferStatuses) return
        if (!enabled) {
            if (manual) mutableState.value = UpdateUiState(UpdateStatus.INTERNAL_DISABLED)
            return
        }
        val elapsed = monotonicClock()
        if (manual && lastManualAttempt?.let { elapsed - it < MANUAL_INTERVAL } == true) return
        if (!requests.tryLock()) return
        if (manual) lastManualAttempt = elapsed else preferences.lastAutomaticAttempt = wallClock()
        val requestGeneration = generation
        mutableState.value = UpdateUiState(UpdateStatus.CHECKING)
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = source.load(cached)
                if (!active || requestGeneration != generation) return@launch
                cached = result
                val update = result.manifest.release?.takeIf { it.isUpdateFor(installedCode, sdk) }
                mutableState.value = when {
                    update != null && (manual || update.versionCode > preferences.deferredCode) -> UpdateUiState(UpdateStatus.AVAILABLE, update)
                    manual -> UpdateUiState(UpdateStatus.CURRENT)
                    else -> UpdateUiState()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: InvalidUpdateResponse) {
                if (active && requestGeneration == generation) mutableState.value = UpdateUiState(if (manual) UpdateStatus.INVALID_RESPONSE else UpdateStatus.IDLE)
            } catch (_: IOException) {
                if (active && requestGeneration == generation) mutableState.value = UpdateUiState(if (manual) UpdateStatus.NETWORK_ERROR else UpdateStatus.IDLE)
            } finally {
                requests.unlock()
            }
        }
    }

    fun defer() {
        mutableState.value.release?.let { preferences.deferredCode = maxOf(preferences.deferredCode, it.versionCode) }
        dismiss()
    }
    fun dismiss() {
        transferGeneration++
        transferJob?.cancel()
        transferJob = null
        mutableState.value.apk?.let { file -> scope.launch(Dispatchers.IO) { file.delete() } }
        mutableState.value = UpdateUiState()
    }

    fun download() {
        if (!enabled || !active || transferJob?.isActive == true) return
        val release = mutableState.value.release ?: return
        val source = downloader ?: return
        val currentGeneration = ++transferGeneration
        mutableState.value = UpdateUiState(UpdateStatus.DOWNLOADING, release)
        transferJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val file = source.download(release) { received, total ->
                    mutableState.update { current ->
                        if (current.status == UpdateStatus.DOWNLOADING && current.release === release &&
                            currentGeneration == transferGeneration) current.copy(received = received, total = total) else current
                    }
                }
                if (currentGeneration == transferGeneration) {
                    mutableState.value = UpdateUiState(UpdateStatus.READY, release, apk = file)
                } else withContext(Dispatchers.IO) { file.delete() }
            } catch (error: CancellationException) {
                throw error
            } catch (_: InvalidUpdateApk) {
                if (currentGeneration == transferGeneration) mutableState.value = UpdateUiState(UpdateStatus.INVALID_APK, release)
            } catch (_: Exception) {
                if (currentGeneration == transferGeneration) mutableState.value = UpdateUiState(UpdateStatus.DOWNLOAD_ERROR, release)
            }
        }
    }

    suspend fun verifiedApk(): File? {
        if (!enabled || !active) return null
        val state = mutableState.value
        val file = state.apk ?: return null
        val release = state.release ?: return null
        val source = downloader ?: return null
        try {
            source.verify(file, release)
            return file.takeIf { active && mutableState.value == state }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            withContext(Dispatchers.IO) { file.delete() }
            if (mutableState.value == state) mutableState.value = UpdateUiState(UpdateStatus.INVALID_APK, release)
            return null
        }
    }

    fun installFailed() {
        mutableState.update { state ->
            if (state.apk != null) state.copy(status = UpdateStatus.INSTALL_ERROR) else state
        }
    }

    fun close() {
        leaveMain()
        transferGeneration++
        transferJob?.cancel()
        // A system installer may hold a read grant; expire completed files next session.
        mutableState.value = UpdateUiState()
    }

    private companion object {
        val transferStatuses = setOf(UpdateStatus.DOWNLOADING, UpdateStatus.READY, UpdateStatus.DOWNLOAD_ERROR, UpdateStatus.INVALID_APK, UpdateStatus.INSTALL_ERROR)
        const val AUTOMATIC_INTERVAL = 24 * 60 * 60 * 1000L
        const val MANUAL_INTERVAL = 5_000L
        val requests = Mutex()
    }
}
