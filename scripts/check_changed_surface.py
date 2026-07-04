#!/usr/bin/env python3
"""Classify changed files into review surface areas.

Usage:
  git diff --name-only origin/main...HEAD | python scripts/check_changed_surface.py
"""
from __future__ import annotations

import sys
from collections import Counter

RULES = [
    ("tests", ("test", "tests", ".test.", ".spec.")),
    ("frontend", ("web/src/", "web/tests/", "apps/")),
    ("backend", ("services/platform-api/", "services/api/")),
    ("provider", ("providers/", "services/platform-api/providers/", "services/api/app/vrp/")),
    ("infra", ("infra/", ".github/workflows/", "terraform/")),
    ("db", ("db/migration/", "migrations/", ".sql")),
    ("docs", ("docs/", "README.md")),
]


def classify(path: str) -> str:
    normalized = path.replace("\\", "/")
    for label, needles in RULES:
        if any(needle in normalized for needle in needles):
            return label
    return "other"


def main() -> int:
    files = [line.strip() for line in sys.stdin if line.strip()]
    counts = Counter(classify(path) for path in files)
    print("Changed surface:")
    for label, count in counts.most_common():
        print(f"- {label}: {count}")
    print(f"Total files: {len(files)}")
    if len(files) > 30:
        print("WARNING: large PR. Consider a do-not-merge spike or split plan.")
    if counts["db"] and not counts["tests"]:
        print("WARNING: DB changes detected without tests.")
    if counts["frontend"] and not counts["tests"]:
        print("WARNING: frontend changes detected without tests.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
