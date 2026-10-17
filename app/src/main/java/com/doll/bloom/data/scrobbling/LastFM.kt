package com.doll.bloom.data.scrobbling

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/**
 * Result wrapper for Last.fm calls that never converts coroutine cancellation
 * into an ordinary API failure. Swallowing cancellation lets a dismissed
 * screen continue saving state or retrying network work after its owner is
 * gone, which is both wasteful and a source of stale UI updates.
 */
internal suspend fun <T> lastfmCallResult(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        Result.failure(failure)
    }

object LastFM {
    private const val LASTFM_USER_AGENT = "DollBloom (https://github.com/DominatorStufs/DollBloom)"

    const val DEFAULT_API_ENDPOINT = "https://ws.audioscrobbler.com/2.0/"
    const val LIBREFM_API_ENDPOINT = "https://libre.fm/2.0/"

    /**
     * The [LastFmException.code] of a call never made because no complete
     * application pair is configured — negative, so it can't collide with a
     * code Last.fm itself returns. See [postAndRead]'s guard.
     */
    const val ERROR_NOT_CONFIGURED = -1
    const val NOT_CONFIGURED_MESSAGE = "Last.fm API credentials are not configured for this build"

    /**
     * Last.fm's own code for "this token has not been authorized yet" — the
     * expected answer from `auth.getSession` while the listener is still on
     * the authorization page, so the poll treats it as "ask again", never as
     * a failure. See `pollForLastfmSession`.
     */
    const val ERROR_TOKEN_NOT_AUTHORIZED = 14

    /**
     * The [LastFmException.code] of an authorization page nobody confirmed
     * inside the poll window — negative, so it cannot collide with a code
     * Last.fm itself returns, like [ERROR_NOT_CONFIGURED].
     */
    const val ERROR_AUTHORIZE_TIMEOUT = -2
    const val AUTHORIZE_TIMEOUT_MESSAGE = "The Last.fm authorization page was not confirmed in time"

    @Serializable
    data class Session(val name: String, val key: String, val subscriber: Int)

    @Serializable
    data class Authentication(val session: Session)

    @Serializable
    data class TokenResponse(val token: String)

    @Serializable
    data class LastFmError(val error: Int, val message: String)

    data class RuntimeConfig(
        val endpoint: String,
        val apiKey: String,
        val secret: String,
        val sessionKey: String?,
    )

    @Volatile
    private var runtimeConfig =
        RuntimeConfig(
            endpoint = DEFAULT_API_ENDPOINT,
            apiKey = "",
            secret = "",
            sessionKey = null,
        )

    var sessionKey: String?
        get() = runtimeConfig.sessionKey
        set(value) {
            runtimeConfig = runtimeConfig.copy(sessionKey = value)
        }

