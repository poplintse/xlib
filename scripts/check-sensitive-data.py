#!/usr/bin/env python3
"""Reject tracked credentials, signing material, and private keys.

The check reports paths and finding categories only. It never prints matched
content, so a failed check cannot echo a credential into CI logs.
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parent.parent
ALLOWED_ENV_NAMES = {".env.example"}
SENSITIVE_SUFFIXES = {
    ".jks",
    ".key",
    ".keystore",
    ".mobileprovision",
    ".p12",
    ".pem",
}
SECRET_PATTERNS = {
    "private key": re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
    "GitHub token": re.compile(rb"(?:github_pat_[A-Za-z0-9_]{20,}|ghp_[A-Za-z0-9]{20,})"),
    "AWS access key": re.compile(rb"AKIA[A-Z0-9]{16}"),
    "live payment key": re.compile(rb"sk_live_[A-Za-z0-9]{16,}"),
}
DATABASE_URI = re.compile(
    rb"(?:postgres(?:ql)?|mysql|mongodb(?:\+srv)?)://[^\s\"']+",
    re.IGNORECASE,
)
PLACEHOLDER_PASSWORDS = {
    "invalid",
    "password",
    "replace-api-password",
    "test",
}


def tracked_paths() -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=ROOT,
        check=True,
        stdout=subprocess.PIPE,
    )
    return [ROOT / item.decode() for item in result.stdout.split(b"\0") if item]


def credentialed_database_uri_is_real(value: bytes) -> bool:
    try:
        parsed = urlsplit(value.decode("utf-8"))
        password = parsed.password
    except (UnicodeDecodeError, ValueError):
        return True
    if password is None:
        return False
    if password in PLACEHOLDER_PASSWORDS:
        return False
    return not (password.startswith("${") and password.endswith("}"))


def main() -> int:
    findings: set[tuple[str, str]] = set()
    for path in tracked_paths():
        if not path.is_file():
            continue
        relative = path.relative_to(ROOT).as_posix()
        name = path.name.lower()
        if name.startswith(".env") and name not in ALLOWED_ENV_NAMES:
            findings.add((relative, "tracked environment file"))
        if path.suffix.lower() in SENSITIVE_SUFFIXES:
            findings.add((relative, "tracked credential or signing file"))

        try:
            data = path.read_bytes()
        except OSError:
            findings.add((relative, "could not inspect tracked file"))
            continue
        if b"\0" in data[:8192]:
            continue
        for category, pattern in SECRET_PATTERNS.items():
            if pattern.search(data):
                findings.add((relative, category))
        for match in DATABASE_URI.finditer(data):
            if credentialed_database_uri_is_real(match.group(0)):
                findings.add((relative, "database URI containing credentials"))

    if findings:
        print("sensitive data check failed:", file=sys.stderr)
        for relative, category in sorted(findings):
            print(f"- {relative}: {category}", file=sys.stderr)
        return 1
    print("sensitive data check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
