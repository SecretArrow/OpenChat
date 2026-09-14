#!/usr/bin/env python3
"""Extract the tap center of the first UI node matching a uiautomator dump.

Reads the XML dump on stdin, looks for the first <node> whose `attr`
("text" or "content-desc") equals `value`, and prints its bounds center
as "cx cy". Prints nothing (exit 0) when no match — the caller decides
whether that is fatal.

Robust against regex metacharacters in UI labels such as
"Local models (on-device)".
"""
import re
import sys


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: emu_ui.py <text|content-desc> <value>", file=sys.stderr)
        return 2
    attr, val = sys.argv[1], sys.argv[2]
    xml = sys.stdin.read()
    node = re.search(
        r"<node [^>]*\b" + attr + r"=\"" + re.escape(val) + r"\"[^>]*>", xml
    )
    if not node:
        return 0
    bounds = re.search(
        r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", node.group(0)
    )
    if not bounds:
        return 0
    x1, y1, x2, y2 = map(int, bounds.groups())
    print((x1 + x2) // 2, (y1 + y2) // 2)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
