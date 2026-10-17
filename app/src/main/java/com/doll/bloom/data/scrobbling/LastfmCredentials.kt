package com.doll.bloom.data.scrobbling

import java.security.MessageDigest

/**
 * Where an effective Last.fm application pair came from.
 *
 * The pair itself is the API key of a registered Last.fm application and its
 * matching shared secret — never an account password. Which source won decides
 * what the settings UI says and whether a stored session is still compatible,
 * so it travels with the values rather than being re-derived per caller.
 */
enum class LastfmCredentialSource {
    /** Values baked into this APK at build time (`BuildConfig`). */
    APK_BUILD,

    /**
     * A pair the listener deliberately saved in-app. Lives in the encrypted
     * [AuthStore][com.doll.bloom.auth.AuthStore], not in plain preferences.
     */
    CUSTOM,

    /**
     * A pair left in plain preferences by an older build. Read only as a
     * fallback and migrated into [CUSTOM] the first time this build sees a
     * complete one; see `AppSettings.reloadLastfmCredentials`.
     */
    SAVED,

    /** No source supplied a usable value. */
    NONE,
}

/**
 * One Last.fm application credential pair: the API key and its shared secret.
 *
 * A pair is the unit everywhere on purpose. Half a pair — a key from
 * `local.properties` beside a secret from the process environment, or a saved
 * key beside a new build's secret — signs every request with an `api_sig`
 * Last.fm answers as "Invalid API key" or "Invalid method signature", which
 * reads exactly like a wrong account password and is not one. So the pair is
 * always selected whole from a single source ([resolve], [selectComplete]),
 * completeness is asked of the pair ([isConfigured]) rather than of either
 * field, and the values never reach [toString] — a credential object that
 * prints itself is a credential in a logcat line, a crash report or a
 * debug build's settings dump.
 *
 * Pure JVM on purpose: the selection rules are the heart of the login bug
 * this model was written for, and they are tested on the JVM in
 * `LastfmCredentialsTest` rather than on a device.
 */
