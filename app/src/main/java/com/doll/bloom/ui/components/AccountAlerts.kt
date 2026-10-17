package com.doll.bloom.ui.components

import com.doll.bloom.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.doll.bloom.data.scrobbling.LastFM
import com.doll.bloom.data.scrobbling.LastfmCredentials
import com.doll.bloom.data.scrobbling.LastfmFailureKind
import com.doll.bloom.data.scrobbling.classifyLastfmFailure
import com.doll.bloom.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * Same UIAlertController shape as [UpdateAvailableDialog] — frosted card,
 * hairline rules, full-width stacked actions — but with a text field for the
 * one bit of input this alert needs.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun ListenBrainzTokenAlert(
    hazeState: HazeState,
    tokenInput: String,
    onTokenInputChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.listenbrainz_token),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.listenbrainz_token_description),
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            PillTextField(
                value = tokenInput,
                onValueChange = onTokenInputChange,
                placeholder = stringResource(R.string.api_token),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave() }),
            )
        }
        AlertRule()
        AlertAction(label = stringResource(R.string.save), emphasised = true, onClick = onSave)
        AlertRule()
        AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = onDismiss)
    }
}

/**
 * Input and progress of one open Last.fm sign-in surface.
 *
 * Shared by [LastfmLoginAlert] and the settings sheet's dialog so the two
 * cannot drift into different guards or different field handling. Plain
 * Compose state rather than a ViewModel: it lives exactly as long as the
 * dialog is open, and nothing else may observe it — the password above all,
 * which is never trimmed, never persisted, and cleared the moment it has
 * been used.
 */
@Stable
class LastfmLoginUiState {
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
}

/**
 * Whether account sign-in may be submitted.
 *
 * The single guard both surfaces — button *and* keyboard Done — ask. It starts
 * at the application pair the build carries: signing in without a complete
 * API key + shared secret can only produce Last.fm's signature error, which
 * reads exactly like a wrong account password and is not one.
 */
fun lastfmSignInEnabled(
    credentials: LastfmCredentials,
    loading: Boolean,
    username: String,
    password: String,
): Boolean =
    credentials.isConfigured && !loading && username.isNotBlank() && password.isNotEmpty()

/**
 * The one sign-in path, shared by both surfaces.
 *
 * The effective pair comes from the single
 * [AppSettings.lastfmCredentials] flow — never from separate key/secret
 * fields that could be observed mid-update — [LastFM] is initialized with
 * that whole pair, and Last.fm gets the username trimmed and the password
 * exactly as typed.
 *
 * The password attempt is only the first half. Nearly every self-service
 * API key is web-class, and Last.fm refuses a web-class key's password with
 * error 4 / HTTP 403 no matter that the account details are correct; when
 * that specific refusal arrives, [onPasswordRefused] opens Last.fm's own
 * authorization page instead (see `LastfmAuthFlow` / `LastfmAuthorizeSheet`).
 * The other failures are sorted by [classifyLastfmFailure] into their own
 * wording, so a mismatched key/secret pair no longer masquerades as a wrong
 * password either. On success the session key is stored (stamped with the
 * pair that created it, inside `setLastfmSessionKey`) and the password is
 * dropped from memory rather than left in a field nobody will look at again.
 */
fun lastfmSignIn(
    scope: CoroutineScope,
    state: LastfmLoginUiState,
    notConfiguredError: String,
    invalidPairError: String,
    networkError: String,
    onSignedIn: () -> Unit,
    onPasswordRefused: () -> Unit,
) {
    if (state.loading) return
    val credentials = AppSettings.lastfmCredentials.value
    if (!credentials.isConfigured) {
        state.error = notConfiguredError
        return
    }
    if (!lastfmSignInEnabled(credentials, state.loading, state.username, state.password)) {
        return
    }
    state.loading = true
    state.error = null
    scope.launch {
        try {
            LastFM.initialize(
                apiKey = credentials.apiKey,
                secret = credentials.sharedSecret,
            )
            LastFM.getMobileSession(state.username.trim(), state.password)
                .onSuccess { auth ->
                    AppSettings.setLastfmSessionKey(auth.session.key)
                    AppSettings.setLastfmUsername(auth.session.name)
                    AppSettings.setLastfmEnabled(true)
                    state.password = ""
                    state.loading = false
                    onSignedIn()
                }.onFailure { e ->
                    handleLastfmSignInFailure(
                        state = state,
                        error = e,
                        notConfiguredError = notConfiguredError,
                        invalidPairError = invalidPairError,
                        networkError = networkError,
                        onPasswordRefused = onPasswordRefused,
                    )
                }
        } catch (e: Exception) {
            state.error = networkError
            state.loading = false
        }
    }
}

