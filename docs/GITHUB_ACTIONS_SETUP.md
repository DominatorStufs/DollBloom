# GitHub Actions setup for DollBloom

Configure values in the `DominatorStufs/DollBloom` repository under **Settings → Secrets and variables → Actions**. Use **Repository secrets** for credentials: the workflows reference `${{ secrets.NAME }}` and do not declare a named GitHub Environment.

## Android production signing — required on `main` and `master`

Create these four repository secrets with the exact names:

| Secret | What to store |
| --- | --- |
| `KEYSTORE_BASE64` | Base64-encoded bytes of the release keystore |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Alias from the same keystore; the DollBloom setup uses `dollbloom` |
| `KEY_PASSWORD` | Password for that alias |

For a local JKS file, run from the repository root to get its Base64 value:

```bash
base64 dollbloom-release.jks | tr -d '\n'
```

Copy the output into `KEYSTORE_BASE64`. The passwords and alias must belong to that same keystore. Keep the JKS and passwords private; do not commit them or paste them into issues/chat.

The Android workflow decodes `KEYSTORE_BASE64` into the runner's temporary directory, writes the ignored root-level `keystore.properties`, validates the store, alias, and passwords, then runs `assembleProdRelease`. `app/build.gradle.kts` reads that properties file. No local `signing/` folder or helper script is needed.

## Optional Last.fm credentials

Add these repository secrets only if you want Last.fm scrobbling configured in CI builds:

- `LASTFM_API_KEY`
- `LASTFM_SECRET`

Without them, the app can still build; Last.fm credentials remain empty.

## Pointing the Android app at your own backend

If you host your own Listen Together service, add the repository variable `LISTEN_TOGETHER_SERVER` with its public base URL, for example `https://party.example.com` (no `/healthz`). Android CI writes this into `local.properties`, and the app uses it as the default server address. If unset, the app keeps its current upstream default; that service is not DollBloom-hosted. This variable does not deploy a server by itself.

## Optional backend deployment

The backend coordinates Listen Together rooms; it does not stream or store music. Tests run on pushes to `main` or `master` without extra credentials. The checked-in deployment workflow deploys to an Oracle VM only; Render hosting is configured separately in the Render dashboard. Oracle deployment is opt-in. To enable it, configure:

**Repository variables**

- `DOLLBLOOM_BACKEND_DEPLOY` — set to the exact text `true` to enable deployment; leave unset or set it to `false` to keep deployment disabled.
- `DOLLBLOOM_HEALTH_URL` — base URL of your own deployed DollBloom backend, without `/healthz`; the workflow appends `/healthz` for its check.

**Repository secrets**

- `ORACLE_DEPLOY_KEY` — private SSH deploy key authorized on your VM.
- `ORACLE_HOST` — VM hostname or IP address; the workflow connects as `deploy`.
- `ORACLE_KNOWN_HOSTS` — verified SSH host-key line(s) for the VM. Verify the host fingerprint through a trusted channel before adding it.

Do not set the backend deploy variable unless the VM, SSH secrets, and health-check URL are ready.

## No manual setup required

The contributor workflow uses GitHub's built-in `GITHUB_TOKEN`; no extra secret is needed. Feature branches and pull requests build the Dev Debug APK. Pushes to `main` or `master` run the signed production build, so configure the Android signing secrets before pushing there.
