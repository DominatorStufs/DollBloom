#!/usr/bin/env python3
"""Unlock DollBloom's release signing key.

The release keystore is not in this repository in the clear. It is stored as one
AES-256-CBC blob — ``dollbloom-release.keystore.enc`` — whose key is derived from
a passphrase with PBKDF2-HMAC-SHA256. Nothing here can sign anything until that
passphrase is supplied, which is the point: the key material is only ever on disk
in the clear for as long as a build is running, and only for the build that
asked for it.

    python3 signing/dollbloom_signing.py info
    python3 signing/dollbloom_signing.py verify
    python3 signing/dollbloom_signing.py unseal --out-dir .
    python3 signing/dollbloom_signing.py rotate
    python3 signing/dollbloom_signing.py init --force

``unseal`` is what the release workflow runs. It writes ``dollbloom-release.jks``
and ``keystore.properties`` into the project root, which is exactly the input
``app/build.gradle.kts`` already knows how to read, so nothing about the Gradle
side is special-cased for this scheme.

Verification is deliberately independent of decryption. A wrong passphrase is
rejected by an HMAC over a fixed string before any ciphertext is touched, so
``verify`` can answer "is this the right passphrase?" without the answer
depending on how the keystore was packaged.
"""

from __future__ import annotations

import argparse
import base64
import getpass
import hashlib
import hmac
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import textwrap
from datetime import datetime, timezone
from pathlib import Path

# --- The scheme's fixed parameters -------------------------------------------
#
# These are the values the Android side reproduces byte for byte in
# app/src/main/java/com/doll/bloom/signing/SigningKey.kt, so that the passphrase
# the app checks is the same passphrase this tool checks. Changing any of them
# means changing both places, and rotating the key.

SCHEME = 1
KDF = "pbkdf2-hmac-sha256"
ITERATIONS = 600_000
KEY_LENGTH = 32
VERIFIER_MESSAGE = b"DollBloom signing key v1"
VERIFIER_CONTEXT = "DollBloom signing key v1"

KEY_ALIAS = "dollbloom"
KEYSTORE_TYPE = "PKCS12"
JKS_TYPE = "JKS"

CERT_CN = "DollBloom"
CERT_ORG = "DollBloom Music"
CERT_DAYS = 10950  # ~30 years: an app signing key should outlive the app.
KEY_BITS = 4096

HERE = Path(__file__).resolve().parent
DEFAULT_REPO_ROOT = HERE.parent

CHECK_FILE = "keycheck.json"
KEYSTORE_BLOB = "dollbloom-release.keystore.enc"
CERT_FILE = "dollbloom-cert.pem"


class SigningError(Exception):
    """Raised for anything the person at the keyboard can fix."""


def say(message: str = "") -> None:
    print(message, flush=True)


# --- KDF ---------------------------------------------------------------------


def salt_from(check: dict) -> bytes:
    return base64.b64decode(check["kdf"]["salt"])


def derive_kek(passphrase: str, check: dict) -> bytes:
    """PBKDF2-HMAC-SHA256 over the passphrase. Same call the app makes."""
    return hashlib.pbkdf2_hmac(
        "sha256",
        passphrase.encode("utf-8"),
        salt_from(check),
        int(check["kdf"]["iterations"]),
        dklen=KEY_LENGTH,
    )


def verifier_for(kek: bytes) -> bytes:
    return hmac.new(kek, VERIFIER_MESSAGE, hashlib.sha256).digest()


def check_passphrase(passphrase: str, check: dict) -> bytes:
    """Return the derived key, or exit if the passphrase is not this key's."""
    kek = derive_kek(passphrase, check)
    expected = base64.b64decode(check["verifier"])
    if not hmac.compare_digest(verifier_for(kek), expected):
        raise SigningError(
            "that passphrase does not unlock this key. "
            "Use 'info' to see which key is stored here, or 'init --force' to "
            "generate a new one."
        )
    return kek


# --- openssl plumbing --------------------------------------------------------


def openssl(args: list[str], passphrase: str, stdin: bytes | None = None) -> bytes:
    """Run openssl with the passphrase in the environment, never in argv."""
    if shutil.which("openssl") is None:
        raise SigningError("openssl is required but was not found on PATH")
    env = dict(os.environ, DOLLBLOOM_OPENSSL_PASS=passphrase)
    result = subprocess.run(
        args,
        input=stdin,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
        check=False,
    )
    if result.returncode != 0:
        raise SigningError(
            f"openssl failed ({' '.join(args)}): "
            f"{result.stderr.decode('utf-8', 'replace').strip()}"
        )
    return result.stdout


