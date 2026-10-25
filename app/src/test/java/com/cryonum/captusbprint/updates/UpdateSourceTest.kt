package com.cryonum.captusbprint.updates

import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import com.cryonum.captusbprint.BuildConfig
import kotlinx.coroutines.runBlocking
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class UpdateSourceTest {
    private val json = """{"schemaVersion":1,"platform":"android","channel":"experimental","applicationId":"com.cryonum.captusbprint","release":null}"""
    private fun response(code: Int = 200, bytes: ByteArray = json.toByteArray(), type: String = "application/json") = Response.Builder()
        .request(Request.Builder().url(UpdateUrls.MANIFEST).build()).protocol(Protocol.HTTP_1_1)
        .code(code).message("Fixture").body(bytes.toResponseBody(type.toMediaType()))

    @Test fun clientDisablesRedirectsCookiesAuthenticationAndRetries() {
        assertFalse(BuildConfig.PUBLIC_UPDATE_CHECKS) // debug never contacts the release channel
        SiteUpdateSource().use { source ->
            assertFalse(source.client.followRedirects)
            assertFalse(source.client.followSslRedirects)
            assertFalse(source.client.retryOnConnectionFailure)
            assertSame(CookieJar.NO_COOKIES, source.client.cookieJar)
            assertNull(source.client.cache)
            assertEquals(15000, source.client.callTimeoutMillis)
        }
    }
    @Test fun actualClientRejectsRedirectWithoutSecondRequestOrCredentials() = runBlocking {
        var calls = 0
        val client = SiteUpdateSource.newClient().newBuilder().addInterceptor { chain ->
            calls++
            val request = chain.request()
            assertEquals(UpdateUrls.MANIFEST, request.url.toString())
            for (header in listOf("Authorization", "Cookie", "Referer")) assertNull(request.header(header))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(302).message("Redirect")
                .header("Location", "http://evil.example/update.json").body("".toResponseBody()).build()
        }.build()
        SiteUpdateSource(client).use { source ->
            try { source.load(null); fail("Redirect accepted") } catch (_: InvalidUpdateResponse) { }
            assertEquals(1, calls)
        }
    }
    @Test fun tlsFailureIsReturnedWithoutRetryOrTrustOverride() = runBlocking {
        var calls = 0
        val client = SiteUpdateSource.newClient().newBuilder().addInterceptor {
            calls++
            throw SSLHandshakeException("Untrusted fixture certificate")
        }.build()
        SiteUpdateSource(client).use { source ->
            try { source.load(null); fail("TLS error ignored") } catch (_: SSLHandshakeException) { }
            assertEquals(1, calls)
        }
    }
    @Test fun validJsonAndConditionalResponse() {
        val cached = response().header("ETag", "\"fixture\"").build().use { SiteUpdateSource.parseResponse(it, null) }
        assertNull(cached.manifest.release)
        response(304).build().use { assertSame(cached, SiteUpdateSource.parseResponse(it, cached)) }
        response(304).build().use { assertThrows(InvalidUpdateResponse::class.java) { SiteUpdateSource.parseResponse(it, null) } }
        val unsafe = response().header("ETag", "unquoted").build().use { SiteUpdateSource.parseResponse(it, null) }
        assertNull(unsafe.etag)
    }
    @Test fun redirectsHttpErrorsWrongTypeAndMalformedJsonAreRejected() {
        for (code in listOf(301, 302, 303, 307, 308)) response(code).header("Location", "https://evil.example/").build().use {
            assertThrows(InvalidUpdateResponse::class.java) { SiteUpdateSource.parseResponse(it, null) }
        }
        for (code in listOf(403, 404, 500)) response(code).build().use {
            assertThrows(IOException::class.java) { SiteUpdateSource.parseResponse(it, null) }
        }
        response(type = "text/html").build().use { assertThrows(InvalidUpdateResponse::class.java) { SiteUpdateSource.parseResponse(it, null) } }
        response(bytes = "{".toByteArray()).build().use { assertThrows(InvalidUpdateResponse::class.java) { SiteUpdateSource.parseResponse(it, null) } }
    }
    @Test fun rawGithubUtf8PlainTextIsParsedAsStrictJson() {
        response(type = "text/plain; charset=utf-8").build().use {
            assertNull(SiteUpdateSource.parseResponse(it, null).manifest.release)
        }
        response(type = "text/plain; charset=iso-8859-1").build().use {
            assertThrows(InvalidUpdateResponse::class.java) { SiteUpdateSource.parseResponse(it, null) }
        }
    }
    @Test fun declaredAndStreamedSizesAreBounded() {
        response(bytes = ByteArray(16385)).build().use { assertThrows(InvalidUpdateResponse::class.java) { SiteUpdateSource.parseResponse(it, null) } }
        val streamed = object : ResponseBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = -1L
            override fun source() = Buffer().write(ByteArray(16385))
        }
        response().body(streamed).build().use { assertThrows(InvalidUpdateResponse::class.java) { SiteUpdateSource.parseResponse(it, null) } }
    }
}
