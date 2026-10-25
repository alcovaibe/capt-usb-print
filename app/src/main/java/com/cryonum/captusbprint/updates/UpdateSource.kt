package com.cryonum.captusbprint.updates

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal data class UpdateSnapshot(val manifest: UpdateManifest, val etag: String?)

internal fun interface UpdateSource {
    suspend fun load(cached: UpdateSnapshot?): UpdateSnapshot
}

internal class InvalidUpdateResponse : IOException("Invalid update response")

/** Independent cookie-free client, not the privacy viewer or any authenticated app client. */
internal class SiteUpdateSource(internal val client: OkHttpClient = newClient()) : UpdateSource, AutoCloseable {
    private val closed = AtomicBoolean()

    override suspend fun load(cached: UpdateSnapshot?): UpdateSnapshot = suspendCancellableCoroutine { continuation ->
        if (closed.get()) {
            continuation.resumeWithException(IOException("Update client closed"))
            return@suspendCancellableCoroutine
        }
        val request = Request.Builder().url(UpdateUrls.manifest(UpdateUrls.MANIFEST).toString())
            .get().header("Accept", "application/json")
            .header("User-Agent", "CAPT-USB-Print-Updates/1")
            .header("Cache-Control", "no-cache")
            .apply { cached?.etag?.let { header("If-None-Match", it) } }.build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        if (closed.get()) call.cancel()
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val snapshot = response.use { parseResponse(it, cached) }
                    if (continuation.isActive) continuation.resume(snapshot)
                } catch (error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } catch (error: IllegalArgumentException) {
                    if (continuation.isActive) continuation.resumeWithException(InvalidUpdateResponse())
                }
            }
        })
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        client.dispatcher.cancelAll()
        // TLS close_notify can perform I/O even for an idle pooled connection.
        // ViewModel.onCleared runs on Main; use the client's own executor for cleanup.
        val executor = client.dispatcher.executorService
        executor.execute {
            try { client.connectionPool.evictAll() } finally { executor.shutdown() }
        }
    }

    internal companion object {
        fun newClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .cookieJar(CookieJar.NO_COOKIES).cache(null)
            .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS).build()

        fun parseResponse(response: Response, cached: UpdateSnapshot?): UpdateSnapshot {
            if (response.code == 304) return cached?.takeIf { it.etag != null } ?: throw InvalidUpdateResponse()
            if (response.code in 300..399) throw InvalidUpdateResponse()
            if (response.code != 200) throw IOException("Update server unavailable")
            val body = response.body ?: throw InvalidUpdateResponse()
            val type = body.contentType()
            if ((type?.type to type?.subtype) !in setOf("application" to "json", "text" to "plain") ||
                type?.charset(Charsets.UTF_8) != Charsets.UTF_8 || body.contentLength() > UpdateManifest.MAX_BYTES
            ) throw InvalidUpdateResponse()
            val bytes = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(2048)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (bytes.size() + count > UpdateManifest.MAX_BYTES) throw InvalidUpdateResponse()
                    bytes.write(buffer, 0, count)
                }
            }
            val manifest = try { UpdateManifest.parse(bytes.toByteArray()) } catch (_: IllegalArgumentException) {
                throw InvalidUpdateResponse()
            }
            val previousCode = cached?.manifest?.release?.versionCode ?: 0
            if ((manifest.release?.versionCode ?: 0) < previousCode) throw InvalidUpdateResponse()
            val etag = response.header("ETag")?.takeIf { value ->
                value.length in 2..160 && Regex("(?:W/)?\"[\\x21\\x23-\\x7e]*\"").matches(value)
            }
            return UpdateSnapshot(manifest, etag)
        }
    }
}
