package com.doll.bloom

import com.doll.bloom.data.scrobbling.LASTFM_POLL_INTERVAL_MS
import com.doll.bloom.data.scrobbling.LastFM
import com.doll.bloom.data.scrobbling.LastfmCredentialSource
import com.doll.bloom.data.scrobbling.LastfmCredentials
import com.doll.bloom.data.scrobbling.LastfmFailureKind
import com.doll.bloom.data.scrobbling.buildLastfmAuthorizeUrl
import com.doll.bloom.data.scrobbling.classifyLastfmFailure
import com.doll.bloom.data.scrobbling.lastfmCallResult
import com.doll.bloom.data.scrobbling.lastfmFailureDetail
import com.doll.bloom.data.scrobbling.pollForLastfmSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The selection rules behind the Last.fm login bug: an APK whose key came from
 * one source and whose secret came from another — or whose local.properties
 * carried an empty line that hid a valid environment pair — signs every
 * request wrongly and answers the listener with what reads as "wrong
 * password". These are the rules, on the JVM, with no device and no network.
 */
class LastfmCredentialsTest {

    // ── sanitize ─────────────────────────────────────────────────────────

    @Test
    fun sanitize_trimsAndTreatsNullAsEmpty() {
        val credentials = LastfmCredentials.sanitize("  key-1  ", "\tsecret-1 ", LastfmCredentialSource.CUSTOM)
        assertTrue(credentials.isConfigured)
        assertEquals("key-1", credentials.apiKey)
        assertEquals("secret-1", credentials.sharedSecret)
        assertEquals(LastfmCredentialSource.CUSTOM, credentials.source)
        assertTrue(credentials.missingSettingNames.isEmpty())

        val absent = LastfmCredentials.sanitize(null, null, LastfmCredentialSource.APK_BUILD)
        assertSame(LastfmCredentials.NotConfigured, absent)
        assertFalse(absent.isConfigured)
        assertEquals(LastfmCredentialSource.NONE, absent.source)
    }

    @Test
    fun sanitize_partialPairIsUnconfiguredAndNamesTheMissingSetting() {
        val noSecret = LastfmCredentials.sanitize("key-only", "   ", LastfmCredentialSource.APK_BUILD)
        assertFalse(noSecret.isConfigured)
        // A partial pair never wears its source's name: nothing may mistake
        // it for that source's configured pair.
        assertEquals(LastfmCredentialSource.NONE, noSecret.source)
        assertEquals(listOf(LastfmCredentials.SETTING_SHARED_SECRET), noSecret.missingSettingNames)

        val noKey = LastfmCredentials.sanitize("", "secret-only", LastfmCredentialSource.SAVED)
        assertFalse(noKey.isConfigured)
        assertEquals(listOf(LastfmCredentials.SETTING_API_KEY), noKey.missingSettingNames)
    }

    // ── selectComplete: the build's local-vs-environment rule ───────────

    @Test
    fun selectComplete_blankLocalFallsThroughToCompleteEnvironment() {
        // local.properties carried `LASTFM_API_KEY=` (empty) beside a valid
        // environment pair — the original bug. The blank candidate is skipped
        // whole and the environment pair wins.
        val blankLocal = LastfmCredentials.sanitize("", "", LastfmCredentialSource.SAVED)
        val envPair = LastfmCredentials.sanitize("env-key", "env-secret", LastfmCredentialSource.APK_BUILD)
        assertEquals(envPair, LastfmCredentials.selectComplete(blankLocal, envPair))
    }

    @Test
    fun selectComplete_partialLocalFallsThroughToCompleteEnvironment() {
        val partialLocal = LastfmCredentials.sanitize("local-key", "", LastfmCredentialSource.SAVED)
        val envPair = LastfmCredentials.sanitize("env-key", "env-secret", LastfmCredentialSource.APK_BUILD)
        val selected = LastfmCredentials.selectComplete(partialLocal, envPair)
        assertEquals(envPair, selected)
        // The local half never rides along with the environment's half.
        assertEquals("env-key", selected?.apiKey)
        assertEquals("env-secret", selected?.sharedSecret)
    }

