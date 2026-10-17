package com.doll.bloom.auth

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.doll.bloom.R
import com.doll.bloom.data.scrobbling.LastFM
import com.doll.bloom.data.scrobbling.LastfmFailureKind
import com.doll.bloom.data.scrobbling.buildLastfmAuthorizeUrl
import com.doll.bloom.data.scrobbling.classifyLastfmFailure
import com.doll.bloom.data.scrobbling.lastfmFailureDetail
import com.doll.bloom.data.scrobbling.pollForLastfmSession
import com.doll.bloom.data.settings.AppSettings
import com.doll.bloom.ui.components.AlertAction
import com.doll.bloom.ui.components.AlertRule
import com.doll.bloom.ui.components.AlertScaffold
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where one run of the authorization sheet stands. */
private enum class LastfmAuthorizePhase {
    /** Asking Last.fm for a token; the page is not open yet. */
    STARTING,

    /** The page is open and the poll is watching the token. */
    WAITING,

    /** The poll saw the confirmation; the session is already saved. */
    AUTHORIZED,

    /** The window ran out without a confirmation. */
    TIMED_OUT,

    /** Anything else that ended the run before a session. */
    FAILED,
}

/**
 * Everything the sheet shows, in one short-lived holder — same shape as
 * `LastfmLoginUiState`, and for the same reason: it lives exactly as long as
 * the sheet is open, and nothing else may observe it.
 */
private class LastfmAuthorizeUiState {
    var phase by mutableStateOf(LastfmAuthorizePhase.STARTING)
    var authorizeUrl by mutableStateOf<String?>(null)
    var errorMessage by mutableStateOf<String?>(null)

    /** Rises on every (re)start; keys the WebView so a retry loads fresh. */
    var attempt by mutableStateOf(0)
}

/**
 * Last.fm's own authorization page, in-app — the fallback the login sheet
 * opens when a password sign-in is refused, which a web-class API key always
 * does (see `LastfmAuthFlow` for the background).
 *
 * The sheet asks Last.fm for a token, shows the service's page at
 * `…/api/auth/?api_key=…&token=…` in a WebView, and polls `auth.getSession`
 * every two seconds for up to five minutes. When the listener confirms access
 * on the page, the poll returns the session: it is stored — key, username,
 * scrobbling on — exactly like a password sign-in's, and the sheet closes
 * itself. No account password is involved anywhere in this surface.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun LastfmAuthorizeSheet(
    hazeState: HazeState,
    onDismiss: () -> Unit,
) {
    val state = remember { LastfmAuthorizeUiState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var authorizeJob by remember { mutableStateOf<Job?>(null) }

    val start: () -> Unit = {
        authorizeJob?.cancel()
        state.attempt += 1
        authorizeJob = scope.launch { runLastfmAuthorize(state, context, onDismiss) }
    }
    LaunchedEffect(Unit) { start() }
    // Leaving the sheet — scrim tap, cancel row, system back — is a decision
    // to stop: the poll must not keep running against a token nobody watches.
    DisposableEffect(Unit) {
        onDispose { authorizeJob?.cancel() }
    }

    val closing = state.phase == LastfmAuthorizePhase.AUTHORIZED
    val dismiss = {
        if (!closing) {
            authorizeJob?.cancel()
            onDismiss()
        }
    }

    AlertScaffold(hazeState = hazeState, onDismiss = dismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.lastfm_authorize_title),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.lastfm_authorize_explanation),
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                text = when (state.phase) {
                    LastfmAuthorizePhase.STARTING -> stringResource(R.string.lastfm_authorize_opening)
                    LastfmAuthorizePhase.WAITING -> stringResource(R.string.lastfm_authorize_waiting)
                    LastfmAuthorizePhase.AUTHORIZED -> stringResource(R.string.lastfm_authorize_success)
                    LastfmAuthorizePhase.TIMED_OUT -> stringResource(R.string.lastfm_authorize_timed_out)
                    LastfmAuthorizePhase.FAILED ->
                        state.errorMessage ?: stringResource(R.string.lastfm_network_error)
                },
                modifier = Modifier.padding(bottom = 10.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = when (state.phase) {
                    LastfmAuthorizePhase.AUTHORIZED -> MaterialTheme.colorScheme.primary
                    LastfmAuthorizePhase.TIMED_OUT, LastfmAuthorizePhase.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
            )
            // The page only exists while its token is being watched; between
            // runs there is nothing to show. `weight(fill = false)` yields to
            // a short screen instead of pushing the buttons off it.
            if (state.phase == LastfmAuthorizePhase.WAITING) {
                val url = state.authorizeUrl
                if (url != null) {
                    key(state.attempt) {
                        LastfmAuthorizeWebView(
                            url = url,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .heightIn(max = 340.dp)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                    }
                }
            }
        }
        AlertRule()
        if (state.phase == LastfmAuthorizePhase.TIMED_OUT || state.phase == LastfmAuthorizePhase.FAILED) {
            AlertAction(
                label = stringResource(
                    if (state.phase == LastfmAuthorizePhase.TIMED_OUT) {
                        R.string.lastfm_authorize_retry
                    } else {
                        R.string.retry
                    },
                ),
                emphasised = true,
                onClick = start,
            )
            AlertRule()
        }
        AlertAction(
            label = stringResource(R.string.cancel),
            emphasised = false,
            onClick = dismiss,
            enabled = !closing,
        )
    }
}

/**
 * One run of the flow behind [LastfmAuthorizeSheet].
 *
 * Reads the effective pair the same single way the login sheet does, points
 * [LastFM] at it, and takes it from there: token → page URL → poll. Saving
 * the session on success goes through the very same `AppSettings` setters a
 * password sign-in uses, so the stamp, the enablement and the playback
 * service's snapshot all behave identically no matter which door the session
 * came in by.
 */