/**
 * Reads a failed password attempt the way the listener should hear it.
 *
 * A refusal (error 4 / HTTP 403) is not a wrong-password message: for a
 * web-class key it is structural, and the password is cleared before handing
 * over to the authorization page so it does not sit in the field behind it.
 */
private fun handleLastfmSignInFailure(
    state: LastfmLoginUiState,
    error: Throwable,
    notConfiguredError: String,
    invalidPairError: String,
    networkError: String,
    onPasswordRefused: () -> Unit,
) {
    when (classifyLastfmFailure(error)) {
        LastfmFailureKind.PASSWORD_REFUSED -> {
            state.password = ""
            state.loading = false
            onPasswordRefused()
        }
        LastfmFailureKind.INVALID_CREDENTIALS -> {
            state.error = invalidPairError
            state.loading = false
        }
        LastfmFailureKind.NOT_CONFIGURED -> {
            state.error = notConfiguredError
            state.loading = false
        }
        LastfmFailureKind.NETWORK -> {
            state.error = networkError
            state.loading = false
        }
    }
}

/**
 * The Last.fm sign-in sheet. It asks only for the listener's Last.fm account
 * (username or email, and password).
 *
 * The application pair is a build property, never a listener's input. A build
 * that carries none says so in one red line and keeps sign-in disabled, so the
 * password question is only ever asked when it can be answered meaningfully.
 * A refused password (error 4 / HTTP 403) hands over to [LastfmAuthorizeSheet]
 * via [onPasswordRefused].
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun LastfmLoginAlert(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    onPasswordRefused: () -> Unit,
) {
    val state = remember { LastfmLoginUiState() }
    val credentials by AppSettings.lastfmCredentials.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val signIn = {
        lastfmSignIn(
            scope = scope,
            state = state,
            notConfiguredError = context.getString(R.string.lastfm_api_status_missing),
            invalidPairError = context.getString(R.string.lastfm_invalid_api_pair),
            networkError = context.getString(R.string.lastfm_network_error),
            onSignedIn = onDismiss,
            onPasswordRefused = onPasswordRefused,
        )
    }
    // A dismiss mid-sign-in would strand the coroutine's continuation writing
    // into a state nobody reads; both the scrim and the cancel row wait.
    val dismiss = { if (!state.loading) onDismiss() }
    AlertScaffold(hazeState = hazeState, onDismiss = dismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.lastfm_login),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            LastfmLoginFields(
                state = state,
                credentials = credentials,
                onSignInRequest = signIn,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
        AlertRule()
        AlertAction(
            label = if (state.loading) stringResource(R.string.signing_in) else stringResource(R.string.sign_in),
            emphasised = true,
            onClick = signIn,
            enabled = lastfmSignInEnabled(credentials, state.loading, state.username, state.password),
        )
        AlertRule()
        AlertAction(
            label = stringResource(R.string.cancel),
            emphasised = false,
            onClick = dismiss,
            enabled = !state.loading,
        )
    }
}

/**
 * The account fields both Last.fm sign-in surfaces share, so the alert here and
 * the dialog in the settings sheet present the same guards, the same masked
 * password and the same wording.
 *
 * The sheet asks only for the account. The application pair is never shown or
 * edited here: the description says what the sheet needs, and the red line
 * appears only for an unconfigured build, to explain why sign-in sits disabled.
 * Submission — button or keyboard Done — goes through [lastfmSignInEnabled].
 */
