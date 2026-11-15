#!/usr/bin/env python3
"""The measures of the end-to-end trial of the « Opération » mode (P39.12, docs/specs/group-hierarchy.md § 23).

Reads the counters that tools/emulator/operation-e2e.sh asked of each device between its steps (counters.txt: one
line per label and device, « <label> <A|B> t=<ms> <key>=<count>… », written by TrafficMeter and TrafficStats) and
prints, for each device and each stretch between two labels: its length, the server's calls (writes, and those per
minute), the bytes of their bodies up and down, the frames of the operation out and in, and the app's whole traffic
(TrafficStats, TLS and headers included, the map's tiles too). Then the frames by genre over the whole run. Only
counts: nothing in them is private.

    python3 tools/emulator/operation-e2e-measures.py operation-e2e/counters.txt
"""
import sys

COLUMNS = [
    ("writes", "écritures"),
    ("call.h", "présence"),
    ("call.p", "position"),
    ("call.c", "contrôle"),
    ("call.c.clean", "nettoyage"),
    ("call.stream", "flux"),
    ("call.auth", "auth"),
    ("up", "corps ↑ (o)"),
    ("down", "corps ↓ (o)"),
    ("frames.out", "trames ↑"),
    ("frames.in", "trames ↓"),
    ("uid.tx", "appli ↑ (o)"),
    ("uid.rx", "appli ↓ (o)"),
]


def parse(path):
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.split()
            if len(parts) < 3:
                continue
            values = {}
            for word in parts[2:]:
                key, _, value = word.partition("=")
                if value.lstrip("-").isdigit():
                    values[key] = int(value)
            if "t" in values:
                rows.append((parts[0], parts[1], values))
    return rows


def table(stretches):
    head = ["tronçon", "appareil", "durée (s)"] + [name for _, name in COLUMNS] + ["écritures / min"]
    print("| " + " | ".join(head) + " |")
    print("|" + "---|" * len(head))
    for name, role, a, b in stretches:
        seconds = (b["t"] - a["t"]) / 1000
        diff = [b.get(key, 0) - a.get(key, 0) for key, _ in COLUMNS]
        per_minute = diff[0] / seconds * 60 if seconds > 0 else 0
        cells = [name, role, f"{seconds:.0f}"] + [str(d) for d in diff] + [f"{per_minute:.1f}"]
        print("| " + " | ".join(cells) + " |")


def main(path):
    rows = parse(path)
    by_role = {}
    for label, role, values in rows:
        by_role.setdefault(role, []).append((label, values))
    stretches = []
    for role in sorted(by_role):
        seq = by_role[role]
        for (l0, a), (l1, b) in zip(seq, seq[1:]):
            stretches.append((f"{l0} → {l1}", role, a, b))
    print("Mesures par appareil et par tronçon (corps HTTP : sans en-têtes ni TLS ; appli : tout le trafic de l'appli) :")
    table(stretches)
    print()
    print("Sur tout l'essai :")
    table([(f"{seq[0][0]} → {seq[-1][0]}", role, seq[0][1], seq[-1][1]) for role, seq in sorted(by_role.items()) if len(seq) > 1])
    print()
    print("Trames de l'opération par genre, sur tout l'essai (envoyées / reçues) :")
    for role, seq in sorted(by_role.items()):
        if len(seq) < 2:
            continue
        first, last = seq[0][1], seq[-1][1]
        genres = sorted({k.split(".", 2)[2] for k in last if k.startswith("frames.out.") or k.startswith("frames.in.")})
        cells = []
        for genre in genres:
            out = last.get(f"frames.out.{genre}", 0) - first.get(f"frames.out.{genre}", 0)
            got = last.get(f"frames.in.{genre}", 0) - first.get(f"frames.in.{genre}", 0)
            cells.append(f"{genre} {out}/{got}")
        print(f"  {role} : " + ", ".join(cells))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "operation-e2e/counters.txt")
