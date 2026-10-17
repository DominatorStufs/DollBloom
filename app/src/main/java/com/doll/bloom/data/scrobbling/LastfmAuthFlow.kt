package com.doll.bloom.data.scrobbling

import kotlinx.coroutines.delay
import java.io.IOException

/**
 * The in-app fallback for a Last.fm password sign-in that Last.fm refuses.
 *
 * Why this exists: every API key created through
 * https://www.last.fm/api/account/create is issued "web"-class, because that
 * form has no application-type selector — and a web-class key cannot use
 * `auth.getMobileSession` at all: Last.fm answers the password attempt with
 * HTTP 403, error 4, no matter that the account details are correct.
 * Mobile-class keys (the only kind a password sign-in works with) require
 * emailing partners@last.fm. So for nearly every listener the password path
 * fails for a reason that is invisible from the credentials they typed.
 *
 * The flow therefore tries the password first — the zero-tap fast path, and
 * still the whole answer for the rare mobile-class key — and only when
 * Last.fm refuses it with that specific error does the app open Last.fm's
 * own authorization page in-app: `auth.getToken`, the page, then polling
 * `auth.getSession` until the listener confirms. See `LastfmAuthorizeSheet`
 * for the surface that runs it.
 *
 * Pure JVM on purpose, like `LastfmCredentials`: the classification and the
 * polling rules are tested on the JVM in `LastfmCredentialsTest`, with no
 * device and no network.
 */

/** What a failed Last.fm sign-in attempt means, for the listener's sake. */
enum class LastfmFailureKind {
    /**
     * Error 4 or HTTP 403: Last.fm refused the password itself. For a
     * web-class key that is structural, not a typo — the answer is the
     * authorization page, not another password attempt.
     */
    PASSWORD_REFUSED,

    /**
     * Errors 10/13/26: the application pair does not hang together — an API
     * key beside a different application's shared secret signs every request
     * in a way Last.fm rejects outright. Retrying the same pair cannot help.
     */
    INVALID_CREDENTIALS,

    /** [LastFM.ERROR_NOT_CONFIGURED]: no complete pair anywhere. */
    NOT_CONFIGURED,

    /** Everything else: the network, Last.fm's servers, or the unknown. */
    NETWORK,
}

/**
 * Sorts a sign-in failure into a [LastfmFailureKind].
 *
 * The decision rides on [LastFM.LastFmException.code] alone: Last.fm's own
 * error number when the body carried one, or the HTTP status when it did not
 * — `postAndRead` raises both shapes through the same exception.
 * Anything that is not a [LastFM.LastFmException] never reached Last.fm, so
 * it is [LastfmFailureKind.NETWORK] by definition.
 */
fun classifyLastfmFailure(error: Throwable?): LastfmFailureKind {
    val code = (error as? LastFM.LastFmException)?.code ?: return LastfmFailureKind.NETWORK
    return when (code) {
        4, 403 -> LastfmFailureKind.PASSWORD_REFUSED
        10, 13, 26 -> LastfmFailureKind.INVALID_CREDENTIALS
        LastFM.ERROR_NOT_CONFIGURED -> LastfmFailureKind.NOT_CONFIGURED
        else -> LastfmFailureKind.NETWORK
    }
}

/**
 * A safe detail for an authorization failure returned by Last.fm itself.
 * It contains only Last.fm's numeric result and standard wording—never the
 * request, application secret, account password, token, or session key.
 */
fun lastfmFailureDetail(error: Throwable?): String? {
    error ?: return null
    val failure = error as? LastFM.LastFmException
    if (failure == null) {
        // The type identifies transport versus decoding failures without
        // exposing a URL, response body, token, password, or credential.
        return "Last.fm authorization failed (${error.javaClass.simpleName})"
    }
    val wording = failure.message.trim().takeIf { it.isNotEmpty() }
    return if (wording == null) {
        "Last.fm error ${failure.code}"
    } else {
        "Last.fm error ${failure.code}: $wording"
    }
}

/**
 * The URL of the application's own authorization page on the service that
 * issued the pair — the page the listener confirms access on while
 * [pollForLastfmSession] watches the token.
 *
 * Same shape the removed `LastFM.getAuthUrl` produced: libre.fm-compatible
 * endpoints authorize on libre.fm, everything else on Last.fm itself. The
 * endpoint is compared after normalization, so a trailing slash or an absent
 * path cannot send a libre.fm listener to Last.fm's page.
 */
fun buildLastfmAuthorizeUrl(
    endpoint: String,
    apiKey: String,
    token: String,
): String {
    val normalized = runCatching { LastFM.normalizeEndpoint(endpoint) }.getOrDefault(endpoint)
    val base =
        if (normalized == LastFM.LIBREFM_API_ENDPOINT) {
            "https://libre.fm/api/auth"
        } else {
            "https://www.last.fm/api/auth/"
        }
    return "$base?api_key=$apiKey&token=$token"
}

/** The poll cadence while the listener is still on the authorization page. */
const val LASTFM_POLL_INTERVAL_MS = 2_000L

/**
 * How long the token stays open. Last.fm tokens live for 60 minutes; five
 * minutes is the window this app promises in its wording, and a page left
 * open longer than that has most likely been abandoned.
 */
const val LASTFM_AUTHORIZE_TIMEOUT_MS = 5 * 60 * 1000L

/**
 * Polls `auth.getSession` for [token] until the listener confirms on the
 * authorization page, the window runs out, or Last.fm gives a final answer.
 *
 * Error 14 — "This token has not been authorized yet" — is the expected
 * answer while the page is still open, so it polls again. Temporary network
 * failures are retried too: Android may briefly lose DNS while switching
 * between DollBloom and the browser, and that must not abandon an already
 * approved token. Other Last.fm answers remain final. A window that expires
 * without a usable answer fails with [LastFM.ERROR_AUTHORIZE_TIMEOUT], a
 * sentinel the surface recognizes as "offer to open the page again".
 *
 * [now] and [sleep] are injected so the rules are testable on the JVM
 * without waiting five minutes; production takes the defaults.
 */
suspend fun pollForLastfmSession(
    fetchSession: suspend () -> Result<LastFM.Authentication>,
    pollIntervalMs: Long = LASTFM_POLL_INTERVAL_MS,
    timeoutMs: Long = LASTFM_AUTHORIZE_TIMEOUT_MS,
    now: () -> Long = { System.currentTimeMillis() },
    sleep: suspend (Long) -> Unit = { delay(it) },
): Result<LastFM.Authentication> {
    val startedAt = now()
    while (true) {
        val attempt = fetchSession()
        if (attempt.isSuccess) return attempt
        val failure = attempt.exceptionOrNull()
        val code = (failure as? LastFM.LastFmException)?.code
        val retryable = code == LastFM.ERROR_TOKEN_NOT_AUTHORIZED || failure is IOException
        if (!retryable) return attempt
        if (now() - startedAt >= timeoutMs) {
            return Result.failure(
                LastFM.LastFmException(
                    LastFM.ERROR_AUTHORIZE_TIMEOUT,
                    LastFM.AUTHORIZE_TIMEOUT_MESSAGE,
                ),
            )
        }
        sleep(pollIntervalMs)
    }
}