    @Test
    fun selectComplete_firstCompleteSourceWins() {
        val local = LastfmCredentials.sanitize("local-key", "local-secret", LastfmCredentialSource.SAVED)
        val env = LastfmCredentials.sanitize("env-key", "env-secret", LastfmCredentialSource.APK_BUILD)
        assertEquals(local, LastfmCredentials.selectComplete(local, env))
        assertEquals(env, LastfmCredentials.selectComplete(null, env))
    }

    @Test
    fun selectComplete_neverCombinesHalvesFromDifferentSources() {
        val keyOnly = LastfmCredentials.sanitize("local-key", null, LastfmCredentialSource.SAVED)
        val secretOnly = LastfmCredentials.sanitize("", "env-secret", LastfmCredentialSource.APK_BUILD)
        assertNull(LastfmCredentials.selectComplete(keyOnly, secretOnly))
        assertNull(LastfmCredentials.selectComplete(null, null))
    }

    // ── resolve: the runtime custom > APK > legacy rule ─────────────────

    @Test
    fun resolve_customPairOverridesCompleteApkPair() {
        val custom = LastfmCredentials.sanitize("custom-key", "custom-secret", LastfmCredentialSource.CUSTOM)
        val apk = LastfmCredentials.sanitize("apk-key", "apk-secret", LastfmCredentialSource.APK_BUILD)
        val saved = LastfmCredentials.sanitize("old-key", "old-secret", LastfmCredentialSource.SAVED)
        assertEquals(custom, LastfmCredentials.resolve(custom, apk, saved))
    }

    @Test
    fun resolve_partialCustomNeverOverridesCompleteApk() {
        val custom = LastfmCredentials.sanitize("custom-key", " ", LastfmCredentialSource.CUSTOM)
        val apk = LastfmCredentials.sanitize("apk-key", "apk-secret", LastfmCredentialSource.APK_BUILD)
        val resolved = LastfmCredentials.resolve(custom, apk, null)
        assertEquals(apk, resolved)
        assertEquals("apk-key", resolved.apiKey)
        assertEquals("apk-secret", resolved.sharedSecret)
    }

    @Test
    fun resolve_completeApkBeatsStaleSavedPreferences() {
        // The stale-preferences case: an old plaintext pair left in settings
        // must not shadow a build whose APK pair is complete.
        val apk = LastfmCredentials.sanitize("fresh-apk-key", "fresh-apk-secret", LastfmCredentialSource.APK_BUILD)
        val stale = LastfmCredentials.sanitize("stale-key", "stale-secret", LastfmCredentialSource.SAVED)
        assertEquals(apk, LastfmCredentials.resolve(null, apk, stale))
    }

    @Test
    fun resolve_savedPairIsTheFallbackWhenApkUnconfigured() {
        val apk = LastfmCredentials.NotConfigured
        val saved = LastfmCredentials.sanitize("saved-key", "saved-secret", LastfmCredentialSource.SAVED)
        val resolved = LastfmCredentials.resolve(null, apk, saved)
        assertEquals(saved, resolved)
        assertEquals(LastfmCredentialSource.SAVED, resolved.source)
    }

    @Test
    fun resolve_noCompleteSource_returnsUnconfiguredWithHighestPriorityDiagnostics() {
        val customPartial = LastfmCredentials.sanitize("custom-key", "", LastfmCredentialSource.CUSTOM)
        val apkPartial = LastfmCredentials.sanitize("", "apk-secret", LastfmCredentialSource.APK_BUILD)
        val resolved = LastfmCredentials.resolve(customPartial, apkPartial, null)
        assertFalse(resolved.isConfigured)
        assertEquals(LastfmCredentialSource.NONE, resolved.source)
        // The diagnostics name what the highest-priority partial source lacks…
        assertEquals(listOf(LastfmCredentials.SETTING_SHARED_SECRET), resolved.missingSettingNames)
        // …and the halves were NOT stitched into one fake pair.
        assertEquals("custom-key", resolved.apiKey)
        assertEquals("", resolved.sharedSecret)
    }

