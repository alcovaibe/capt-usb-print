package com.cryonum.captusbprint.updates

import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UpdateControllerTest {
    private class Preferences : UpdatePreferences {
        override var lastAutomaticAttempt = 0L
        override var deferredCode = 0
    }
    private fun snapshot(code: Int = 100, sdk: Int = 26): UpdateSnapshot {
        val json = """{"schemaVersion":1,"platform":"android","channel":"experimental","applicationId":"com.cryonum.captusbprint","release":{"versionCode":$code,"versionName":"0.1.0-a2.120","minSdk":$sdk,"releaseUrl":"https://github.com/alcovaibe/capt-usb-print/releases/tag/v0.1.0-a2-signed.build.120","notes":{"ru":"Исправления","en":"Fixes"}}}"""
        return UpdateSnapshot(UpdateManifest.parse(json.toByteArray()), null)
    }

    @Test fun automaticIntervalAndManualCooldownAreIndependent() = runBlocking {
        val prefs = Preferences()
        var now = 100_000_000L
        var elapsed = 1000L
        var requests = 0
        val source = UpdateSource { requests++; snapshot() }
        val controller = UpdateController(this, source, prefs, true, 99, 26, { now }, { elapsed })
        controller.enterMain()
        assertEquals(1, requests)
        controller.leaveMain(); controller.enterMain()
        assertEquals(1, requests)
        controller.check(); controller.check()
        assertEquals(2, requests)
        elapsed += 5001
        controller.check()
        assertEquals(3, requests)
        now += 24 * 60 * 60 * 1000L
        controller.enterMain()
        assertEquals(4, requests)
        controller.leaveMain()
    }
    @Test fun deferralSurvivesControllerRecreationButNewerVersionAndManualCheckAppear() = runBlocking {
        val prefs = Preferences()
        var now = 100_000_000L
        var code = 100
        val source = UpdateSource { snapshot(code) }
        fun controller() = UpdateController(this, source, prefs, true, 99, 26, { now }, { now })
        val first = controller()
        first.enterMain(); first.defer(); first.leaveMain()
        assertEquals(100, prefs.deferredCode)
        now += 24 * 60 * 60 * 1000L
        val second = controller()
        second.enterMain()
        assertEquals(UpdateStatus.IDLE, second.state.value.status)
        second.check()
        assertEquals(UpdateStatus.AVAILABLE, second.state.value.status)
        second.leaveMain()
        now += 24 * 60 * 60 * 1000L
        code = 101
        val third = controller()
        third.enterMain()
        assertEquals(UpdateStatus.AVAILABLE, third.state.value.status)
        third.leaveMain()
    }
    @Test fun internalAndInactiveScreensNeverCallSource() = runBlocking {
        var calls = 0
        val controller = UpdateController(this, UpdateSource { calls++; snapshot() }, Preferences(), false, 1000000, 37, { 100000000L }, { 1000L })
        controller.check(); controller.enterMain(); controller.check()
        assertEquals(0, calls)
        assertEquals(UpdateStatus.INTERNAL_DISABLED, controller.state.value.status)
        controller.leaveMain()
        assertEquals(UpdateStatus.IDLE, controller.state.value.status)
    }
    @Test fun leavingMainCancelsPendingRequestAndPreventsLateDialog() = runBlocking {
        val pending = CompletableDeferred<UpdateSnapshot>()
        var cancelled = false
        var calls = 0
        val source = UpdateSource { calls++; try { pending.await() } finally { cancelled = true } }
        val controller = UpdateController(this, source, Preferences(), true, 99, 26, { 100000000L }, { 1000L })
        controller.enterMain(); controller.check()
        assertEquals(1, calls)
        controller.leaveMain()
        pending.complete(snapshot())
        kotlinx.coroutines.yield()
        assertTrue(cancelled)
        assertEquals(UpdateStatus.IDLE, controller.state.value.status)
    }
    @Test fun automaticErrorsAreSilentAndManualErrorsAreActionable() = runBlocking {
        for (error in listOf(IOException(), SocketTimeoutException(), SSLHandshakeException("Fixture"), InvalidUpdateResponse())) {
            val prefs = Preferences()
            var now = 100_000_000L
            val controller = UpdateController(this, UpdateSource { throw error }, prefs, true, 99, 26, { now }, { now })
            controller.enterMain()
            assertEquals(UpdateStatus.IDLE, controller.state.value.status)
            controller.check()
            assertEquals(if (error is InvalidUpdateResponse) UpdateStatus.INVALID_RESPONSE else UpdateStatus.NETWORK_ERROR, controller.state.value.status)
            now += 5001
            controller.check()
            assertNotEquals(UpdateStatus.CHECKING, controller.state.value.status)
            controller.leaveMain()
        }
    }
    @Test fun sameOlderAndIncompatibleVersionsDoNotAdvertiseUpdate() = runBlocking {
        for ((code, sdk) in listOf(99 to 26, 98 to 26, 100 to 38)) {
            val prefs = Preferences().apply { lastAutomaticAttempt = 100000000L }
            val controller = UpdateController(this, UpdateSource { snapshot(code, sdk) }, prefs, true, 99, 37, { 100000000L }, { 1000L })
            controller.enterMain(); controller.check()
            assertEquals(UpdateStatus.CURRENT, controller.state.value.status)
            controller.leaveMain()
        }
    }

    @Test fun downloadSurvivesPermissionRoundTripAndCancellationPreventsLateResult() = runBlocking {
        val prefs = Preferences().apply { lastAutomaticAttempt = 100000000L }
        var pending = CompletableDeferred<java.io.File>()
        val source = object : UpdateDownloader {
            override suspend fun download(release: UpdateManifest.Release, progress: (Long, Long?) -> Unit): java.io.File {
                progress(50, 100)
                return pending.await()
            }
            override suspend fun verify(file: java.io.File, release: UpdateManifest.Release) = Unit
        }
        val controller = UpdateController(this, UpdateSource { snapshot() }, prefs, true, 99, 37, { 100000000L }, { 100000000L }, source)
        controller.enterMain(); controller.check(); controller.download()
        assertEquals(UpdateStatus.DOWNLOADING, controller.state.value.status)
        assertEquals(50L, controller.state.value.received)
        controller.leaveMain()
        val file = java.io.File.createTempFile("update-test", ".apk")
        try {
            pending.complete(file)
            kotlinx.coroutines.yield()
            assertEquals(UpdateStatus.READY, controller.state.value.status)
            assertNull(controller.verifiedApk())
            controller.enterMain()
            assertEquals(file, controller.verifiedApk())
            controller.leaveMain(); controller.enterMain()
            assertEquals(UpdateStatus.READY, controller.state.value.status)
            controller.defer()
            assertEquals(UpdateStatus.IDLE, controller.state.value.status)
        } finally { file.delete() }
        controller.close()
    }

    @Test fun cancelledDownloadCannotReopenDialogAndInternalCannotDownload() = runBlocking {
        for (enabled in listOf(true, false)) {
            var calls = 0
            val pending = CompletableDeferred<java.io.File>()
            val source = object : UpdateDownloader {
                override suspend fun download(release: UpdateManifest.Release, progress: (Long, Long?) -> Unit): java.io.File {
                    calls++
                    return pending.await()
                }
                override suspend fun verify(file: java.io.File, release: UpdateManifest.Release) = Unit
            }
            val controller = UpdateController(this, UpdateSource { snapshot() }, Preferences(), enabled, 99, 37, { 100000000L }, { 100000000L }, source)
            controller.enterMain(); controller.download(); controller.download()
            assertEquals(if (enabled) 1 else 0, calls)
            controller.defer()
            pending.complete(java.io.File("unused-fixture"))
            kotlinx.coroutines.yield()
            assertEquals(UpdateStatus.IDLE, controller.state.value.status)
            controller.close()
        }
    }
}