@Composable
fun LastfmLoginFields(
    state: LastfmLoginUiState,
    credentials: LastfmCredentials,
    onSignInRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSignIn = lastfmSignInEnabled(credentials, state.loading, state.username, state.password)

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = state.error ?: stringResource(R.string.lastfm_login_description),
            modifier = Modifier.padding(bottom = 10.dp),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
            color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        // Only an unconfigured build needs more than the description: the
        // listener has to learn why sign-in sits disabled. A configured
        // build says nothing about the application pair at all.
        if (!credentials.isConfigured) {
            Text(
                text = stringResource(R.string.lastfm_api_status_missing),
                modifier = Modifier.padding(bottom = 10.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }

        LastfmSectionLabel(R.string.lastfm_account_section)
        PillTextField(
            value = state.username,
            onValueChange = { state.username = it },
            placeholder = stringResource(R.string.lastfm_username_or_email),
            enabled = !state.loading,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        Spacer(Modifier.height(8.dp))
        PillTextField(
            value = state.password,
            onValueChange = { state.password = it },
            placeholder = stringResource(R.string.password),
            enabled = !state.loading,
            isPassword = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                // The keyboard's Done asks the very same guard the button
                // does; an unconfigured pair or empty input submits nothing.
                onDone = { if (canSignIn) onSignInRequest() },
            ),
        )
        Text(
            text = stringResource(R.string.lastfm_account_password_hint),
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
        )
    }
}

/** A small left-aligned heading dividing the sheet's two halves. */
@Composable
private fun LastfmSectionLabel(textRes: Int) {
    Text(
        text = stringResource(textRes),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Start,
    )
}

/**
 * Manual token entry, for when the in-app login can't run — a WebView an OEM
 * has broken, or a token lifted from a desktop client.
 *
 * [error] carries back what the verification attempt said, because a token that
 * was mistyped or has expired is indistinguishable from one that works until
 * Discord is asked about it.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun DiscordTokenAlert(
    hazeState: HazeState,
    tokenInput: String,
    onTokenInputChange: (String) -> Unit,
    error: String?,
    loading: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.discord_token),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = error ?: stringResource(R.string.discord_token_description),
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            PillTextField(
                value = tokenInput,
                onValueChange = onTokenInputChange,
                placeholder = stringResource(R.string.token),
                enabled = !loading,
                isPassword = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (tokenInput.isNotBlank()) onSave() }),
            )
        }
        AlertRule()
        AlertAction(
            label = if (loading) stringResource(R.string.checking) else stringResource(R.string.save),
            emphasised = true,
            onClick = onSave,
            enabled = !loading && tokenInput.isNotBlank(),
        )
        AlertRule()
        AlertAction(
            label = stringResource(R.string.cancel),
            emphasised = false,
            onClick = onDismiss,
            enabled = !loading,
        )
    }
}

/**
 * One free-text presence field — an activity name, a button label.
 *
 * [message] is where the caller explains the field, including which `{...}`
 * variables it accepts, since that is the only place a user would find out.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun TextValueAlert(
    hazeState: HazeState,
    title: String,
    message: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    /** False greys Save out — for a field that isn't worth saving empty. */
    saveEnabled: Boolean = true,
    /** A third action between Save and Cancel, for a value that can be cleared. */
    onRemove: (() -> Unit)? = null,
) {
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            PillTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = placeholder,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave() }),
            )
        }
        AlertRule()
        AlertAction(
            label = stringResource(R.string.save),
            emphasised = true,
            onClick = onSave,
            enabled = saveEnabled,
        )
        if (onRemove != null) {
            AlertRule()
            AlertAction(label = stringResource(R.string.remove), emphasised = false, onClick = onRemove)
        }
        AlertRule()
        AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = onDismiss)
    }
}

/**
 * One field of a server editor: the value, its change handler, the hint, and
 * how the keyboard behaves on it. The last field always gets Done-to-save
 * while the rest advance with Next, which is what every editor below already
 * did by hand.
 */
data class EditorField(
    val value: String,
    val onChange: (String) -> Unit,
    val placeholder: String,
    val keyboardType: KeyboardType = KeyboardType.Text,
    val isPassword: Boolean = false,
)

/**
 * The shape every server editor in this app shares: a centered title, the
 * last test result in place of the description, the fields, then Test above
 * Save above Cancel — plus an optional destructive row between Save and
 * Cancel for an entry that can also be removed.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun ServerEditorAlert(
    hazeState: HazeState,
    title: String,
    description: String,
    fields: List<EditorField>,
    /** What the last test said, or null before one has been run. */
    status: String?,
    statusIsGood: Boolean,
    testing: Boolean,
    /** Whether there is enough typed in to be worth testing or saving. */
    canSubmit: Boolean,
    onTest: () -> Unit,
    onSave: () -> Unit,
    removeLabel: String? = null,
    /** Offered only when there is something stored to remove. */
    onRemove: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    AlertScaffold(hazeState = hazeState, onDismiss = { if (!testing) onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = status ?: description,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = when {
                    status == null -> MaterialTheme.colorScheme.onSurface
                    statusIsGood -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.error
                },
                textAlign = TextAlign.Center,
            )
            fields.forEachIndexed { index, field ->
                if (index > 0) Spacer(Modifier.height(8.dp))
                PillTextField(
                    value = field.value,
                    onValueChange = field.onChange,
                    placeholder = field.placeholder,
                    enabled = !testing,
                    isPassword = field.isPassword,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = field.keyboardType,
                        imeAction = if (index == fields.lastIndex) ImeAction.Done else ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (canSubmit && !testing) onSave() },
                    ),
                )
            }
        }
        AlertRule()
        // Above Save rather than beside it: an address is worth checking before
        // it is stored, and a row of three cramped buttons is what the Material
        // dialog did badly.
        AlertAction(
            label = if (testing) stringResource(R.string.testing) else stringResource(R.string.test),
            emphasised = false,
            onClick = onTest,
            enabled = canSubmit && !testing,
        )
        AlertRule()
        AlertAction(
            label = stringResource(R.string.save),
            emphasised = true,
            onClick = onSave,
            enabled = canSubmit && !testing,
        )
        if (onRemove != null && removeLabel != null) {
            AlertRule()
            AlertAction(
                label = removeLabel,
                emphasised = false,
                destructive = true,
                onClick = onRemove,
                enabled = !testing,
            )
        }
        AlertRule()
        AlertAction(
            label = stringResource(R.string.cancel),
            emphasised = false,
            onClick = onDismiss,
            enabled = !testing,
        )
    }
}

