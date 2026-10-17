"""Tests for scripts/write-lastfm-settings.py.

The writer is the CI half of the Last.fm credential fix: what it writes into
local.properties decides whether the APK's BuildConfig pair is complete, and
what it prints decides whether a value can leak into a public run log. So the
tests check both halves — content (escaped, complete pairs only, round-tripped
through a Java Properties reader) and silence (no diagnostic ever contains a
credential value).
"""

from __future__ import annotations

import importlib.util
import io
import os
import tempfile
import unittest
from pathlib import Path

SCRIPT_PATH = Path(__file__).resolve().parents[1] / "write-lastfm-settings.py"

_spec = importlib.util.spec_from_file_location("write_lastfm_settings", SCRIPT_PATH)
assert _spec is not None and _spec.loader is not None
writer = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(writer)


def parse_properties_line(line: str) -> tuple[str, str]:
    """Read one `key=value` line the way java.util.Properties.load would.

    Covers the escapes the writer emits: ``\\\\``, ``\\=``, ``\\:``, ``\\#``,
    ``\\!``, ``\\ ``(leading space), ``\\t``, ``\\n``, ``\\r`` and
    ``\\uXXXX``. Enough of the format to prove a round trip, not a full
    implementation — continuation lines and key escapes are not exercised
    because the writer never emits them.
    """
    key, _, raw_value = line.partition("=")
    out: list[str] = []
    index = 0
    leading = True
    while index < len(raw_value):
        char = raw_value[index]
        if char == "\\" and index + 1 < len(raw_value):
            escape = raw_value[index + 1]
            if escape == "u" and index + 5 < len(raw_value):
                unit = int(raw_value[index + 2 : index + 6], 16)
                index += 6
                # A high surrogate is followed by its low half; Java reads the
                # pair as one supplementary character, and so do we.
                if 0xD800 <= unit <= 0xDBFF and raw_value[index : index + 2] == "\\u":
                    low = int(raw_value[index + 2 : index + 6], 16)
                    if 0xDC00 <= low <= 0xDFFF:
                        unit = 0x10000 + ((unit - 0xD800) << 10) + (low - 0xDC00)
                        index += 6
                out.append(chr(unit))
                continue
            mapping = {
                "\\": "\\",
                "=": "=",
                ":": ":",
                "#": "#",
                "!": "!",
                " ": " ",
                "t": "\t",
                "n": "\n",
                "r": "\r",
            }
            if escape not in mapping:
                raise ValueError(f"unexpected escape \\{escape} in {line!r}")
            out.append(mapping[escape])
            index += 2
            leading = False
            continue
        if leading and char == " ":
            raise ValueError(f"unescaped leading space in value of {line!r}")
        leading = False
        out.append(char)
        index += 1
    return key, "".join(out)


def evaluate(env: dict[str, str]) -> tuple[list[str], list[tuple[str, str]], int]:
    result = writer.evaluate(env)
    return result.lines, result.messages, result.exit_code


def all_text(messages) -> str:
    return "\n".join(text for _, text in messages)


class EscapeRoundTripTest(unittest.TestCase):
    def test_special_characters_round_trip(self):
        for value in [
            "plainkey123",
            "back\\slash",
            "equals=sign",
            "colon:value",
            "hash#tag",
            "bang!here",
            " leading space",
            "trailingspace ",
            "tab\there",
            "unicode: café — 日本語 🔑",
            "quote\"single'both",
        ]:
            with self.subTest(value=repr(value)):
                escaped = writer.escape_java_property(value)
                key, parsed = parse_properties_line(f"K={escaped}")
                self.assertEqual(key, "K")
                self.assertEqual(parsed, value)

    def test_non_ascii_becomes_unicode_escape(self):
        escaped = writer.escape_java_property("日本")
        self.assertTrue(escaped.isascii())
        self.assertIn("\\u", escaped)


