#!/usr/bin/env python3
"""The measures of the end-to-end trial of the « Opération » mode (P39.12, docs/specs/group-hierarchy.md § 23).

Reads the counters that tools/emulator/operation-e2e.sh asked of each device between its steps (counters.txt: one
line per label and device, « <label> <A|B> t=<ms> <key>=<count>… », written by TrafficMeter and TrafficStats) and
prints, for each device and each stretch between two labels: its length, the server's calls (writes, and those per
minute), the bytes of their bodies up and down, the frames of the operation out and in, the app's whole traffic
(TrafficStats: TLS and headers included, the map's tiles too), then the writes by genre and how long a frame waited
in the relay before it was written. The app's counters start again from zero when its process is started again: such
a stretch counts from zero (« redémarrée »). Only counts: nothing in them is private.

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
GENRES = ["OPS", "SUMMARY", "KEYS", "SHARE", "JOIN", "INVITE_KEY", "DISSOLUTION", "POSITION"]


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


def diff(a, b):
    """b minus a, key by key; from zero when the app's counters went back (its process was started again)."""
    # A key gone, or a count gone down: a new process (one that has made no call yet has no key at all).
    app = [k for k in set(a) | set(b) if k not in ("t", "uid.tx", "uid.rx")]
    restarted = any(b.get(k, 0) < a.get(k, 0) for k in app)
    out = {}
    for k in set(a) | set(b):
        if k == "t":
            continue
        start = 0 if restarted and k not in ("uid.tx", "uid.rx") else a.get(k, 0)
        out[k] = b.get(k, 0) - start
    # TrafficStats counts by app (uid): it survives a restart of the process.
    return out, (b["t"] - a["t"]) / 1000, restarted


def stretches(rows):
    by_role = {}
    for label, role, values in rows:
        by_role.setdefault(role, []).append((label, values))
    out = []
    for role in sorted(by_role):
        seq = by_role[role]
        for (l0, a), (l1, b) in zip(seq, seq[1:]):
            d, seconds, restarted = diff(a, b)
            out.append((f"{l0} → {l1}" + (" (redémarrée)" if restarted else ""), role, seconds, d))
    return out


def table(rows):
    head = ["tronçon", "appareil", "durée (s)"] + [name for _, name in COLUMNS] + ["écritures / min"]
    print("| " + " | ".join(head) + " |")
    print("|" + "---|" * len(head))
    for name, role, seconds, d in rows:
        values = [d.get(key, 0) for key, _ in COLUMNS]
        per_minute = values[0] / seconds * 60 if seconds > 0 else 0
        print("| " + " | ".join([name, role, f"{seconds:.0f}"] + [str(v) for v in values] + [f"{per_minute:.1f}"]) + " |")


def genres(rows):
    head = ["tronçon", "appareil"] + GENRES + ["attente moy. (ms)", "attente > 1 s"]
    print("| " + " | ".join(head) + " |")
    print("|" + "---|" * len(head))
    for name, role, _, d in rows:
        cells = []
        for g in GENRES:
            kind = "p" if g == "POSITION" else "c"
            cells.append(f"{d.get(f'writes.{kind}.{g}', 0)} ({d.get(f'frames.out.{g}', 0)}/{d.get(f'frames.in.{g}', 0)})")
        n = d.get("queue.writes", 0)
        mean = d.get("queue.ms", 0) / n if n else 0
        print("| " + " | ".join([name, role] + cells + [f"{mean:.0f}", str(d.get("queue.over1s", 0))]) + " |")


def main(path):
    rows = stretches(parse(path))
    print("Mesures par appareil et par tronçon (corps HTTP : sans en-têtes ni TLS ; appli : tout le trafic de l'appli) :")
    table(rows)
    print()
    print("Sur tout l'essai (somme des tronçons) :")
    totals = {}
    for _, role, seconds, d in rows:
        t = totals.setdefault(role, [0.0, {}])
        t[0] += seconds
        for k, v in d.items():
            t[1][k] = t[1].get(k, 0) + v
    table([("tout l'essai", role, seconds, d) for role, (seconds, d) in sorted(totals.items())])
    print()
    print("Écritures par genre : écrites (trames envoyées par l'opération / reçues), et attente avant l'écriture :")
    genres(rows + [("tout l'essai", role, seconds, d) for role, (seconds, d) in sorted(totals.items())])
    print()
    for role, (_, d) in sorted(totals.items()):
        refused = {k: v for k, v in d.items() if k.startswith("http.") and v}
        print(f"Réponses du serveur autres qu'un succès, {role} : " + (", ".join(f"{k} {v}" for k, v in sorted(refused.items())) or "aucune"))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "operation-e2e/counters.txt")