    private val json =
        Json {
            isLenient = true
            ignoreUnknownKeys = true
        }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }
            expectSuccess = false
        }
    }

    private fun Map<String, String>.apiSig(secret: String): String {
        val sorted = toSortedMap()
        val toHash = sorted.entries.joinToString("") { it.key + it.value } + secret
        val digest = MessageDigest.getInstance("MD5").digest(toHash.toByteArray())
        return digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private fun HttpRequestBuilder.lastfmParams(
        method: String,
        apiKey: String,
        secret: String,
        sessionKey: String? = null,
        extra: Map<String, String> = emptyMap(),
        format: String = "json",
    ) {
        headers.append(HttpHeaders.UserAgent, LASTFM_USER_AGENT)
        val paramsForSig =
            mutableMapOf(
                "method" to method,
                "api_key" to apiKey,
            ).apply {
                sessionKey?.let { put("sk", it) }
                putAll(extra)
            }
        val apiSig = paramsForSig.apiSig(secret)

        setBody(
            FormDataContent(
                Parameters.build {
                    paramsForSig.forEach { (key, value) -> append(key, value) }
                    append("api_sig", apiSig)
                    append("format", format)
                },
            ),
        )
    }

    /**
     * The two halves of the desktop-style authorization flow
     * (`auth.getToken` → the service's page → `auth.getSession`), the
     * fallback for keys Last.fm will not let sign in with a password — see
     * `LastfmAuthFlow` for why that is most of them.
     *
     * Use the same signed form POST transport as BitChord and the other
     * authenticated Last.fm calls. Neither response contains a password;
     * the token only means something to the application that requested it.
     */
    suspend fun getToken() =
        lastfmCallResult {
            postAndDecode<TokenResponse>(
                method = "auth.getToken",
            )
        }

    suspend fun getSession(token: String) =
        lastfmCallResult {
            decodeAuthentication(
                postAndRead(
                    method = "auth.getSession",
                    extra = mapOf("token" to token),
                ),
            )
        }

    suspend fun getMobileSession(
        username: String,
        password: String,
    ) = lastfmCallResult {
        decodeAuthentication(
            postAndRead(
                method = "auth.getMobileSession",
                extra = mapOf("username" to username, "password" to password),
            ),
        )
    }

    /**
     * Decode the successful session shape without depending on whether
     * Last.fm writes `subscriber` as JSON 0 or JSON "0". That field is only
     * informational, but a type variation must not throw away a valid session
     * after the listener has already approved the application.
     */
    fun decodeAuthentication(body: String): Authentication {
        val session = json.parseToJsonElement(body).jsonObject.getValue("session").jsonObject
        return Authentication(
            Session(
                name = session.getValue("name").jsonPrimitive.content,
                key = session.getValue("key").jsonPrimitive.content,
                subscriber = session["subscriber"]?.jsonPrimitive?.intOrNull ?: 0,
            ),
        )
    }

    class LastFmException(
        val code: Int,
        override val message: String,
    ) : Exception(message)

    suspend fun updateNowPlaying(
        artist: String,
        track: String,
        album: String? = null,
        trackNumber: Int? = null,
        duration: Int? = null,
    ) = lastfmCallResult {
        postAndRead(
            method = "track.updateNowPlaying",
            sessionKey = requireSessionKey(),
            extra =
                buildMap {
                    put("artist", artist)
                    put("track", track)
                    album?.let { put("album", it) }
                    trackNumber?.let { put("trackNumber", it.toString()) }
                    duration?.let { put("duration", it.toString()) }
                },
        )
    }

    suspend fun scrobble(
        artist: String,
        track: String,
        timestamp: Long,
        album: String? = null,
        trackNumber: Int? = null,
        duration: Int? = null,
    ) = lastfmCallResult {
        postAndRead(
            method = "track.scrobble",
            sessionKey = requireSessionKey(),
            extra =
                buildMap {
                    put("artist[0]", artist)
                    put("track[0]", track)
                    put("timestamp[0]", timestamp.toString())
                    album?.let { put("album[0]", it) }
                    trackNumber?.let { put("trackNumber[0]", it.toString()) }
                    duration?.let { put("duration[0]", it.toString()) }
                },
        )
    }

    /**
     * Points the client at an application pair without touching the endpoint
     * or the session — the shape both login callers want.
     *
     * Normalized onto [configure]: one place trims, one place builds the
     * runtime config, so `initialize` and `configure` cannot drift into
     * different ideas of what a configured client is. Still refuses an
     * incomplete pair loudly rather than sending requests Last.fm would
     * answer with a signature error that reads like a wrong password — but
     * the UIs guard on [AppSettings.lastfmCredentials] first, so in practice
     * this only fires on a programming mistake.
     */
    fun initialize(
        apiKey: String,
        secret: String,
    ) {
        require(apiKey.isNotBlank() && secret.isNotBlank()) {
            NOT_CONFIGURED_MESSAGE
        }
        configure(
            endpoint = runtimeConfig.endpoint,
            apiKey = apiKey,
            secret = secret,
            sessionKey = runtimeConfig.sessionKey,
        )
    }

    fun configure(
        endpoint: String,
        apiKey: String,
        secret: String,
        sessionKey: String? = runtimeConfig.sessionKey,
    ) {
        runtimeConfig =
            RuntimeConfig(
                endpoint = normalizeEndpoint(endpoint),
                apiKey = apiKey.trim(),
                secret = secret.trim(),
                sessionKey = sessionKey?.trim()?.takeIf { it.isNotEmpty() },
            )
    }

    fun currentConfig(): RuntimeConfig = runtimeConfig

    fun isInitialized(): Boolean = runtimeConfig.apiKey.isNotBlank() && runtimeConfig.secret.isNotBlank()

    fun normalizeEndpoint(endpoint: String): String {
        val uri = URI(endpoint.trim())
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https")
        require(!uri.host.isNullOrBlank())
        require(uri.query == null && uri.fragment == null)

        val path =
            uri.path
                ?.takeIf { it.isNotBlank() && it != "/" }
                ?.trimEnd('/')
                ?: "/2.0"

        return URI(
            scheme,
            uri.userInfo,
            uri.host,
            uri.port,
            "$path/",
            null,
            null,
        ).toString()
    }

    private fun requireSessionKey(): String = runtimeConfig.sessionKey ?: throw LastFmException(9, "Session key missing")

    private suspend inline fun <reified T> postAndDecode(
        method: String,
        sessionKey: String? = null,
        extra: Map<String, String> = emptyMap(),
    ): T =
        json.decodeFromString(
            postAndRead(
                method = method,
                sessionKey = sessionKey,
                extra = extra,
            ),
        )

    private suspend fun postAndRead(
        method: String,
        sessionKey: String? = null,
        extra: Map<String, String> = emptyMap(),
    ): String {
        val config = runtimeConfig
        // Never reach the network without a complete application pair. An
        // unconfigured call would be signed with an empty secret and answered
        // as an authentication failure — indistinguishable, in the UI, from a
        // wrong account password, which is the exact confusion this guard
        // exists to prevent. Every caller uses lastfmCallResult, so this
        // surfaces as an ordinary failure result without swallowing cancellation.
        if (config.apiKey.isBlank() || config.secret.isBlank()) {
            throw LastFmException(ERROR_NOT_CONFIGURED, NOT_CONFIGURED_MESSAGE)
        }
        val response =
            client.post(config.endpoint) {
                lastfmParams(
                    method = method,
                    apiKey = config.apiKey,
                    secret = config.secret,
                    sessionKey = sessionKey,
                    extra = extra,
                )
            }

        return readErrorChecked(response)
    }

    /**
     * One reading of one Last.fm answer, shared by both transports.
     * The body's own error number is read first; the HTTP status is
     * reported only when the body carries no Last.fm error object.
     */
    private suspend fun readErrorChecked(response: HttpResponse): String {
        val responseText = response.bodyAsText()
        answerFailure(response.status.value, response.status.description, responseText)?.let { throw it }
        return responseText
    }

    /**
     * The failure one Last.fm answer means, or null when it is a usable body.
     * Pure, so the rule is testable on the JVM without a network: the body's
     * own error object wins over the HTTP status; the status is the answer
     * only when the body carries none.
     */
    fun answerFailure(
        httpStatus: Int,
        httpStatusText: String,
        body: String,
    ): LastFmException? {
        val lastfmError =
            if (body.contains("\"error\"")) {
                runCatching { json.decodeFromString<LastFmError>(body) }.getOrNull()
            } else {
                null
            }
        if (lastfmError != null) return LastFmException(lastfmError.error, lastfmError.message)
        if (httpStatus !in 200..299) return LastFmException(httpStatus, httpStatusText)
        return null
    }

    const val DEFAULT_SCROBBLE_DELAY_PERCENT = 0.5f
    const val DEFAULT_SCROBBLE_MIN_SONG_DURATION = 30
    const val DEFAULT_SCROBBLE_DELAY_SECONDS = 180
}