class CompletePairTest(unittest.TestCase):
    def test_complete_pair_is_written(self):
        lines, messages, code = evaluate({
            "LASTFM_API_KEY": " key-with-space ",
            "LASTFM_SECRET": "secret123",
        })
        self.assertEqual(code, 0)
        parsed = dict(parse_properties_line(line) for line in lines)
        self.assertEqual(parsed["LASTFM_API_KEY"], "key-with-space")
        self.assertEqual(parsed["LASTFM_SECRET"], "secret123")
        self.assertNotIn("secret123", all_text(messages))
        self.assertNotIn("key-with-space", all_text(messages))

    def test_complete_pair_values_never_printed(self):
        key, secret = "SUPERSECRETKEY9999", "SUPERSECRETSHARED77"
        _, messages, code = evaluate({"LASTFM_API_KEY": key, "LASTFM_SECRET": secret})
        self.assertEqual(code, 0)
        self.assertNotIn(key, all_text(messages))
        self.assertNotIn(secret, all_text(messages))


class PartialPairTest(unittest.TestCase):
    def test_key_only_writes_nothing(self):
        lines, messages, code = evaluate({
            "LASTFM_API_KEY": "abc123",
            "LASTFM_SECRET": "",
        })
        self.assertEqual(code, 0)
        self.assertEqual([line for line in lines if "LASTFM" in line], [])
        text = all_text(messages)
        self.assertIn("LASTFM_SECRET", text)
        self.assertNotIn("abc123", text)
        self.assertTrue(any(level == "warning" for level, _ in messages))

    def test_secret_only_writes_nothing(self):
        lines, messages, code = evaluate({
            "LASTFM_API_KEY": "   ",
            "LASTFM_SECRET": "shh",
        })
        self.assertEqual(code, 0)
        self.assertEqual([line for line in lines if "LASTFM" in line], [])
        self.assertIn("LASTFM_API_KEY", all_text(messages))
        self.assertNotIn("shh", all_text(messages))

    def test_blank_line_never_reaches_the_file(self):
        # An empty `LASTFM_API_KEY=` in local.properties is the original bug:
        # getProperty answers "" rather than null and hides the environment
        # fallback. The writer must never emit it.
        lines, _, _ = evaluate({"LASTFM_API_KEY": "", "LASTFM_SECRET": ""})
        for line in lines:
            self.assertFalse(line.startswith("LASTFM_API_KEY="))
            self.assertFalse(line.startswith("LASTFM_SECRET="))

    def test_listen_together_server_survives_a_missing_pair(self):
        lines, _, code = evaluate({
            "LASTFM_API_KEY": "",
            "LASTFM_SECRET": "",
            "LISTEN_TOGETHER_SERVER": "https://party.example.com",
        })
        self.assertEqual(code, 0)
        parsed = dict(parse_properties_line(line) for line in lines)
        self.assertEqual(parsed["LISTEN_TOGETHER_SERVER"], "https://party.example.com")


class RequiredTest(unittest.TestCase):
    def test_required_fails_on_missing_pair(self):
        _, messages, code = evaluate({
            "LASTFM_API_KEY": "",
            "LASTFM_SECRET": "",
            "LASTFM_REQUIRED": "true",
        })
        self.assertEqual(code, 1)
        self.assertIn("LASTFM_REQUIRED", all_text(messages))
        self.assertTrue(any(level == "error" for level, _ in messages))

    def test_required_fails_on_partial_pair(self):
        _, _, code = evaluate({
            "LASTFM_API_KEY": "abc",
            "LASTFM_SECRET": "",
            "LASTFM_REQUIRED": "TRUE",
        })
        self.assertEqual(code, 1)

    def test_required_passes_with_complete_pair(self):
        lines, _, code = evaluate({
            "LASTFM_API_KEY": "abc",
            "LASTFM_SECRET": "def",
            "LASTFM_REQUIRED": "true",
        })
        self.assertEqual(code, 0)
        self.assertEqual(len([line for line in lines if line.startswith("LASTFM")]), 2)


class MisplacedVariablesTest(unittest.TestCase):
    def test_variables_without_secrets_are_diagnosed_without_values(self):
        _, messages, code = evaluate({
            "LASTFM_API_KEY": "",
            "LASTFM_SECRET": "",
            "LASTFM_API_KEY_VARIABLE": "plaintext-key-in-a-variable",
            "LASTFM_SECRET_VARIABLE": "plaintext-secret-in-a-variable",
        })
        self.assertEqual(code, 0)
        text = all_text(messages)
        self.assertIn("Variables", text)
        self.assertIn("Secrets", text)
        self.assertIn("LASTFM_API_KEY", text)
        self.assertIn("LASTFM_SECRET", text)
        # Boolean presence check only — the variable contents never appear.
        self.assertNotIn("plaintext-key-in-a-variable", text)
        self.assertNotIn("plaintext-secret-in-a-variable", text)

    def test_no_diagnostic_when_secrets_present(self):
        _, messages, _ = evaluate({
            "LASTFM_API_KEY": "k",
            "LASTFM_SECRET": "s",
            "LASTFM_API_KEY_VARIABLE": "",
            "LASTFM_SECRET_VARIABLE": "",
        })
        self.assertNotIn("Variables", all_text(messages))