private suspend fun runLastfmAuthorize(
    state: LastfmAuthorizeUiState,
    context: Context,
    onDismiss: () -> Unit,
) {
    state.phase = LastfmAuthorizePhase.STARTING
    state.authorizeUrl = null
    state.errorMessage = null

    val credentials = AppSettings.lastfmCredentials.value
    if (!credentials.isConfigured) {
        state.errorMessage = context.getString(R.string.lastfm_api_status_missing)
        state.phase = LastfmAuthorizePhase.FAILED
        return
    }
    // configure(), not initialize(): the endpoint lives in settings, and the
    // flow must use the pair's endpoint even if the runtime config still
    // holds another build's. The stored session key rides along untouched.
    LastFM.configure(
        endpoint = AppSettings.lastfmEndpoint.value.ifBlank { LastFM.DEFAULT_API_ENDPOINT },
        apiKey = credentials.apiKey,
        secret = credentials.sharedSecret,
    )

    val token =
        LastFM.getToken().getOrElse { e ->
            state.errorMessage = lastfmFlowErrorMessage(e, context)
            state.phase = LastfmAuthorizePhase.FAILED
            return
        }.token
    state.authorizeUrl =
        buildLastfmAuthorizeUrl(
            endpoint = LastFM.currentConfig().endpoint,
            apiKey = credentials.apiKey,
            token = token,
        )
    state.phase = LastfmAuthorizePhase.WAITING

    pollForLastfmSession(fetchSession = { LastFM.getSession(token) })
        .onSuccess { auth ->
            AppSettings.setLastfmSessionKey(auth.session.key)
            AppSettings.setLastfmUsername(auth.session.name)
            AppSettings.setLastfmEnabled(true)
            state.phase = LastfmAuthorizePhase.AUTHORIZED
            // A beat to read the confirmation before the sheet folds away.
            delay(900)
            onDismiss()
        }.onFailure { e ->
            if ((e as? LastFM.LastFmException)?.code == LastFM.ERROR_AUTHORIZE_TIMEOUT) {
                state.phase = LastfmAuthorizePhase.TIMED_OUT
            } else {
                state.errorMessage = lastfmFlowErrorMessage(e, context)
                state.phase = LastfmAuthorizePhase.FAILED
            }
        }
}

/**
 * Listener-facing wording for a flow failure, by kind. A password refusal
 * cannot occur here — nothing in this surface sends a password — so that
 * kind falls through to the network wording rather than lying.
 */
private fun lastfmFlowErrorMessage(
    error: Throwable,
    context: Context,
): String =
    when (classifyLastfmFailure(error)) {
        LastfmFailureKind.INVALID_CREDENTIALS -> context.getString(R.string.lastfm_invalid_api_pair)
        LastfmFailureKind.NOT_CONFIGURED -> context.getString(R.string.lastfm_api_status_missing)
        LastfmFailureKind.PASSWORD_REFUSED, LastfmFailureKind.NETWORK ->
            lastfmFailureDetail(error) ?: context.getString(R.string.lastfm_network_error)
    }

/**
 * The authorization page itself. JavaScript on because Last.fm's page is an
 * app, not a document; nothing else tuned — the listener signs in to
 * last.fm's own origin, so the WebView's ordinary cookies and storage are
 * exactly what the page expects.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LastfmAuthorizeWebView(
    url: String,
    modifier: Modifier = Modifier,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) {
        onDispose { webView?.destroy() }
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webView = this
                loadUrl(url)
            }
        },
    )
}
