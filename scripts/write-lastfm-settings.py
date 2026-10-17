#!/usr/bin/env python3
"""Write the build's Last.fm (and Listen Together) settings into local.properties.

Run by the Android workflow before Gradle starts. `app/build.gradle.kts` reads
`local.properties` first and the process environment second, and in both cases
only ever accepts a COMPLETE pair — LASTFM_API_KEY and LASTFM_SECRET together.
This script is the CI half of that rule:

  * It reads the pair from the *repository secrets* the workflow hands it as
    environment variables. Values are never printed, echoed, or written
    anywhere except the properties file itself — every diagnostic names
    settings, not contents.
  * A complete pair is escaped for the Java Properties format and written.
  * A partial pair (one half set, one empty) is rejected whole rather than
    written: an empty `LASTFM_API_KEY=` line in local.properties would
    otherwise *hide* a valid environment value from the Gradle fallback, which
    is the exact bug that shipped an APK saying "Last.fm API credentials are
    not configured for this build".
  * Both halves missing is supported: the build proceeds, and the app offers
    in-app Last.fm API setup. Set the repository variable LASTFM_REQUIRED=true
    to make a missing pair fail the run instead (opt-in; the default stays a
    warning).
  * If the pair is missing from the secrets but present in the repository
    *Variables* (checked as booleans only, never printed), the run says so:
    secrets and variables are adjacent tabs in the GitHub UI, and credentials
    filed under Variables are stored in plaintext and echoed into job
    environments unmasked.

Exit status: 0 when the build settings are usable (with or without Last.fm
credentials), 1 when the pair is corrupt (control characters), unwritable, or
required and missing.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from typing import Dict, List, TextIO, Tuple

API_KEY_NAME = "LASTFM_API_KEY"
SECRET_NAME = "LASTFM_SECRET"
SERVER_NAME = "LISTEN_TOGETHER_SERVER"
REQUIRED_NAME = "LASTFM_REQUIRED"

# Environment names the workflow forwards from repository *Variables*, used
# only to diagnose credentials filed on the wrong tab. Booleans, never values.
API_KEY_VARIABLE_NAME = "LASTFM_API_KEY_VARIABLE"
SECRET_VARIABLE_NAME = "LASTFM_SECRET_VARIABLE"

# Characters that cannot survive a properties file or a generated Java string
# literal: C0 control characters other than tab, plus DEL. (Tab is escapable
# and allowed; newline inside a *value* is impossible to type into the GitHub
# secrets form without them being control characters, and is rejected too.)
CONTROL_CHARS = re.compile(r"[\x00-\x08\x0a-\x1f\x7f]")

TRUTHY = {"1", "true", "yes", "on"}


def escape_java_property(value: str) -> str:
    """Escape one value for a `key=value` line of a Java Properties file.

    Covers the characters Properties gives a special meaning to (``\\ = : #
    !``), leading whitespace of the value (Properties skips it unless
    escaped), tab/newline escapes, and every non-ASCII character as
    ``\\uXXXX`` — the encoding `java.util.Properties.load(InputStream)` reads
    is ISO-8859-1, so anything above U+007E has to go through the escape to
    survive the round trip.
    """
    out: List[str] = []
    for index, char in enumerate(value):
        code = ord(char)
        if char == "\\":
            out.append("\\\\")
        elif char in "=:#!":
            out.append("\\" + char)
        elif index == 0 and char in " \t":
            out.append("\\" + char if char == " " else "\\t")
        elif char == "\t":
            out.append("\\t")
        elif char == "\n":
            out.append("\\n")
        elif char == "\r":
            out.append("\\r")
        elif code > 0x7E:
            # \uXXXX escapes carry UTF-16 code units, so a supplementary
            # character (an emoji, a rare CJK ideograph) becomes the same
            # surrogate pair Java's own writer would emit.
            data = char.encode("utf-16-be", "surrogatepass")
            for unit in range(0, len(data), 2):
                out.append("\\u%04x" % int.from_bytes(data[unit : unit + 2], "big"))
        elif code < 0x20:
            out.append("\\u%04x" % code)
        else:
            out.append(char)
    return "".join(out)


class Result:
    """What one evaluation decided: lines to write, what to say, and the status."""

    def __init__(
        self,
        lines: List[str],
        messages: List[Tuple[str, str]],
        exit_code: int,
    ) -> None:
        self.lines = lines
        # (level, text) with level one of "error", "warning", "notice".
        self.messages = messages
        self.exit_code = exit_code


def evaluate(env: Dict[str, str]) -> Result:
    """Decide the settings content and diagnostics from an environment mapping.

    Pure and value-blind: nothing returned here contains a credential value,
    only setting names and status — which is what makes the messages safe to
    print into a public CI log.
    """
    messages: List[Tuple[str, str]] = []
    lines: List[str] = []

    required = (env.get(REQUIRED_NAME) or "").strip().lower() in TRUTHY

    raw_key = env.get(API_KEY_NAME) or ""
    raw_secret = env.get(SECRET_NAME) or ""

    # Whitespace/control-character validation, naming the setting only.
    corrupt = [
        name
        for name, raw in ((API_KEY_NAME, raw_key), (SECRET_NAME, raw_secret))
        if CONTROL_CHARS.search(raw)
    ]
    if corrupt:
        for name in corrupt:
            messages.append((
                "error",
                f"{name} contains control characters or embedded newlines; refusing to "
                f"write it. Re-save the repository secret without them. The value is not shown.",
            ))
        return Result(lines, messages, 1)

    api_key = raw_key.strip()
    secret = raw_secret.strip()
    have_key = bool(api_key)
    have_secret = bool(secret)

    misplaced = [
        secret_name
        for secret_name, variable_name in (
            (API_KEY_NAME, API_KEY_VARIABLE_NAME),
            (SECRET_NAME, SECRET_VARIABLE_NAME),
        )
        if (env.get(variable_name) or "").strip()
    ]

    if have_key and have_secret:
        lines.append(f"{API_KEY_NAME}={escape_java_property(api_key)}")
        lines.append(f"{SECRET_NAME}={escape_java_property(secret)}")
        messages.append((
            "notice",
            "Last.fm credentials: complete pair written to the build settings. "
            "Last.fm credentials in APK: configured. Values are not shown.",
        ))
    else:
        if have_key or have_secret:
            present, missing = (
                (API_KEY_NAME, SECRET_NAME) if have_key else (SECRET_NAME, API_KEY_NAME)
            )
            messages.append((
                "warning",
                f"Last.fm credentials: {present} is set but {missing} is empty. A partial "
                f"pair is never written; the build proceeds without Last.fm credentials "
                f"rather than combine halves from different sources.",
            ))
        else:
            messages.append((
                "warning",
                "Last.fm credentials: neither repository secret is set. The build proceeds "
                "without them; the app offers in-app Last.fm API setup. Set LASTFM_REQUIRED=true "
                "as a repository variable to require configured builds instead.",
            ))
        if misplaced:
            messages.append((
                "warning",
                f"{', '.join(misplaced)} appear to be stored as repository Variables rather "
                f"than repository Secrets (Settings > Secrets and variables > Actions > "
                f"'Secrets' tab). Variables are plaintext and reach this job unmasked; move "
                f"them to Secrets. Only their presence was checked, never their values.",
            ))
        if required:
            messages.append((
                "error",
                f"{REQUIRED_NAME} is set but no complete Last.fm credential pair is "
                f"available; failing the build as requested.",
            ))
            return Result(lines, messages, 1)

    server = (env.get(SERVER_NAME) or "").strip()
    if server:
        lines.append(f"{SERVER_NAME}={escape_java_property(server)}")

    return Result(lines, messages, 0)


def main(argv: List[str] | None = None, env: Dict[str, str] | None = None, stdout: TextIO | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--out",
        default="local.properties",
        help="properties file to write (created or replaced)",
    )
    args = parser.parse_args(argv)

    env = os.environ if env is None else env
    stdout = sys.stdout if stdout is None else stdout
    github = (env.get("GITHUB_ACTIONS") or "").lower() == "true"

    result = evaluate(env)
    for level, text in result.messages:
        if github:
            print(f"::{level}::{text}", file=stdout)
        else:
            print(f"{level}: {text}", file=stdout)

    if result.exit_code != 0:
        return result.exit_code

    try:
        with open(args.out, "w", encoding="utf-8", newline="\n") as handle:
            for line in result.lines:
                handle.write(line + "\n")
    except OSError as exc:
        # The path and the OS reason, never a value.
        print(
            f"::error::Could not write {args.out}: {exc.strerror or exc}" if github
            else f"error: could not write {args.out}: {exc.strerror or exc}",
            file=stdout,
        )
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