def encrypt_bytes(plain: bytes, passphrase: str) -> bytes:
    return openssl(
        [
            "openssl", "enc", "-aes-256-cbc", "-salt",
            "-pbkdf2", "-iter", str(ITERATIONS),
            "-pass", "env:DOLLBLOOM_OPENSSL_PASS",
        ],
        passphrase,
        stdin=plain,
    )


def decrypt_bytes(cipher: bytes, passphrase: str) -> bytes:
    return openssl(
        [
            "openssl", "enc", "-d", "-aes-256-cbc",
            "-pbkdf2", "-iter", str(ITERATIONS),
            "-pass", "env:DOLLBLOOM_OPENSSL_PASS",
        ],
        passphrase,
        stdin=cipher,
    )


def wrap_blob(cipher: bytes) -> str:
    """Base64, wrapped, with a header so a human opening the file knows what it is."""
    body = base64.b64encode(cipher).decode("ascii")
    wrapped = "\n".join(textwrap.wrap(body, 76))
    return (
        "# DollBloom release signing keystore.\n"
        "# AES-256-CBC, PBKDF2-HMAC-SHA256 (600000 iterations).\n"
        "# Encrypted. Useless without the passphrase; do not try to read it.\n"
        f"{wrapped}\n"
    )


def unwrap_blob(text: str) -> bytes:
    body = "\n".join(line for line in text.splitlines() if not line.startswith("#"))
    try:
        return base64.b64decode("".join(body.split()))
    except Exception as exc:  # noqa: BLE001 - reported, not raised
        raise SigningError(f"keystore blob is not valid base64: {exc}") from exc


# --- key material ------------------------------------------------------------


def openssl_run(args: list[str], env: dict | None = None) -> None:
    result = subprocess.run(args, env=env, capture_output=True, check=False, text=True)
    if result.returncode != 0:
        raise SigningError(
            f"failed: {' '.join(args)}\n{result.stderr.strip() or result.stdout.strip()}"
        )


def generate_keystore(passphrase: str, directory: Path) -> Path:
    """Generate a fresh self-signed 4096-bit RSA signing key as a PKCS#12 store."""
    key = directory / "dollbloom.key"
    crt = directory / "dollbloom.crt"
    p12 = directory / "dollbloom-release.p12"
    env = dict(os.environ, DOLLBLOOM_OPENSSL_PASS=passphrase)

    openssl_run([
        "openssl", "genpkey", "-algorithm", "RSA",
        "-pkeyopt", f"rsa_keygen_bits:{KEY_BITS}", "-out", str(key),
    ])
    openssl_run([
        "openssl", "req", "-new", "-x509", "-key", str(key), "-out", str(crt),
        "-days", str(CERT_DAYS), "-sha256",
        "-subj", f"/CN={CERT_CN}/O={CERT_ORG}",
    ])
    openssl_run([
        "openssl", "pkcs12", "-export",
        "-inkey", str(key), "-in", str(crt), "-name", KEY_ALIAS,
        "-out", str(p12), "-passout", "env:DOLLBLOOM_OPENSSL_PASS",
    ], env=env)
    return p12


