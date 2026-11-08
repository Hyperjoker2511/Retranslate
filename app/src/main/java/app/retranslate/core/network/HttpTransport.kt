package app.retranslate.core.network

import android.util.Log
import app.retranslate.BuildConfig
import app.retranslate.core.error.AppError
import app.retranslate.core.error.AppResult
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import kotlin.coroutines.resume

/** An HTTP response as providers consume it: status, body text, headers. */
data class HttpResponse(
    val code: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap()
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * The one HTTP boundary (PLAN.md §2.3). Providers never touch OkHttp
 * directly: they call [get] and receive either an [HttpResponse] (any
 * status code — mapping codes to [AppError]s is the provider's job, via
 * [app.retranslate.providers.hub.HttpErrors]) or an [AppResult.Err] for a
 * transport-level failure (no route, timeout, TLS…) already mapped.
 */
interface HttpTransport {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): AppResult<HttpResponse>

    /**
     * POST a JSON body with extra headers (added in P2 for the keyed
     * providers: Gemini, DeepL, Azure, LibreTranslate). Header values may
     * carry API keys: they travel only to the provider's own endpoint,
     * and the redacting logger (§2.3) never logs headers or bodies.
     */
    suspend fun post(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap()
    ): AppResult<HttpResponse>
}

/**
 * DNS that prefers IPv4 (v1.0.1). Some home LANs hand out IPv6 routes
 * that blackhole, and OkHttp tries resolved addresses in order — a dead
 * IPv6 address first burned the whole connect timeout before IPv4 was
 * ever tried, so every provider looked "down" while the browser (which
 * races the stacks) worked fine. All addresses are kept, IPv4 first;
 * IPv6 remains available as the fallback. Relative order within each
 * family is preserved (stable sort).
 */
class Ipv4FirstDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        delegate.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 }
}

/** Maps a transport exception to the §10 catalogue. Never swallows cancellation. */
fun mapTransportException(providerId: String, t: Throwable): AppError = when (t) {
    is UnknownHostException -> AppError.NoNetwork
    is SocketTimeoutException -> AppError.ProviderDown(providerId, detail = t.technicalDetail())
    is IOException -> AppError.ProviderDown(providerId, detail = t.technicalDetail())
    else -> AppError.Unknown(t)
}

/**
 * "SSLHandshakeException: …" — the exception's class and message only.
 * Transport exceptions never carry request headers or key material, so
 * this is safe to show in the UI's "Details:" line (§10 row 16 spirit).
 */
private fun Throwable.technicalDetail(): String {
    val name = javaClass.simpleName
    val message = message?.trim().orEmpty()
    return if (message.isEmpty()) name else "$name: ${message.take(200)}"
}

/**
 * OkHttp-backed [HttpTransport]. One client app-wide (§2.3) with:
 *  - a user-agent interceptor (several free instances reject OkHttp's
 *    default UA),
 *  - a redacting logger, debug builds only: host, status, timing, body
 *    size — never URLs' query text, never bodies, never keys.
 *
 * Per-call timeouts come from the client this transport is built with
 * (§4.5 budgets are set where the transport is constructed, in DI).
 */
class OkHttpTransport(
    private val client: OkHttpClient,
    private val providerTag: String = "http"
) : HttpTransport {

    override suspend fun get(url: String, headers: Map<String, String>): AppResult<HttpResponse> {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (name, value) -> builder.header(name, value) }
        return execute(builder.build())
    }

    override suspend fun post(
        url: String,
        jsonBody: String,
        headers: Map<String, String>
    ): AppResult<HttpResponse> {
        val body = jsonBody.toRequestBody(JSON_MEDIA_TYPE)
        val builder = Request.Builder().url(url).post(body)
        headers.forEach { (name, value) -> builder.header(name, value) }
        return execute(builder.build())
    }

    private suspend fun execute(request: Request): AppResult<HttpResponse> =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resume(AppResult.Err(mapTransportException(providerTag, e)))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!continuation.isActive) {
                        response.close()
                        return
                    }
                    val result = try {
                        val body: ResponseBody? = response.body
                        val text = body?.string().orEmpty()
                        val headers = response.headers.names().associateWith { name ->
                            response.header(name).orEmpty()
                        }
                        AppResult.Ok(HttpResponse(response.code, text, headers))
                    } catch (t: Throwable) {
                        AppResult.Err(mapTransportException(providerTag, t))
                    }
                    continuation.resume(result)
                }
            })
        }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** The app-wide client (§2.3): UA interceptor + redacting logger. */
        fun appClient(
            connectTimeoutSec: Long = 10,
            readTimeoutSec: Long = 15,
            callTimeoutSec: Long = 20
        ): OkHttpClient = OkHttpClient.Builder()
            // v1.0.1: prefer IPv4 — broken/blackholed LAN IPv6 routes made
            // every provider time out on the user's home Wi-Fi.
            .dns(Ipv4FirstDns())
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .callTimeout(callTimeoutSec, TimeUnit.SECONDS)
            // Instances rate-limit aggressively; a redirect usually means a
            // parked/bot-check page, which providers must see and reject —
            // so redirects are surfaced, not followed.
            .followRedirects(false)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .build()
                val started = System.nanoTime()
                val response = chain.proceed(request)
                if (BuildConfig.DEBUG) {
                    val ms = (System.nanoTime() - started) / 1_000_000
                    Log.d(
                        "HttpTransport",
                        "${request.method} ${request.url.host} -> ${response.code} " +
                            "(${ms}ms, ${response.body?.contentLength() ?: -1}B)"
                    )
                }
                response
            }
            .build()

        /** Same UA the P1 live smoke tests were run with — verified accepted. */
        const val USER_AGENT = "ReTranslate/1.0 (Android; +app.retranslate)"
    }
}
