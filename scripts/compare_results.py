#!/usr/bin/env python3
import json
import os
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RESULTS = os.path.join(ROOT, "results")

# (proyecto, baseline, variante, [(etiqueta, clave, formato, mejor)])
SPECS = [
    ("caffeine", "baseline", "countsketch", [
        ("hit rate %",      "hit_rate_percent",  "{:.4f}", "max"),
        ("hits",            "hits",              "{:.0f}", "max"),
        ("misses",          "misses",            "{:.0f}", "min"),
        ("evictions",       "evictions",         "{:.0f}", None),
        ("admit rate %",    "admit_rate_percent", "{:.4f}", None),
    ]),
    ("ristretto", "baseline", "countsketch", [
        ("hit ratio",       "ratio",             "{:.4f}", "max"),
        ("hits",            "hits",              "{:.0f}", "max"),
        ("misses",          "misses",            "{:.0f}", "min"),
        ("keys added",      "keys_added",        "{:.0f}", None),
        ("sketch bytes",    "sketch_bytes",      "{:.0f}", "min"),
        ("counter max",     "sketch_counter_max", "{:.0f}", None),
    ]),
    ("rocksdb", "baseline", "binaryfuse", [
        ("filter bytes",    "filter_size_bytes",         "{:.0f}", "min"),
        ("bits/key",        "filter_bits_per_key",       "{:.4f}", "min"),
        ("false pos. rate", "filter_false_positive_rate", "{:.6f}", "min"),
        ("false positives", "filter_false_positives",    "{:.0f}", "min"),
        ("lookups/s",       "lookups_per_second",        "{:.0f}", "max"),
    ]),
    ("cassandra", "baseline", "cuckoo", [
        ("filter bytes",    "filter_space_used_bytes",     "{:.0f}", "min"),
        ("false positives", "bloom_filter_false_positives", "{:.0f}", "min"),
        ("false ratio",     "bloom_filter_false_ratio",    "{:.6f}", "min"),
        ("read op rate",    "read_op_rate",                "{:.0f}", "max"),
        ("read p99 ms",     "read_latency_p99_ms",         "{:.3f}", "min"),
    ]),
]


def load(project, name):
    path = os.path.join(RESULTS, project, f"{name}.json")
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, json.JSONDecodeError):
        return None


def fmt(value, spec):
    if value is None:
        return "n/d"
    try:
        return spec.format(float(value))
    except (TypeError, ValueError):
        return str(value)


def main():
    any_found = False
    for project, base_name, mod_name, metrics in SPECS:
        base = load(project, base_name)
        mod = load(project, mod_name)
        if base is None and mod is None:
            continue
        any_found = True

        print()
        print("=" * 72)
        print(f"  {project.upper()}   ({base_name}  vs  {mod_name})")
        print("=" * 72)
        if base is None or mod is None:
            missing = base_name if base is None else mod_name
            print(f"  (falta results/{project}/{missing}.json — variante no corrida)")
            continue

        print(f"  {'metrica':<18} {'baseline':>16} {'reemplazo':>16} {'cambio':>14}")
        print(f"  {'-'*18} {'-'*16} {'-'*16} {'-'*14}")

        identical = 0
        compared = 0
        for label, key, spec, better in metrics:
            b, m = base.get(key), mod.get(key)
            delta = ""
            if b is not None and m is not None:
                compared += 1
                try:
                    bf, mf = float(b), float(m)
                    if bf == mf:
                        identical += 1
                    if bf != 0:
                        pct = (mf - bf) / abs(bf) * 100
                        arrow = ""
                        if better == "max":
                            arrow = " mejor" if mf > bf else (" peor" if mf < bf else "")
                        elif better == "min":
                            arrow = " mejor" if mf < bf else (" peor" if mf > bf else "")
                        delta = f"{pct:+.2f}%{arrow}"
                except (TypeError, ValueError):
                    pass
            print(f"  {label:<18} {fmt(b, spec):>16} {fmt(m, spec):>16} {delta:>14}")

        if compared > 0 and identical == compared:
            print()
            print("  *** SOSPECHOSO: todas las metricas son identicas al baseline.")
            print("      Casi siempre significa que el reemplazo NO quedo conectado")
            print("      (sed que no calzo, build tag sin efecto, property ignorada).")

    if not any_found:
        print("No hay resultados todavia. Corre ./scripts/run_all.sh")
        return 1
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