def jks_from_pkcs12(p12: Path, jks: Path, passphrase: str, alias: str = KEY_ALIAS) -> None:
    """Convert PKCS#12 to a JKS store, which AGP reads without any doubt.

    Also serves as a real integrity check: keytool has to decrypt the store and
    the private key inside it, so a truncated or corrupt blob fails here rather
    than halfway through a release build.
    """
    if shutil.which("keytool") is None:
        raise SigningError(
            "keytool is required to build the JKS store but was not found on PATH. "
            "Install a JDK (keytool ships with it)."
        )
    result = subprocess.run(
        [
            "keytool", "-importkeystore",
            "-srckeystore", str(p12), "-srcstoretype", "PKCS12",
            "-srcstorepass", passphrase, "-srcalias", alias,
            "-destkeystore", str(jks), "-deststoretype", JKS_TYPE,
            "-deststorepass", passphrase, "-destkeypass", passphrase,
            "-destalias", alias, "-noprompt",
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise SigningError(
            "keytool could not read the keystore, so the release build would not "
            f"have been signed correctly:\n{result.stderr.strip()}"
        )


def cert_fingerprint(cert_pem: Path) -> str:
    """SHA-256 of the certificate DER, as uppercase colon-separated hex."""
    der = openssl_run_capture([
        "openssl", "x509", "-in", str(cert_pem), "-outform", "DER",
    ])
    digest = hashlib.sha256(der).hexdigest().upper()
    return ":".join(digest[i:i + 2] for i in range(0, len(digest), 2))


def openssl_run_capture(args: list[str]) -> bytes:
    result = subprocess.run(args, capture_output=True, check=False)
    if result.returncode != 0:
        raise SigningError(
            f"openssl failed: {' '.join(args)}: "
            f"{result.stderr.decode('utf-8', 'replace').strip()}"
        )
    return result.stdout


def cert_details(cert_pem: Path) -> dict[str, str]:
    text = openssl_run_capture([
        "openssl", "x509", "-in", str(cert_pem), "-noout", "-subject", "-issuer", "-dates", "-serial",
    ]).decode("utf-8", "replace")
    details: dict[str, str] = {}
    for line in text.splitlines():
        key, _, value = line.partition("=")
        details[key.strip().replace(" ", "").lower()] = value.strip()
    return details


# --- repo state --------------------------------------------------------------


def load_check(repo_root: Path) -> dict:
    path = repo_root / "signing" / CHECK_FILE
    if not path.is_file():
        raise SigningError(
            f"{path} is missing. This repository has no stored signing key; "
            "run 'init' to generate one."
        )
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def save_check(repo_root: Path, check: dict) -> None:
    path = repo_root / "signing" / CHECK_FILE
    path.write_text(json.dumps(check, indent=2) + "\n", encoding="utf-8")


def read_blob(repo_root: Path) -> bytes:
    path = repo_root / "signing" / KEYSTORE_BLOB
    if not path.is_file():
        raise SigningError(f"{path} is missing")
    return unwrap_blob(path.read_text(encoding="utf-8"))


def resolve_passphrase(args: argparse.Namespace) -> str:
    """Passphrase from an environment variable, else an interactive prompt."""
    name = getattr(args, "passphrase_env", None)
    if name:
        value = os.environ.get(name)
        if not value:
            raise SigningError(
                f"environment variable {name} is empty, so there is no passphrase "
                "to unlock the signing key with"
            )
        return value
    if not sys.stdin.isatty():
        raise SigningError(
            "no passphrase given. Pass --passphrase-env NAME, or run this "
            "interactively so it can be typed."
        )
    say("DollBloom release signing key")
    return getpass.getpass("Signing passphrase: ")


# --- commands ----------------------------------------------------------------


def cmd_info(args: argparse.Namespace) -> int:
    repo_root = Path(args.repo_root).resolve()
    check = load_check(repo_root)
    cert = repo_root / "signing" / check["certificate"]
    say("DollBloom release signing key")
    say(f"  keystore        signing/{check['keystore_blob']}  (encrypted)")
    say(f"  keystore type   {check['keystore_type']} -> {JKS_TYPE} at unseal time")
    say(f"  key alias       {check['key_alias']}")
    say(f"  kdf             {check['kdf']['algorithm']}, "
        f"{check['kdf']['iterations']} iterations")
    say(f"  fingerprint     {check.get('certificate_sha256', 'unknown')}")
    if cert.is_file():
        details = cert_details(cert)
        say(f"  subject         {details.get('subject', '?')}")
        say(f"  valid           {details.get('notbefore', '?')} .. {details.get('notafter', '?')}")
    say(f"  hint            {check.get('hint', 'none recorded')}")
    say()
    say("The key itself is encrypted. Without the passphrase nothing here can sign.")
    return 0


def cmd_verify(args: argparse.Namespace) -> int:
    repo_root = Path(args.repo_root).resolve()
    check = load_check(repo_root)
    passphrase = resolve_passphrase(args)
    kek = check_passphrase(passphrase, check)
    say("Passphrase accepted. This is the key for:")
    cert = repo_root / "signing" / check["certificate"]
    if cert.is_file():
        details = cert_details(cert)
        say(f"  {details.get('subject', 'DollBloom')}")
        say(f"  SHA-256 {check.get('certificate_sha256', '?')}")
    if args.show_kdf:
        say(f"  derived key (PBKDF2) {kek.hex()[:16]}... (first 8 bytes, shown on request only)")
    return 0


def cmd_unseal(args: argparse.Namespace) -> int:
    """Decrypt the keystore and write the files Gradle signs with."""
    repo_root = Path(args.repo_root).resolve()
    out_dir = Path(args.out_dir).resolve()
    check = load_check(repo_root)
    passphrase = resolve_passphrase(args)
    check_passphrase(passphrase, check)

    cipher = read_blob(repo_root)
    with tempfile.TemporaryDirectory() as tmp:
        p12 = Path(tmp) / "dollbloom-release.p12"
        p12.write_bytes(decrypt_bytes(cipher, passphrase))

        jks = out_dir / args.jks_name
        jks_from_pkcs12(p12, jks, passphrase, check["key_alias"])

    properties = out_dir / "keystore.properties"
    properties.write_text(
        "\n".join([
            "# Written by signing/dollbloom_signing.py unseal. Not committed.",
            f"storeFile={args.jks_name}",
            f"storeType={JKS_TYPE}",
            f"storePassword={passphrase}",
            f"keyAlias={check['key_alias']}",
            f"keyPassword={passphrase}",
            "",
        ]),
        encoding="utf-8",
    )
    os.chmod(properties, 0o600)
    os.chmod(out_dir / args.jks_name, 0o600)

    say(f"Unsealed {check['key_alias']} from signing/{check['keystore_blob']}")
    say(f"  {out_dir / args.jks_name}")
    say(f"  {properties}")
    say("Release builds will now be signed. Delete both files when you are done; "
        "they are gitignored, but they are still the private key.")
    return 0


def cmd_encrypt(args: argparse.Namespace) -> int:
    """Re-wrap an existing keystore with a passphrase (used by rotate/init)."""
    repo_root = Path(args.repo_root).resolve()
    source = Path(args.keystore)
    passphrase = args.new_passphrase
    if not passphrase:
        raise SigningError("--new-passphrase is required")
    blob = encrypt_bytes(source.read_bytes(), passphrase)
    (repo_root / "signing" / KEYSTORE_BLOB).write_text(wrap_blob(blob), encoding="utf-8")
    say(f"wrote signing/{KEYSTORE_BLOB} ({len(blob)} bytes of ciphertext)")
    return 0


def cmd_rotate(args: argparse.Namespace) -> int:
    """Keep the same key, change the passphrase."""
    repo_root = Path(args.repo_root).resolve()
    check = load_check(repo_root)
    old = resolve_passphrase(args)
    check_passphrase(old, check)

    new = args.new_passphrase or getpass.getpass("New signing passphrase: ")
    if len(new) < 12:
        raise SigningError("a signing passphrase should be at least 12 characters")
    if new == old:
        raise SigningError("the new passphrase is the same as the old one")

    cipher = read_blob(repo_root)
    keystore = decrypt_bytes(cipher, old)  # the PKCS#12 bytes, unchanged

    salt = os.urandom(16)
    check["kdf"]["salt"] = base64.b64encode(salt).decode("ascii")
    check["kdf"]["iterations"] = ITERATIONS
    check["verifier"] = base64.b64encode(
        verifier_for(derive_kek(new, check))
    ).decode("ascii")
    check["rotated"] = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    check["hint"] = args.hint or check.get("hint", "")

    (repo_root / "signing" / KEYSTORE_BLOB).write_text(
        wrap_blob(encrypt_bytes(keystore, new)), encoding="utf-8"
    )
    save_check(repo_root, check)
    say("Rotated. The signing key is unchanged — only the passphrase is new —")
    say("so existing installs still update cleanly.")
    say()
    say("Update the passphrase wherever the build gets it from:")
    say("  * .github/workflows/android.yml, step 'Unseal the release signing key':")
    say("    change the value of SIGNING_PASSPHRASE_BUILTIN. This is the copy the")
    say("    builds actually use.")
    say("  * If you took the password back out of the loop, the Actions secret or")
    say("    variable DOLLBLOOM_SIGNING_PASSPHRASE instead.")
    say("  * Anywhere a local build runs: export DOLLBLOOM_SIGNING_PASSPHRASE=...")
    return 0


def cmd_init(args: argparse.Namespace) -> int:
    """Generate a brand new signing key. Replaces the stored one."""
    repo_root = Path(args.repo_root).resolve()
    signing_dir = repo_root / "signing"
    signing_dir.mkdir(parents=True, exist_ok=True)

    existing = signing_dir / CHECK_FILE
    if existing.is_file() and not args.force:
        raise SigningError(
            "a signing key is already stored here. Re-running init replaces it, "
            "which means every future build installs as a *different app* on "
            "devices that already have DollBloom. Pass --force if that is what "
            "you want, or use 'rotate' to change only the passphrase."
        )

    passphrase = args.new_passphrase
    if not passphrase:
        passphrase = getpass.getpass("New signing passphrase: ")
    if len(passphrase) < 12:
        raise SigningError("a signing passphrase should be at least 12 characters")

    with tempfile.TemporaryDirectory() as tmp:
        tmp_dir = Path(tmp)
        p12 = generate_keystore(passphrase, tmp_dir)
        cert = tmp_dir / "dollbloom.crt"
        keystore_bytes = p12.read_bytes()
        (signing_dir / CERT_FILE).write_bytes(cert.read_bytes())

    salt = os.urandom(16)
    check = {
        "scheme": SCHEME,
        "kdf": {
            "algorithm": KDF,
            "iterations": ITERATIONS,
            "salt": base64.b64encode(salt).decode("ascii"),
        },
        "verifier": "",
        "keystore_blob": KEYSTORE_BLOB,
        "keystore_type": KEYSTORE_TYPE,
        "key_alias": KEY_ALIAS,
        "key_bits": KEY_BITS,
        "certificate": CERT_FILE,
        "certificate_sha256": cert_fingerprint(signing_dir / CERT_FILE),
        "created": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
        "hint": args.hint or "",
    }
    check["verifier"] = base64.b64encode(
        verifier_for(derive_kek(passphrase, check))
    ).decode("ascii")

    (signing_dir / KEYSTORE_BLOB).write_text(
        wrap_blob(encrypt_bytes(keystore_bytes, passphrase)), encoding="utf-8"
    )
    save_check(repo_root, check)

    say("Generated a new release signing key.")
    say(f"  signing/{CERT_FILE}  (public; safe to publish)")
    say(f"  signing/{KEYSTORE_BLOB}  (encrypted private key)")
    say(f"  signing/{CHECK_FILE}  (KDF parameters and passphrase verifier)")
    say(f"  certificate SHA-256  {check['certificate_sha256']}")
    say()
    say("Remember the passphrase: there is no recovery. Losing it means generating")
    say("a new key, and a new key means every install has to be replaced by hand")
    say("instead of updated in place.")
    return 0


def cmd_fingerprint(args: argparse.Namespace) -> int:
    repo_root = Path(args.repo_root).resolve()
    check = load_check(repo_root)
    cert = repo_root / "signing" / check["certificate"]
    if not cert.is_file():
        raise SigningError(f"{cert} is missing")
    say(f"subject      {cert_details(cert).get('subject', '?')}")
    say(f"SHA-256      {cert_fingerprint(cert)}")
    say(f"SHA-1        {openssl_run_capture(['openssl','x509','-in',str(cert),'-noout','-fingerprint','-sha1']).decode().strip().split('=',1)[1]}")
    return 0


# --- cli ---------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="dollbloom_signing.py",
        description="Unlock DollBloom's encrypted release signing key.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "The passphrase can also come from the environment, which is how CI\n"
            "supplies it without it ever reaching a command line:\n"
            "    DOLLBLOOM_SIGNING_PASSPHRASE=... python3 signing/dollbloom_signing.py \\\n"
            "        unseal --passphrase-env DOLLBLOOM_SIGNING_PASSPHRASE --out-dir ."
        ),
    )
    parser.add_argument(
        "--repo-root",
        default=str(DEFAULT_REPO_ROOT),
        help="repository root (default: the parent of this script)",
    )
    parser.add_argument(
        "--passphrase-env",
        metavar="NAME",
        help="read the passphrase from this environment variable instead of prompting",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("info", help="describe the stored key (no passphrase needed)").set_defaults(func=cmd_info)

    verify = sub.add_parser("verify", help="check that a passphrase unlocks the key")
    verify.add_argument("--show-kdf", action="store_true", help="also print derived-key bytes")
    verify.set_defaults(func=cmd_verify)

    unseal = sub.add_parser("unseal", help="write the JKS + keystore.properties Gradle signs with")
    unseal.add_argument("--out-dir", default=".", help="where to write them (default: cwd)")
    unseal.add_argument("--jks-name", default="dollbloom-release.jks")
    unseal.set_defaults(func=cmd_unseal)

    encrypt = sub.add_parser("encrypt", help="re-wrap an existing keystore file (low level)")
    encrypt.add_argument("--keystore", required=True, help="path to a .p12/.jks file")
    encrypt.add_argument("--new-passphrase", required=True)
    encrypt.set_defaults(func=cmd_encrypt)

    rotate = sub.add_parser("rotate", help="keep the key, change the passphrase")
    rotate.add_argument("--new-passphrase", help="omit to be prompted")
    rotate.add_argument("--hint", help="a non-secret reminder to store alongside")
    rotate.set_defaults(func=cmd_rotate)

    init = sub.add_parser("init", help="generate a new signing key")
    init.add_argument("--force", action="store_true", help="replace an existing key")
    init.add_argument("--new-passphrase", help="omit to be prompted")
    init.add_argument("--hint", help="a non-secret reminder to store alongside")
    init.set_defaults(func=cmd_init)

    sub.add_parser("fingerprint", help="print the certificate fingerprints").set_defaults(func=cmd_fingerprint)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except SigningError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\ncancelled", file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