    @Test
    fun resolve_nothingAnywhere_isNotConfiguredNamingBothSettings() {
        val resolved = LastfmCredentials.resolve(null, null, null)
        assertSame(LastfmCredentials.NotConfigured, resolved)
        assertEquals(
            listOf(LastfmCredentials.SETTING_API_KEY, LastfmCredentials.SETTING_SHARED_SECRET),
            resolved.missingSettingNames,
        )
    }

    @Test
    fun isCompletePair_checksBothHalvesBeforeTrimming() {
        assertTrue(LastfmCredentials.isCompletePair(" key ", " secret"))
        assertFalse(LastfmCredentials.isCompletePair("key", null))
        assertFalse(LastfmCredentials.isCompletePair(null, "secret"))
        assertFalse(LastfmCredentials.isCompletePair("  ", "\t"))
        assertFalse(LastfmCredentials.isCompletePair("", ""))
    }

    // ── redaction ────────────────────────────────────────────────────────

    @Test
    fun toString_neverContainsEitherValue() {
        val credentials = LastfmCredentials.sanitize(
            "SUPER-SECRET-KEY-9f31",
            "SUPER-SECRET-SHARED-2c77",
            LastfmCredentialSource.CUSTOM,
        )
        val printed = credentials.toString()
        assertFalse(printed.contains("SUPER-SECRET-KEY-9f31"))
        assertFalse(printed.contains("SUPER-SECRET-SHARED-2c77"))
        assertFalse(printed.contains("SUPER-SECRET"))
        // The source is status, not a secret: it survives redaction.
        assertTrue(printed.contains("CUSTOM"))
        assertTrue(printed.contains("not set") || printed.contains("•"))
    }

    @Test
    fun redact_masksSetValuesAndNamesEmptyOnes() {
        assertEquals("(not set)", LastfmCredentials.redact(""))
        assertEquals("(not set)", LastfmCredentials.redact("   "))
        val masked = LastfmCredentials.redact("abcdef0123456789")
        assertFalse(masked.contains("abcdef"))
        assertTrue(masked.isNotEmpty())
        assertTrue(LastfmCredentials.NotConfigured.toString().contains("not set"))
    }

    // ── fingerprints: pair identity across sources ──────────────────────

    @Test
    fun fingerprint_dependsOnThePairNotTheSource() {
        val apk = LastfmCredentials.sanitize("same-key", "same-secret", LastfmCredentialSource.APK_BUILD)
        val custom = LastfmCredentials.sanitize("same-key", "same-secret", LastfmCredentialSource.CUSTOM)
        assertEquals(apk.fingerprint(), custom.fingerprint())

        val rotatedKey = LastfmCredentials.sanitize("new-key", "same-secret", LastfmCredentialSource.APK_BUILD)
        val rotatedSecret = LastfmCredentials.sanitize("same-key", "new-secret", LastfmCredentialSource.APK_BUILD)
        assertNotEquals(apk.fingerprint(), rotatedKey.fingerprint())
        assertNotEquals(apk.fingerprint(), rotatedSecret.fingerprint())
        assertNotEquals(rotatedKey.fingerprint(), rotatedSecret.fingerprint())
    }

    @Test
    fun fingerprint_doesNotContainTheValues() {
        val credentials = LastfmCredentials.sanitize("plain-key-42", "plain-secret-42", LastfmCredentialSource.CUSTOM)
        val fingerprint = credentials.fingerprint()
        assertFalse(fingerprint.contains("plain-key-42"))
        assertFalse(fingerprint.contains("plain-secret-42"))
        assertEquals(64, fingerprint.length) // SHA-256 hex
    }