/**
 * One field of a hosted server editor: the saved value it opens with, the
 * hint, and how the keyboard behaves on it.
 */
data class FieldConfig(
    val initial: String,
    val placeholder: String,
    val keyboardType: KeyboardType = KeyboardType.Text,
    val isPassword: Boolean = false,
)

/**
 * A server editor that owns its own state: field values, the last test
 * result and the testing flag. The caller hands over fixed field configs
 * plus what test and save mean for those values, and gets back the same
 * frosted card as [ServerEditorAlert] — without the dozen remembered states
 * each host otherwise repeats.
 *
 * The configs are fixed per call site, so remembering once per opening is
 * sound; the host leaves composition on dismiss, which is when the next
 * opening starts over from the saved values.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun ServerEditorHost(
    hazeState: HazeState,
    title: String,
    description: String,
    fields: List<FieldConfig>,
    canSubmit: (List<String>) -> Boolean,
    testFailedRes: Int,
    onTest: suspend (List<String>) -> Result<Unit>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val states = remember { fields.map { mutableStateOf(it.initial) } }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsGood by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    val values = states.map { it.value }
    ServerEditorAlert(
        hazeState = hazeState,
        title = title,
        description = description,
        fields = fields.mapIndexed { index, field ->
            EditorField(
                value = states[index].value,
                onChange = { states[index].value = it; status = null },
                placeholder = field.placeholder,
                keyboardType = field.keyboardType,
                isPassword = field.isPassword,
            )
        },
        status = status,
        statusIsGood = statusIsGood,
        testing = testing,
        canSubmit = canSubmit(values),
        onTest = {
            testing = true
            status = null
            scope.launch {
                onTest(values).fold(
                    onSuccess = {
                        status = context.getString(R.string.connected)
                        statusIsGood = true
                    },
                    onFailure = {
                        status = context.getString(
                            testFailedRes,
                            it.message ?: context.getString(R.string.failed),
                        )
                        statusIsGood = false
                    },
                )
                testing = false
            }
        },
        onSave = { onSave(values) },
        onDismiss = onDismiss,
    )
}

/**
 * Add or edit a source that has an address — the addon editor.
 *
 * The same frosted card every other alert in this app uses, rather than the
 * Material `AlertDialog` this replaced. That one put a filled `OutlinedTextField`
 * and a row of cramped text buttons in the middle of a screen where nothing
 * else looks like that, and it read as a stock widget dropped into somebody
 * else's design.
 *
 * One field and up to four stacked actions. There is deliberately no name
 * field: an addon states its own name in its manifest, so asking the user to
 * invent one is asking for information the addon is about to supply anyway —
 * and a blank field would leave the row showing a bare hostname next to a
 * perfectly good published name.
 *
 * [status] is the one thing here that no other alert in the file needs:
 * testing an address has *three* outcomes rather than the usual two, and "it
 * answered, but not with something this app can use" is the one worth reading
 * — so a result replaces the description in place, coloured by [statusIsGood],
 * the way [LastfmLoginAlert] surfaces a failed sign-in.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun AddonEditorAlert(
    hazeState: HazeState,
    title: String,
    description: String,
    urlValue: String,
    onUrlChange: (String) -> Unit,
    urlPlaceholder: String,
    /** What the last test said, or null before one has been run. */
    status: String?,
    statusIsGood: Boolean,
    testing: Boolean,
    /** Whether there is enough typed in to be worth testing or saving. */
    canSubmit: Boolean,
    onTest: () -> Unit,
    onSave: () -> Unit,
    /** Offered only for a source already stored — there is nothing to remove otherwise. */
    onRemove: (() -> Unit)?,
    /**
     * What [onRemove] is called, for the callers that are not removing a source.
     *
     * The party server's address wears this same card — one field, an address
     * to test, four stacked actions — and "Remove source" would be the one line
     * on it still talking about addons.
     */
    removeLabel: String? = null,
    onDismiss: () -> Unit,
) {
    ServerEditorAlert(
        hazeState = hazeState,
        title = title,
        description = description,
        fields = listOf(
            EditorField(
                value = urlValue,
                onChange = onUrlChange,
                placeholder = urlPlaceholder,
                keyboardType = KeyboardType.Uri,
            ),
        ),
        status = status,
        statusIsGood = statusIsGood,
        testing = testing,
        canSubmit = canSubmit,
        onTest = onTest,
        onSave = onSave,
        removeLabel = removeLabel ?: stringResource(R.string.remove_source),
        onRemove = onRemove,
        onDismiss = onDismiss,
    )
}

