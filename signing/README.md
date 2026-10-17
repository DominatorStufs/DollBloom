# Release signing

Everything about how DollBloom's APKs get signed, in one place.

## What is in this directory

**The lock file is `dollbloom-release.keystore.enc` in this folder.** That single
file is the whole of the private key, locked: a 4096-bit RSA keystore under
AES-256-CBC, whose key is derived from a passphrase with PBKDF2-HMAC-SHA256 at
600,000 iterations. It is the file that gets pasted into the repository, and the
password is what opens it.

Around it are the things that are not the key:

| File | What it is | Secret? |
| --- | --- | --- |
| `dollbloom-release.keystore.enc` | **The lock file** — the private key, encrypted | Locked |
| `dollbloom_signing.py` | The tool that opens it. `info`, `verify`, `unseal`, `rotate`, `init` | No |
| `dollbloom-cert.pem` | The public certificate. What a release is signed *with* | No — the public half of the key |
| `keycheck.json` | KDF parameters, salt, and the passphrase verifier | No — public on purpose |

## Who has the password

The release workflow does. `SIGNING_PASSPHRASE_BUILTIN` in
`.github/workflows/android.yml` is the passphrase, so when the action runs it
unlocks the lock file by itself: no prompting, no secret to configure, and every
push produces a signed APK. That is what makes a build something the repository
does rather than something somebody has to remember to start.

The honest reading of that line is this, and it is worth being blunt about:
**the passphrase is public, because it is in a public file.** It keeps the key
out of casual reach — the keystore blob on its own is useless — but anyone who
can read the workflow can sign an APK that updates every installed copy. There is
no scheme where the action unlocks something by itself *and* a reader of the same
repository cannot; those are the same act. The alternatives are not more private
either: an Actions secret or a variable is reachable by exactly the same people.

If you would rather have the password be a step somebody performs, delete that
one line from the workflow. Then the passphrase has to come from a secret, a
variable, or the dispatch form — and a build from a push stops short of signing,
because nobody is there to be asked.

Priority order, highest first:

1. `DOLLBLOOM_SIGNING_PASSPHRASE` **secret** — masked everywhere, including the
   run's summary page. Set it and it overrides the built-in.
2. `DOLLBLOOM_SIGNING_PASSPHRASE` **variable** — not masked, set once.
3. the `signing_passphrase` **dispatch input** — masked in the log, visible on
   the run page.
4. `SIGNING_PASSPHRASE_BUILTIN` in the workflow — what makes it automatic.

## Why it is arranged this way

A signing key decides whether an update installs over an existing app or is
treated as a different app entirely. DollBloom is sideloaded from GitHub
Releases, so this key *is* the update channel, and it has to survive the
repository being public, CI being public, and the app being decompiled.

So:

* **The private key is never in the APK.** An APK is a zip file. A key inside one
  is a key given away, so the app carries no key material at all.
* **The private key is never in the clear in the repository.** It is one
  AES-256-CBC blob whose key is derived from the passphrase with
  PBKDF2-HMAC-SHA256 at 600,000 iterations. Without the passphrase the file is
  noise.
* **The key is unsealed for one job and nothing outlives it.** The workflow
  writes `dollbloom-release.jks` and `keystore.properties` into the checkout,
  builds with them, and the whole workspace is thrown away when the job ends.
  Nothing is cached, uploaded, or committed.
* **A passphrase can be checked without having the key.** `keycheck.json`
  holds an HMAC of a fixed string under the derived key. `verify` recomputes it
  (same PBKDF2, same salt, same iterations) and compares. Publishing a verifier
  reveals nothing usable: getting from it back to the passphrase means breaking
  PBKDF2 at 600k iterations.
* **The passphrase is in the workflow.** Deliberately, and with its price
  written down above: it is what makes a build automatic. Delete
  `SIGNING_PASSPHRASE_BUILTIN` and the lock goes back to needing a password
  from somewhere else.

### What this does and does not protect

**What it protects:** the private key against anyone who reads the repository,
anyone who opens the APK, and anyone who reads a build log. The encrypted blob is
useless on its own, and the key never exists in a form that survives a build.

**What it does not protect:** signing rights, if the passphrase is read out of the
workflow. Whoever has the passphrase — which, as it stands, is whoever can read
`.github/workflows/android.yml` — can sign an APK that every installed copy will
accept as an update. That is a real power, and it is the trade this repository has
made to have builds that happen by themselves.

