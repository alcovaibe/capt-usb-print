package com.cryonum.captusbprint.updates

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateDownloadTest {
    @get:Rule val temp = TemporaryFolder()
    private fun release() = UpdateManifest.parse("""{"schemaVersion":1,"platform":"android","channel":"experimental","applicationId":"com.cryonum.captusbprint","release":{"versionCode":30,"versionName":"0.1.0-a2.123","minSdk":26,"releaseUrl":"https://github.com/alcovaibe/capt-usb-print/releases/tag/v0.1.0-a2-signed.build.123","notes":{"ru":"Исправления","en":"Fixes"}}}""".toByteArray()).release!!

    @Test fun exactReleaseUrlAndOnlyHttpsGithubAssetRedirects() {
        val from = GithubUpdateDownloader.downloadUrl(release())
        assertEquals("https://github.com/alcovaibe/capt-usb-print/releases/download/v0.1.0-a2-signed.build.123/CAPT-USB-Print-0.1.0-a2-build-123.apk", from.toString())
        assertEquals("release-assets.githubusercontent.com", GithubUpdateDownloader.validatedRedirect(from, "https://release-assets.githubusercontent.com/a?signature=1").host)
        for (url in listOf("http://release-assets.githubusercontent.com/a", "https://evil.example/a", "https://release-assets.githubusercontent.com.evil.example/a", "https://user@release-assets.githubusercontent.com/a", "https://release-assets.githubusercontent.com:444/a", "https://release-assets.githubusercontent.com/a#fragment", "https://github.com/other/repo/releases/download/a")) {
            assertThrows(InvalidUpdateApk::class.java) { GithubUpdateDownloader.validatedRedirect(from, url) }
        }
    }

    @Test fun downloadFollowsValidatedRedirectStreamsProgressAndVerifiesBeforeReturning() = runBlocking {
        var calls = 0
        var verified = false
        val bytes = ByteArray(80_000) { 42 }
        val client = GithubUpdateDownloader.newClient().newBuilder().addInterceptor { chain ->
            calls++
            assertNull(chain.request().header("Authorization"))
            assertNull(chain.request().header("Cookie"))
            val response = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("Fixture")
            if (calls == 1) response.code(302).header("Location", "https://release-assets.githubusercontent.com/fixture?sig=1").body("".toResponseBody()).build()
            else response.code(200).body(bytes.toResponseBody()).build()
        }.build()
        GithubUpdateDownloader(temp.root, { file, _ -> assertArrayEquals(bytes, file.readBytes()); verified = true }, client).use {
            var received = 0L
            val file = it.download(release()) { count, total -> received = count; assertEquals(bytes.size.toLong(), total) }
            assertTrue(verified)
            assertEquals(2, calls)
            assertEquals(bytes.size.toLong(), received)
            assertTrue(file.isFile)
        }
    }

    @Test fun failuresNeverReturnAnApkAndRemovePartialFiles() = runBlocking {
        for (mode in listOf("http", "redirect", "oversize", "empty", "invalid-apk", "truncated")) {
            val folder = temp.newFolder()
            val client = GithubUpdateDownloader.newClient().newBuilder().addInterceptor { chain ->
                val body = when (mode) {
                    "oversize", "truncated" -> object : ResponseBody() {
                        override fun contentType() = null
                        override fun contentLength() = if (mode == "oversize") GithubUpdateDownloader.MAX_APK_BYTES + 1 else 100
                        override fun source() = Buffer().write(byteArrayOf(1))
                    }
                    "empty" -> byteArrayOf().toResponseBody()
                    else -> byteArrayOf(1).toResponseBody()
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("Fixture")
                    .code(if (mode == "http") 404 else if (mode == "redirect") 302 else 200)
                    .header("Location", "https://evil.example/a").body(body).build()
            }.build()
            GithubUpdateDownloader(folder, { _, _ -> throw InvalidUpdateApk() }, client).use {
                try { it.download(release()) { _, _ -> }; fail(mode) } catch (_: IOException) { }
                assertEquals(mode, 0, folder.listFiles()!!.size)
            }
        }
    }

    @Test fun wrongPackageVersionSdkDebuggableOrSignerIsRejected() {
        val valid = ApkIdentity(UpdateUrls.APPLICATION_ID, 30, "0.1.0-a2.123", 26, false, setOf("trusted-cert"))
        fun verify(apk: ApkIdentity, installedCode: Long = 29, signers: Set<String> = valid.signers) =
            GithubUpdateDownloader.validateIdentity(release(), UpdateUrls.APPLICATION_ID, installedCode, 36, apk, signers)
        verify(valid)
        for (apk in listOf(valid.copy(packageName = "other.app"), valid.copy(code = 29), valid.copy(code = 31),
            valid.copy(name = "0.1.0-a2.124"), valid.copy(minSdk = 37), valid.copy(debuggable = true),
            valid.copy(signers = emptySet()), valid.copy(signers = setOf("other-cert")),
            valid.copy(signers = setOf("trusted-cert", "other-cert")))) {
            assertThrows(InvalidUpdateApk::class.java) { verify(apk) }
        }
        assertThrows(InvalidUpdateApk::class.java) { verify(valid, installedCode = 30) }
        assertThrows(InvalidUpdateApk::class.java) { verify(valid, installedCode = 31) }
        assertThrows(InvalidUpdateApk::class.java) { verify(valid, signers = emptySet()) }
        assertThrows(InvalidUpdateApk::class.java) {
            GithubUpdateDownloader.validateIdentity(release(), UpdateUrls.APPLICATION_ID + ".internal", 29, 36, valid, valid.signers)
        }
    }

    @Test fun cancellingAnInFlightRequestRemovesItsFile() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val unblock = java.util.concurrent.CountDownLatch(1)
        val client = GithubUpdateDownloader.newClient().newBuilder().addInterceptor { chain ->
            entered.complete(Unit)
            try {
                check(unblock.await(5, java.util.concurrent.TimeUnit.SECONDS))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("Fixture")
                    .code(200).body(byteArrayOf(1).toResponseBody()).build()
            } finally { finished.complete(Unit) }
        }.build()
        GithubUpdateDownloader(temp.root, { _, _ -> fail("Cancelled download was verified") }, client).use {
            val pending = async { it.download(release()) { _, _ -> } }
            entered.await()
            pending.cancelAndJoin()
            unblock.countDown()
            finished.await()
            assertEquals(0, temp.root.listFiles()!!.size)
        }
    }
}