    // ── session lifecycle ────────────────────────────────────────────────

    @Test
    fun session_predatingStampingIsAdoptedNotLoggedOut() {
        val effective = LastfmCredentials.sanitize("k", "s", LastfmCredentialSource.APK_BUILD)
        assertFalse(LastfmCredentials.shouldInvalidateSession(null, effective))
        assertFalse(LastfmCredentials.shouldInvalidateSession(null, LastfmCredentials.NotConfigured))
    }

    @Test
    fun session_survivesSourceChangeUnderAnIdenticalPair() {
        val apk = LastfmCredentials.sanitize("k", "s", LastfmCredentialSource.APK_BUILD)
        val stamp = apk.fingerprint()
        val samePairAsCustom = LastfmCredentials.sanitize("k", "s", LastfmCredentialSource.CUSTOM)
        assertFalse(LastfmCredentials.shouldInvalidateSession(stamp, samePairAsCustom))
    }

    @Test
    fun session_survivesRepeatedUnchangedLaunches() {
        val effective = LastfmCredentials.sanitize("k", "s", LastfmCredentialSource.APK_BUILD)
        val stamp = effective.fingerprint()
        // The stamp comparison is a pure function of stored state: launch it a
        // hundred times and it never flips to "invalidate".
        repeat(100) {
            assertFalse(LastfmCredentials.shouldInvalidateSession(stamp, effective))
        }
    }

    @Test
    fun session_isInvalidatedByARotatedPairOrAMissingPair() {
        val issued = LastfmCredentials.sanitize("old-key", "old-secret", LastfmCredentialSource.APK_BUILD)
        val stamp = issued.fingerprint()
        val rotated = LastfmCredentials.sanitize("new-key", "new-secret", LastfmCredentialSource.APK_BUILD)
        assertTrue(LastfmCredentials.shouldInvalidateSession(stamp, rotated))
        assertTrue(LastfmCredentials.shouldInvalidateSession(stamp, LastfmCredentials.NotConfigured))
        // A partial pair is not the issued pair either.
        val partial = LastfmCredentials.sanitize("old-key", "", LastfmCredentialSource.APK_BUILD)
        assertTrue(LastfmCredentials.shouldInvalidateSession(stamp, partial))
    }

    // ── LastFM: normalize initialize/configure, guard the network ───────

    @Test
    fun initialize_refusesAnIncompletePair() {
        for (pair in listOf("" to "s", "k" to "", "  " to "\t", "" to "")) {
            try {
                LastFM.initialize(pair.first, pair.second)
                fail("initialize(${pair.first}, ${pair.second}) should have been refused")
            } catch (expected: IllegalArgumentException) {
                assertEquals(LastFM.NOT_CONFIGURED_MESSAGE, expected.message)
            }
        }
    }

    @Test
    fun configure_trimsValuesAndKeepsTheEndpoint() {
        LastFM.configure(
            endpoint = LastFM.DEFAULT_API_ENDPOINT,
            apiKey = "  trimmed-key  ",
            secret = "\ttrimmed-secret\n",
            sessionKey = "  session-1  ",
        )
        val config = LastFM.currentConfig()
        assertEquals("trimmed-key", config.apiKey)
        assertEquals("trimmed-secret", config.secret)
        assertEquals("session-1", config.sessionKey)
        assertEquals(LastFM.DEFAULT_API_ENDPOINT, config.endpoint)
        assertTrue(LastFM.isInitialized())
    }

    @Test
    fun configure_blankSessionBecomesNull_andPartialPairIsNotInitialized() {
        LastFM.configure(
            endpoint = LastFM.DEFAULT_API_ENDPOINT,
            apiKey = "key-only",
            secret = " ",
            sessionKey = "   ",
        )
        assertNull(LastFM.currentConfig().sessionKey)
        assertFalse(LastFM.isInitialized())

        // initialize() on the unconfigured object still refuses, which is the
        // backstop behind the UI's guard.
        try {
            LastFM.initialize(LastFM.currentConfig().apiKey, LastFM.currentConfig().secret)
            fail("initialize should refuse the partial pair configure just stored")
        } catch (expected: IllegalArgumentException) {
            assertEquals(LastFM.NOT_CONFIGURED_MESSAGE, expected.message)
        }
    }

