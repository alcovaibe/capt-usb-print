package com.cryonum.captusbprint.updates

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal class InvalidUpdateApk : IOException("Invalid update APK")

internal interface UpdateDownloader {
    suspend fun download(release: UpdateManifest.Release, progress: (Long, Long?) -> Unit): File
    suspend fun verify(file: File, release: UpdateManifest.Release)
    fun retainForInstaller(file: File) {}
}

/** Downloads only the exact release announced by the existing website manifest. */
internal class GithubUpdateDownloader(
    private val directory: File,
    private val verifier: suspend (File, UpdateManifest.Release) -> Unit,
    internal val client: OkHttpClient = newClient(),
) : UpdateDownloader, AutoCloseable {
    constructor(context: Context) : this(File(context.cacheDir, "updates"), { file, release -> verifyApk(context, file, release) })
    private val installerFiles = java.util.concurrent.ConcurrentHashMap.newKeySet<File>()
    override fun retainForInstaller(file: File) { installerFiles.add(file) }
    private val ownedFiles = java.util.concurrent.ConcurrentHashMap.newKeySet<File>()

    override suspend fun download(release: UpdateManifest.Release, progress: (Long, Long?) -> Unit): File {
        val file = withContext(Dispatchers.IO) {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create update directory")
            File.createTempFile("update-", ".apk", directory).also { ownedFiles.add(it) }
        }
        try {
            var url = downloadUrl(release)
            repeat(6) {
                currentCoroutineContext().ensureActive()
                val redirect = downloadResponse(url, file, progress)
                if (redirect == null) {
                    verify(file, release)
                    return file
                }
                url = redirect
            }
            throw IOException("Too many download redirects")
        } catch (error: Exception) {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { file.delete() }
            throw error
        }
    }

    private suspend fun downloadResponse(url: HttpUrl, file: File, progress: (Long, Long?) -> Unit): HttpUrl? =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(url)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "CAPT-USB-Print-Updates/1").build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val redirect = response.use {
                            if (it.code in listOf(301, 302, 303, 307, 308)) {
                                val location = it.header("Location") ?: throw InvalidUpdateApk()
                                validatedRedirect(url, location)
                            } else {
                                if (it.code != 200) throw IOException("Download unavailable")
                                val body = it.body ?: throw InvalidUpdateApk()
                                val expected = body.contentLength().takeIf { length -> length >= 0 }
                                if (expected != null && (expected == 0L || expected > MAX_APK_BYTES)) throw InvalidUpdateApk()
                                var received = 0L
                                var lastProgress = 0L
                                progress(0, expected)
                                file.outputStream().use { output ->
                                    body.byteStream().use { input ->
                                        val buffer = ByteArray(32 * 1024)
                                        while (true) {
                                            if (call.isCanceled()) throw IOException("Download cancelled")
                                            val count = input.read(buffer)
                                            if (count == -1) break
                                            received += count
                                            if (received > MAX_APK_BYTES) throw InvalidUpdateApk()
                                            output.write(buffer, 0, count)
                                            if (received - lastProgress >= 64 * 1024) {
                                                progress(received, expected)
                                                lastProgress = received
                                            }
                                        }
                                    }
                                }
                                if (received == 0L || (expected != null && received != expected)) throw InvalidUpdateApk()
                                progress(received, expected)
                                null
                            }
                        }
                        if (continuation.isActive) continuation.resume(redirect)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    } finally {
                        if (call.isCanceled()) file.delete()
                    }
                }
            })
        }

    override suspend fun verify(file: File, release: UpdateManifest.Release) = verifier(file, release)

    override fun close() {
        client.dispatcher.cancelAll()
        val executor = client.dispatcher.executorService
        executor.execute {
            try {
                client.connectionPool.evictAll()
                ownedFiles.filterNot { it in installerFiles }.forEach { it.delete() }
            } finally { executor.shutdown() }
        }
    }

    internal companion object {
        fun newClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .cookieJar(CookieJar.NO_COOKIES).cache(null)
            .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES).build()

        @Suppress("DEPRECATION")
        suspend fun verifyApk(context: Context, file: File, release: UpdateManifest.Release) = withContext(Dispatchers.IO) {
            if (!file.isFile || file.length() !in 1..MAX_APK_BYTES) throw InvalidUpdateApk()
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val candidate = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: throw InvalidUpdateApk()
            val installed = pm.getPackageInfo(context.packageName, flags)
            val candidateCode = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
            val installedCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
            val app = candidate.applicationInfo ?: throw InvalidUpdateApk()
            // Deliberately require the current signer(s); key rotation needs an explicit migration.
            val expected = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
            val actual = if (Build.VERSION.SDK_INT >= 28) candidate.signingInfo?.apkContentsSigners else candidate.signatures
            validateIdentity(
                release, context.packageName, installedCode, Build.VERSION.SDK_INT,
                ApkIdentity(candidate.packageName, candidateCode, candidate.versionName, app.minSdkVersion,
                    app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0, actual?.map { it.toCharsString() }?.toSet().orEmpty()),
                expected?.map { it.toCharsString() }?.toSet().orEmpty(),
            )
            // Android's package installer performs the final cryptographic APK verification.
        }

        fun validateIdentity(release: UpdateManifest.Release, installedPackage: String, installedCode: Long,
            sdk: Int, apk: ApkIdentity, installedSigners: Set<String>) {
            if (installedPackage != UpdateUrls.APPLICATION_ID || apk.packageName != installedPackage ||
                apk.code != release.versionCode.toLong() || apk.code <= installedCode ||
                apk.name != release.versionName || apk.minSdk != release.minSdk || apk.minSdk > sdk ||
                apk.debuggable || installedSigners.isEmpty() || apk.signers != installedSigners
            ) throw InvalidUpdateApk()
        }

        const val MAX_APK_BYTES = 100L * 1024 * 1024
        fun downloadUrl(release: UpdateManifest.Release): HttpUrl {
            UpdateUrls.release(release.releaseUrl)
            require(release.releaseUrl.endsWith("/${UpdateUrls.tag(release.versionName)}"))
            val build = release.versionName.substringAfterLast('.')
            return "https://github.com/alcovaibe/capt-usb-print/releases/download/${UpdateUrls.tag(release.versionName)}/CAPT-USB-Print-0.1.0-a2-build-$build.apk".toHttpUrl()
        }
        fun validatedRedirect(from: HttpUrl, location: String): HttpUrl {
            val url = from.resolve(location) ?: throw InvalidUpdateApk()
            if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() ||
                url.fragment != null || url.host !in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")
            ) throw InvalidUpdateApk()
            return url
        }
    }
}

internal data class ApkIdentity(val packageName: String?, val code: Long, val name: String?,
    val minSdk: Int, val debuggable: Boolean, val signers: Set<String>)