/**
 * A name clash mid-upload: the server already holds a file under the name a
 * track would land as.
 *
 * Three stacked outcomes rather than a yes/no — overwriting destroys the
 * server copy, so the non-destructive answer leads as the emphasised action.
 * [showApplyToAll] is only true mid-batch, where one answer can carry every
 * clash still queued behind this one.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun WebDavConflictAlert(
    hazeState: HazeState,
    fileName: String,
    showApplyToAll: Boolean,
    applyToAll: Boolean,
    onApplyToAllChange: (Boolean) -> Unit,
    onOverwrite: () -> Unit,
    onKeepBoth: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.webdav_conflict_title, fileName),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.webdav_conflict_message),
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
        AlertRule()
        AlertAction(
            label = stringResource(R.string.webdav_keep_both),
            emphasised = true,
            onClick = onKeepBoth,
        )
        AlertRule()
        AlertAction(
            label = stringResource(R.string.webdav_overwrite),
            emphasised = false,
            onClick = onOverwrite,
        )
        AlertRule()
        AlertAction(
            label = stringResource(R.string.webdav_skip),
            emphasised = false,
            onClick = onSkip,
        )
        if (showApplyToAll) {
            AlertRule()
            ChoiceRow(
                label = stringResource(R.string.webdav_apply_to_all),
                detail = null,
                checked = applyToAll,
                onClick = { onApplyToAllChange(!applyToAll) },
            )
        }
    }
}

/**
 * A two-action confirmation using the same frosted UIAlertController shell as
 * the addon editor. Kept here so warnings opened from settings do not fall back
 * to a visually unrelated Material dialog.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun ConfirmationAlert(
    hazeState: HazeState,
    title: String,
    description: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = description,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
        AlertRule()
        AlertAction(label = confirmLabel, emphasised = true, onClick = onConfirm)
        AlertRule()
        AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = onDismiss)
    }
}

/**
 * Single-select list, ticked like [LyricsSourcesDialog] rather than with radio
 * buttons — same reasoning: a column of Material radios would be the one
 * Material thing left on an otherwise Apple-shaped alert.
 *
 * Picking commits immediately and closes, so there is no Save action to reach
 * for; Cancel is the only one, and it's the dismiss.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun <T> ChoiceAlert(
    hazeState: HazeState,
    title: String,
    message: String?,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    detail: (T) -> String? = { null },
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            if (message != null) {
                Text(
                    text = message,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
        }
        options.forEach { option ->
            AlertRule()
            ChoiceRow(
                label = label(option),
                detail = detail(option),
                checked = option == selected,
                onClick = { onSelect(option) },
            )
        }
        AlertRule()
        AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = onDismiss)
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    detail: String?,
    checked: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ACTION_HEIGHT)
            // iOS washes the whole row instead of drawing a ripple inside it.
            .background(
                if (pressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f) else Color.Transparent,
            )
            .clickable(
                indication = null,
                interactionSource = interactionSource,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 15.sp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        if (checked) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = stringResource(R.string.selected),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

/** The scrim + frosted card frame shared by every UIAlertController-style dialog. */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun AlertScaffold(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(ALERT_CORNER)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SCRIM_COLOR)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(ALERT_WIDTH)
                .clip(shape)
                .then(
                    if (reduceDynamicBlur) {
                        Modifier.background(MaterialTheme.colorScheme.surface)
                    } else {
                        Modifier.optimizedHazeEffect(
                            state = hazeState,
                            style = HazeMaterials.regular(MaterialTheme.colorScheme.surface),
                        )
                    },
                )
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                ),
            content = content,
        )
    }
}
