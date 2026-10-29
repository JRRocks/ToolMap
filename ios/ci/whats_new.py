#!/usr/bin/env python3
"""« Nouveautés » of the iPhone app (Android P16.3, WhatsNewTask of app/build.gradle.kts).

Reads docs/JOURNAL.md and writes ios/ToolMap/Resources/whats-new.json with the 5 latest entries that concern the
iPhone: the entries of an iOS session (« (iOS) », « iOS », « iPhone » or a P32 task in the title) and the general ones
(no task ID in the title, such as the rules of the project). Like on Android, each entry keeps its title, without
« Session N — », and its « Objectif » line, cut at 400 characters; its other fields stay out.

Run from anywhere, before `xcodegen generate` (the JSON is a resource of the app):
    python3 ios/ci/whats_new.py
"""

import json
import re
import sys
from pathlib import Path

MAX_ENTRIES = 5
MAX_GOAL = 400
GOAL_PREFIX = "- **Objectif** :"

ROOT = Path(__file__).resolve().parents[2]
JOURNAL = ROOT / "docs" / "JOURNAL.md"
OUTPUT = ROOT / "ios" / "ToolMap" / "Resources" / "whats-new.json"

SESSION = re.compile(r"Session \d+(?: \([^)]*\))? — ")
IOS = re.compile(r"\(iOS\)|\biOS\b|iPhone|\[P32")


def concerns_iphone(title: str) -> bool:
    """An iOS entry: the general ones are rules of the project, of no interest to the person using the app."""
    return bool(IOS.search(title))


def entries(lines):
    """(title, goal) of every entry of the journal, newest first (the journal is written newest first)."""
    found = []
    title = None
    goal = ""
    for line in lines:
        if line.startswith("## "):
            if title is not None:
                found.append((title, goal))
            title = line[3:].strip()
            goal = ""
        elif title is not None and not goal and line.startswith(GOAL_PREFIX):
            goal = line[len(GOAL_PREFIX):].strip()[:MAX_GOAL]
    if title is not None:
        found.append((title, goal))
    return found


def main() -> int:
    lines = JOURNAL.read_text(encoding="utf-8").splitlines() if JOURNAL.is_file() else []
    chosen = []
    for title, goal in entries(lines):
        if not concerns_iphone(title):
            continue
        chosen.append({"title": SESSION.sub("", title, count=1), "goal": goal})
        if len(chosen) == MAX_ENTRIES:
            break
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps({"entries": chosen}, ensure_ascii=False, indent=2) + "\n"
    OUTPUT.write_text(text, encoding="utf-8")
    print(f"{OUTPUT.relative_to(ROOT)}: {len(chosen)} entries")
    return 0


if __name__ == "__main__":
    sys.exit(main())