data class LastfmCredentials(
    val apiKey: String,
    val sharedSecret: String,
    val source: LastfmCredentialSource,
) {

    /**
     * Whether this is a complete pair. Both fields, non-blank, from the one
     * [source] — the only state in which Last.fm calls may be attempted.
     */
    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && sharedSecret.isNotBlank()

    /**
     * The names of the settings that are missing from this pair, for
     * diagnostics that say *what to fix* without ever saying what the other
     * field holds. Empty when [isConfigured].
     */
    val missingSettingNames: List<String>
        get() = buildList {
            if (apiKey.isBlank()) add(SETTING_API_KEY)
            if (sharedSecret.isBlank()) add(SETTING_SHARED_SECRET)
        }

    /**
     * A stable one-way digest of the pair, used to decide whether a stored
     * session was issued under these exact credentials.
     *
     * Last.fm session keys are bound to the application (key + secret) that
     * created them: a session signed in under one pair is answered with
     * "Invalid session key" under another. Comparing digests rather than the
     * values lets that check persist — in plain preferences beside the session
     * key — without adding one more plaintext credential to the file. Same
     * pair, different [source], digests equal: switching from the APK's pair
     * to an identical saved one is not a reason to log anybody out.
     */
    fun fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$FINGERPRINT_PREFIX\n$apiKey\n$sharedSecret".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
    }

    /** Redacted on purpose — see the class doc. */
    override fun toString(): String =
        "LastfmCredentials(apiKey=${redact(apiKey)}, sharedSecret=${redact(sharedSecret)}, source=$source)"

    companion object {
        /** The `local.properties` / CI-secret name of the application key. */
        const val SETTING_API_KEY = "LASTFM_API_KEY"

        /** The `local.properties` / CI-secret name of the shared secret. */
        const val SETTING_SHARED_SECRET = "LASTFM_SECRET"

        private const val FINGERPRINT_PREFIX = "dollbloom-lastfm-credentials-v1"

        /**
         * The state every source starts in: nothing anywhere, and
         * [missingSettingNames] naming both settings.
         */
        val NotConfigured = LastfmCredentials("", "", LastfmCredentialSource.NONE)

        /**
         * Normalizes one candidate source: trims both fields, treats null as
         * empty, and refuses to label a partial pair with the source's name —
         * an incomplete candidate reports [LastfmCredentialSource.NONE] so no
         * caller can mistake it for that source's configured pair, while its
         * fields stay readable for [missingSettingNames].
         */
        fun sanitize(
            apiKey: String?,
            sharedSecret: String?,
            source: LastfmCredentialSource,
        ): LastfmCredentials {
            val key = apiKey?.trim().orEmpty()
            val secret = sharedSecret?.trim().orEmpty()
            if (key.isEmpty() && secret.isEmpty()) return NotConfigured
            val complete = key.isNotEmpty() && secret.isNotEmpty()
            return LastfmCredentials(
                apiKey = key,
                sharedSecret = secret,
                source = if (complete) source else LastfmCredentialSource.NONE,
            )
        }

        /** Whether two raw values form a complete pair, before any trimming. */
        fun isCompletePair(apiKey: String?, sharedSecret: String?): Boolean =
            !apiKey.isNullOrBlank() && !sharedSecret.isNullOrBlank()

        /**
         * The first complete pair among [candidates], highest priority first,
         * or null when none is complete.
         *
         * This is the whole of the "blank local must not hide a valid ENV
         * pair" rule: a candidate that is blank *or partial* is skipped whole,
         * and fields from different candidates are never combined. Both the
         * build script (local.properties vs process environment) and the
         * runtime ([resolve]) select through this shape.
         */
        fun selectComplete(vararg candidates: LastfmCredentials?): LastfmCredentials? =
            candidates.firstOrNull { it?.isConfigured == true }

        /**
         * The effective pair for the running app, from its three sources in
         * priority order:
         *
         * 1. [custom] — deliberately saved in-app; overrides the APK so a
         *    sideloaded build with no CI credentials still works, and so a
         *    listener can rotate to their own application.
         * 2. [apk] — this build's `BuildConfig` pair. A complete one beats a
         *    stale [saved] pair: the APK was rebuilt with newer credentials
         *    for a reason.
         * 3. [saved] — the legacy plaintext pair, as a fallback only when the
         *    APK is unconfigured.
         *
         * When no source is complete, the highest-priority source that holds
         * *anything* is returned unconfigured (its [source] is
         * [LastfmCredentialSource.NONE] — see [sanitize]), so the UI can name
         * the missing settings. A partial custom key is never completed with
         * the APK's secret or vice versa.
         */
        fun resolve(
            custom: LastfmCredentials?,
            apk: LastfmCredentials?,
            saved: LastfmCredentials?,
        ): LastfmCredentials =
            selectComplete(custom, apk, saved)
                ?: listOfNotNull(custom, apk, saved)
                    .firstOrNull { it.apiKey.isNotEmpty() || it.sharedSecret.isNotEmpty() }
                ?: NotConfigured

        /**
         * Whether a stored Last.fm session must be dropped because the
         * effective pair changed under it.
         *
         * [storedStamp] is the [fingerprint] recorded when the session was
         * created, or null for a session that predates stamping — which is
         * *adopted* (false) rather than logged out, so upgrading to this build
         * does not sign everybody out on the first launch. Afterwards the rule
         * is exact: same digest keeps the session even when only the source
         * changed; a different pair — or no pair at all — invalidates it, and
         * the answer never depends on how often the check runs, so repeated
         * launches with unchanged credentials never log out repeatedly.
         */
        fun shouldInvalidateSession(storedStamp: String?, effective: LastfmCredentials): Boolean {
            if (storedStamp == null) return false
            val current = if (effective.isConfigured) effective.fingerprint() else ""
            return storedStamp != current
        }

        /**
         * A value as it may appear in a log: a fixed mask that says only
         * whether the field holds anything, and roughly how much.
         */
        fun redact(value: String): String =
            if (value.isBlank()) "(not set)" else "•".repeat(value.length.coerceAtMost(12))
    }
}