class ControlCharacterTest(unittest.TestCase):
    def test_control_characters_fail_without_showing_the_value(self):
        _, messages, code = evaluate({
            "LASTFM_API_KEY": "fine",
            "LASTFM_SECRET": "bad\x07value\x1b",
        })
        self.assertEqual(code, 1)
        text = all_text(messages)
        self.assertIn("LASTFM_SECRET", text)
        self.assertNotIn("bad", text)
        self.assertTrue(any(level == "error" for level, _ in messages))

    def test_embedded_newline_fails(self):
        _, messages, code = evaluate({
            "LASTFM_API_KEY": "line1\nline2",
            "LASTFM_SECRET": "s",
        })
        self.assertEqual(code, 1)
        self.assertIn("LASTFM_API_KEY", all_text(messages))

    def test_tab_is_allowed(self):
        # Tab is escapable in the Properties format, so it is not "corrupt".
        lines, _, code = evaluate({
            "LASTFM_API_KEY": "with\ttab",
            "LASTFM_SECRET": "s",
        })
        self.assertEqual(code, 0)
        parsed = dict(parse_properties_line(line) for line in lines)
        self.assertEqual(parsed["LASTFM_API_KEY"], "with\ttab")


class MainWriterTest(unittest.TestCase):
    def test_main_writes_file_and_github_format(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = os.path.join(tmp, "local.properties")
            stdout = io.StringIO()
            code = writer.main(
                ["--out", out],
                env={
                    "GITHUB_ACTIONS": "true",
                    "LASTFM_API_KEY": "the-key",
                    "LASTFM_SECRET": "the-secret",
                    "LISTEN_TOGETHER_SERVER": "https://party.example.com",
                },
                stdout=stdout,
            )
            self.assertEqual(code, 0)
            content = Path(out).read_text(encoding="utf-8")
            parsed = dict(parse_properties_line(line) for line in content.splitlines())
            self.assertEqual(parsed["LASTFM_API_KEY"], "the-key")
            self.assertEqual(parsed["LASTFM_SECRET"], "the-secret")
            self.assertEqual(parsed["LISTEN_TOGETHER_SERVER"], "https://party.example.com")
            printed = stdout.getvalue()
            self.assertIn("::notice::", printed)
            self.assertNotIn("the-key", printed)
            self.assertNotIn("the-secret", printed)

    def test_main_writes_nothing_on_partial_pair(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = os.path.join(tmp, "local.properties")
            code = writer.main(
                ["--out", out],
                env={"LASTFM_API_KEY": "half", "LASTFM_SECRET": ""},
                stdout=io.StringIO(),
            )
            self.assertEqual(code, 0)
            content = Path(out).read_text(encoding="utf-8")
            self.assertEqual(content, "")

    def test_main_reports_unwritable_output(self):
        stdout = io.StringIO()
        # A directory as the output path: open() fails with IsADirectoryError.
        code = writer.main(
            ["--out", tempfile.gettempdir()],
            env={
                "LASTFM_API_KEY": "DISTINGUISHABLE-KEY-7f3a",
                "LASTFM_SECRET": "DISTINGUISHABLE-SECRET-9c1b",
            },
            stdout=stdout,
        )
        self.assertEqual(code, 1)
        printed = stdout.getvalue()
        self.assertIn("error", printed.lower())
        self.assertNotIn("DISTINGUISHABLE-KEY-7f3a", printed)
        self.assertNotIn("DISTINGUISHABLE-SECRET-9c1b", printed)

    def test_main_fails_on_required_without_pair(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = os.path.join(tmp, "local.properties")
            code = writer.main(
                ["--out", out],
                env={"LASTFM_REQUIRED": "true"},
                stdout=io.StringIO(),
            )
            self.assertEqual(code, 1)
            self.assertFalse(os.path.exists(out))


if __name__ == "__main__":
    unittest.main()