    @Test
    fun notConfiguredSentinel_cannotCollideWithALastfmErrorCode() {
        assertTrue(LastFM.ERROR_NOT_CONFIGURED < 0)
        assertEquals("Last.fm API credentials are not configured for this build", LastFM.NOT_CONFIGURED_MESSAGE)
        // The authorize-timeout sentinel is negative for the same reason, and
        // the two sentinels must stay distinguishable.
        assertTrue(LastFM.ERROR_AUTHORIZE_TIMEOUT < 0)
        assertNotEquals(LastFM.ERROR_NOT_CONFIGURED, LastFM.ERROR_AUTHORIZE_TIMEOUT)
        // Error 14 is Last.fm's own "token not authorized yet" — the poll's
        // keep-polling signal, not a sentinel of this app's.
        assertEquals(14, LastFM.ERROR_TOKEN_NOT_AUTHORIZED)
    }

    // ── failure classification: what a refused sign-in means ────────────

    @Test
    fun classify_passwordRefusedOnLastError4AndHttp403() {
        // Both shapes of the same refusal: Last.fm's error object when the
        // body carried one, the HTTP status when it did not.
        val errorFour = LastFM.LastFmException(4, "Authentication Failed")
        val forbidden = LastFM.LastFmException(403, "Forbidden")
        assertEquals(LastfmFailureKind.PASSWORD_REFUSED, classifyLastfmFailure(errorFour))
        assertEquals(LastfmFailureKind.PASSWORD_REFUSED, classifyLastfmFailure(forbidden))
    }

    @Test
    fun classify_mismatchedPairOnTheSignatureErrors() {
        // 10, 13 and 26 are the answers an API key signed beside a different
        // application's secret produces — a pair problem, never a password.
        for (code in listOf(10, 13, 26)) {
            assertEquals(
                "code $code must classify as a mismatched pair",
                LastfmFailureKind.INVALID_CREDENTIALS,
                classifyLastfmFailure(LastFM.LastFmException(code, "signature problem")),
            )
        }
    }

    @Test
    fun classify_notConfiguredOnTheSentinel() {
        val unconfigured = LastFM.LastFmException(LastFM.ERROR_NOT_CONFIGURED, LastFM.NOT_CONFIGURED_MESSAGE)
        assertEquals(LastfmFailureKind.NOT_CONFIGURED, classifyLastfmFailure(unconfigured))
    }

    @Test
    fun classify_networkForEverythingElse() {
        assertEquals(
            LastfmFailureKind.NETWORK,
            classifyLastfmFailure(LastFM.LastFmException(500, "Server Error")),
        )
        assertEquals(
            LastfmFailureKind.NETWORK,
            classifyLastfmFailure(LastFM.LastFmException(8, "Operation failed")),
        )
        // Anything that never became a Last.fm exception never reached
        // Last.fm, and null is nothing at all.
        assertEquals(LastfmFailureKind.NETWORK, classifyLastfmFailure(java.io.IOException("no route")))
        assertEquals(LastfmFailureKind.NETWORK, classifyLastfmFailure(null))
    }

    @Test
    fun failureDetail_showsOnlyLastfmsCodeAndWording() {
        val error = LastFM.LastFmException(13, "Invalid method signature supplied")
        assertEquals(
            "Last.fm error 13: Invalid method signature supplied",
            lastfmFailureDetail(error),
        )
    }

    @Test
    fun failureDetail_identifiesTransportExceptionsWithoutTheirMessage() {
        assertEquals(
            "Last.fm authorization failed (IOException)",
            lastfmFailureDetail(java.io.IOException("https://secret.example/path?token=hidden")),
        )
    }