Practical consequences worth knowing:

* The one thing that actually protects installs is the *code* that gets merged.
  A signed APK is only as trustworthy as the branch it was built from.
* To take the password back out of the loop, delete `SIGNING_PASSPHRASE_BUILTIN`,
  set the passphrase as an Actions **secret** (masked even on the run page), and
  keep it in a password manager.
* If the passphrase leaks, rotate it (`rotate` below) — that re-encrypts the same
  key, so installed apps still update normally. If the *key file* leaks **and**
  the passphrase with it, that is a different problem: see "Starting over".
* This is not a Play Store key. Google Play manages its own app signing key, and
  an app distributed through Play cannot be signed with this one.

## Using it

### On GitHub (how releases are actually built)

Workflow → **Android Release Build** → *Run workflow*, and fill in:

* `version_code` — optional. Above whatever is installed; leave it empty and the
  date is used.
* `version_name` — optional, up to 64 characters, letters included (`1.8-beta2`).
* `signing_passphrase` — optional; the workflow has the passphrase already.

Fill in neither and the dispatch is a push: it builds, signs, and replaces
`DollBloom-latest.apk` on the `latest` pre-release.

The workflow unseals the key, builds `prodRelease`, signs it, checks the APK is
under 100 MB, and publishes it to Releases with the tag `v<version_name>`.

Nothing has to be supplied: the workflow opens the lock file itself (see "Who has
the password" above). A **push** therefore produces a signed APK, published to a
rolling pre-release tagged `latest`:

```
https://github.com/DominatorStufs/DollBloom/releases/download/latest/DollBloom-latest.apk
```

A **dispatch** with a version filled in produces a properly versioned release,
`v<version name>`, and the only thing worth thinking about there is the version
code: give it something above the build already on the phone — today's date as
`YYYYMMDD` always is. The name may contain letters, so `1.8`, `1.8-beta2` and
`spring-release` are all fine — though a name with no number in it reads as
version 0 to the app's own update check, so leading with a number is kinder.

Neither box is required. Leave both empty and the run behaves exactly like a
push: date version code, nightly name, rolling `latest` pre-release. The two
boxes decide which kind of build this is, and nothing else does.

### On a machine, by hand

```bash
# What key is stored, with no passphrase needed
python3 signing/dollbloom_signing.py info

# Check a passphrase
DOLLBLOOM_SIGNING_PASSPHRASE='…' \
  python3 signing/dollbloom_signing.py --passphrase-env DOLLBLOOM_SIGNING_PASSPHRASE verify

# Write dollbloom-release.jks + keystore.properties, then build normally
DOLLBLOOM_SIGNING_PASSPHRASE='…' \
  python3 signing/dollbloom_signing.py --passphrase-env DOLLBLOOM_SIGNING_PASSPHRASE unseal --out-dir .
./gradlew assembleProdRelease

# Delete the unsealed copies when done — they are gitignored, not safe
rm -f dollbloom-release.jks keystore.properties
```

`unseal` needs `openssl` and `keytool` (a JDK). The Python is standard library
only.

## Changing the passphrase

```bash
DOLLBLOOM_SIGNING_PASSPHRASE='old' \
  python3 signing/dollbloom_signing.py --passphrase-env DOLLBLOOM_SIGNING_PASSPHRASE \
  rotate --new-passphrase 'new'
```

The signing key is unchanged — only the wrapping is — so existing installs still
update cleanly. The salt and verifier in `keycheck.json` change with it, and
`verify` reads them from there. Then update the copy the workflow uses (see
above), or the next build will fail at the unseal step with "that passphrase does
not unlock this key" — which is the failure working, not breaking.

## Starting over (new key)

```bash
python3 signing/dollbloom_signing.py init --force
```

Only do this if the key itself is compromised, and know what it costs: a new key
means a new certificate, so every installed copy has to be **uninstalled and
reinstalled by hand**. Android will refuse to update an app whose signature
changed. Afterwards, tell everyone to reinstall.

## Verifying a build

```bash
apksigner verify --print-certs DollBloom-prod-release-universal.apk
```

The SHA-256 it prints must equal the `certificate_sha256` in `keycheck.json`.