    @Test
    fun authentication_acceptsNumericSubscriber() {
        val auth =
            LastFM.decodeAuthentication(
                """{"session":{"name":"listener","key":"session-key","subscriber":0}}""",
            )
        assertEquals("listener", auth.session.name)
        assertEquals("session-key", auth.session.key)
        assertEquals(0, auth.session.subscriber)
    }

    @Test
    fun authentication_acceptsStringSubscriber() {
        val auth =
            LastFM.decodeAuthentication(
                """{"session":{"name":"listener","key":"session-key","subscriber":"1"}}""",
            )
        assertEquals(1, auth.session.subscriber)
    }

    @Test
    fun callResult_rethrowsCoroutineCancellation() = runBlocking {
        val cancellation = kotlinx.coroutines.CancellationException("sheet closed")
        try {
            lastfmCallResult<Unit> { throw cancellation }
            fail("coroutine cancellation must not become a failure Result")
        } catch (actual: kotlinx.coroutines.CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test
    fun callResult_keepsOrdinaryFailuresInTheResult() = runBlocking {
        val failure = java.io.IOException("offline")
        val result = lastfmCallResult<Unit> { throw failure }
        assertSame(failure, result.exceptionOrNull())
    }

    // ── the authorization page URL ───────────────────────────────────────

    @Test
    fun authorizeUrl_pointsAtLastfmForTheDefaultEndpoint() {
        val url = buildLastfmAuthorizeUrl(LastFM.DEFAULT_API_ENDPOINT, "key-123", "token-abc")
        assertEquals("https://www.last.fm/api/auth/?api_key=key-123&token=token-abc", url)
    }

    @Test
    fun authorizeUrl_pointsAtLibreFmForTheLibreEndpoint() {
        val url = buildLastfmAuthorizeUrl(LastFM.LIBREFM_API_ENDPOINT, "key-123", "token-abc")
        assertEquals("https://libre.fm/api/auth?api_key=key-123&token=token-abc", url)
    }

    @Test
    fun authorizeUrl_treatsAnyOtherEndpointAsLastfm() {
        val url = buildLastfmAuthorizeUrl("https://example.com/2.0/", "k", "t")
        assertEquals("https://www.last.fm/api/auth/?api_key=k&token=t", url)
    }

    @Test
    fun authorizeUrl_isNormalizedAgainstTrailingSlashes() {
        // The endpoint comparison normalizes, so an unnormalized libre.fm
        // endpoint still reaches libre.fm's page rather than Last.fm's.
        val url = buildLastfmAuthorizeUrl("https://libre.fm/2.0", "key-123", "token-abc")
        assertEquals("https://libre.fm/api/auth?api_key=key-123&token=token-abc", url)
    }

    // ── the session poll: 2 s cadence, error 14 keeps polling, 5 min cap ─

    @Test
    fun poll_keepsPollingThroughError14_thenReturnsTheSession() = runBlocking {
        var clock = 0L
        val sleeps = mutableListOf<Long>()
        var calls = 0
        val auth = LastFM.Authentication(LastFM.Session(name = "listener", key = "sk-1", subscriber = 0))
        val result =
            pollForLastfmSession(
                fetchSession = {
                    calls += 1
                    if (calls < 3) {
                        Result.failure(LastFM.LastFmException(LastFM.ERROR_TOKEN_NOT_AUTHORIZED, "not authorized yet"))
                    } else {
                        Result.success(auth)
                    }
                },
                now = { clock },
                sleep = { ms -> sleeps += ms; clock += ms },
            )
        assertTrue(result.isSuccess)
        assertEquals(auth, result.getOrNull())
        assertEquals(3, calls)
        // Two refusals, two waits — at the production cadence.
        assertEquals(listOf(LASTFM_POLL_INTERVAL_MS, LASTFM_POLL_INTERVAL_MS), sleeps)
    }

    @Test
    fun poll_retriesTemporaryDnsFailure_thenReturnsTheApprovedSession() = runBlocking {
        var clock = 0L
        var calls = 0
        val auth = LastFM.Authentication(LastFM.Session(name = "listener", key = "sk-dns", subscriber = 0))
        val result =
            pollForLastfmSession(
                fetchSession = {
                    calls += 1
                    if (calls == 1) {
                        Result.failure(java.net.UnknownHostException("temporary DNS failure"))
                    } else {
                        Result.success(auth)
                    }
                },
                now = { clock },
                sleep = { clock += it },
            )
        assertEquals(auth, result.getOrNull())
        assertEquals(2, calls)
        assertEquals(LASTFM_POLL_INTERVAL_MS, clock)
    }

    @Test
    fun poll_returnsTheFirstSuccessWithoutWaiting() = runBlocking {
        val auth = LastFM.Authentication(LastFM.Session(name = "listener", key = "sk-2", subscriber = 0))
        var calls = 0
        val result =
            pollForLastfmSession(
                fetchSession = { calls += 1; Result.success(auth) },
                now = { 0L },
                sleep = { fail("a first-attempt success must not sleep") },
            )
        assertEquals(auth, result.getOrNull())
        assertEquals(1, calls)
    }

    @Test
    fun poll_failsFastOnAnyErrorThatIsNot14() = runBlocking {
        val invalidKey = LastFM.LastFmException(10, "Invalid API key")
        var calls = 0
        val result =
            pollForLastfmSession(
                fetchSession = { calls += 1; Result.failure(invalidKey) },
                now = { 0L },
                sleep = { fail("a final error must not sleep") },
            )
        assertEquals(1, calls)
        assertSame(invalidKey, result.exceptionOrNull())
    }

    @Test
    fun poll_timesOutAfterTheWindow_withTheSentinelCode() = runBlocking {
        var clock = 0L
        var calls = 0
        val result =
            pollForLastfmSession(
                fetchSession = {
                    calls += 1
                    Result.failure(LastFM.LastFmException(LastFM.ERROR_TOKEN_NOT_AUTHORIZED, "not authorized yet"))
                },
                pollIntervalMs = 2_000L,
                timeoutMs = 6_000L,
                now = { clock },
                sleep = { clock += it },
            )
        val error = result.exceptionOrNull() as LastFM.LastFmException
        assertEquals(LastFM.ERROR_AUTHORIZE_TIMEOUT, error.code)
        assertEquals(LastFM.AUTHORIZE_TIMEOUT_MESSAGE, error.message)
        // Polls at 0 s, 2 s and 4 s, then the 6 s check ends the window.
        assertEquals(4, calls)
    }

    @Test
    fun answer_error14InsideAnHttp403_isTheTokenNotAuthorizedAnswer() {
        val body = """{"error":14,"message":"Unauthorized Token - This token has not been authorized"}"""
        assertEquals(
            LastFM.ERROR_TOKEN_NOT_AUTHORIZED,
            LastFM.answerFailure(403, "Forbidden", body)?.code,
        )
    }

    @Test
    fun answer_error4InsideAnHttp403_keepsLastfmsOwnCode() {
        val body = """{"error":4,"message":"Authentication Failed - Invalid username or password"}"""
        assertEquals(4, LastFM.answerFailure(403, "Forbidden", body)?.code)
    }

    @Test
    fun answer_httpStatusIsTheAnswerOnlyWhenTheBodyCarriesNoError() {
        assertEquals(
            502,
            LastFM.answerFailure(502, "Bad Gateway", "<html>proxy error</html>")?.code,
        )
    }

    @Test
    fun answer_successBodyIsNeverAFailure_evenWhenItMentionsError() {
        val body = """{"session":{"name":"error","key":"k","subscriber":0}}"""
        assertNull(LastFM.answerFailure(200, "OK", body))
    }
}
