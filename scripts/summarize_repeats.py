#!/usr/bin/env python3
"""Resume las corridas de scripts/run_repeats.sh.

Para cada métrica imprime media, mínimo y máximo de las N repeticiones, y —
cuando hay al menos 2 de cada lado — la prueba t de Welch entre baseline y
reemplazo. Hace falta porque en las métricas ruidosas (hit ratio de Ristretto,
throughput de RocksDB y Cassandra) la diferencia entre variantes puede ser más
chica que la variación entre corridas de una misma variante, y una sola corrida
no deja verlo.
"""
import glob
import json
import os
import statistics as st
import sys
import warnings

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RESULTS = os.path.join(ROOT, "results")

try:
    from scipy import stats as _scipy_stats
except ImportError:
    _scipy_stats = None

# (proyecto, baseline, reemplazo, [(etiqueta, clave, formato)])
SPECS = [
    ("caffeine", "baseline", "countsketch", [
        ("hit rate %",       "hit_rate_percent",   "{:.4f}"),
        ("admit rate %",     "admit_rate_percent", "{:.4f}"),
    ]),
    ("ristretto", "baseline", "countsketch", [
        ("hit ratio",        "ratio",              "{:.6f}"),
        ("keys added",       "keys_added",         "{:.0f}"),
    ]),
    ("rocksdb", "baseline", "binaryfuse", [
        ("bits/key",         "filter_bits_per_key",        "{:.4f}"),
        ("false pos. rate",  "filter_false_positive_rate", "{:.6f}"),
        ("filter bytes",     "filter_size_bytes",          "{:.0f}"),
        ("lookups/s",        "lookups_per_second",         "{:.0f}"),
    ]),
    ("cassandra", "baseline", "cuckoo", [
        ("false positives",  "bloom_filter_false_positives", "{:.0f}"),
        ("false ratio",      "bloom_filter_false_ratio",     "{:.6f}"),
        ("filter bytes",     "filter_space_used_bytes",      "{:.0f}"),
        ("read op rate",     "read_op_rate",                 "{:.0f}"),
        ("read p99 ms",      "read_latency_p99_ms",          "{:.3f}"),
    ]),
]


def load(project, variant):
    pattern = os.path.join(RESULTS, project, "repeats", f"rep_{variant}_*.json")
    runs = []
    for path in sorted(glob.glob(pattern)):
        try:
            with open(path) as f:
                runs.append(json.load(f))
        except (OSError, json.JSONDecodeError):
            pass
    return runs


def series(runs, key):
    out = []
    for run in runs:
        try:
            out.append(float(run[key]))
        except (KeyError, TypeError, ValueError):
            pass
    return out


def main():
    wanted = sys.argv[1:]
    found = False

    for project, base_name, mod_name, metrics in SPECS:
        if wanted and project not in wanted:
            continue
        base_runs, mod_runs = load(project, base_name), load(project, mod_name)
        if not base_runs and not mod_runs:
            continue
        found = True

        print()
        print("=" * 78)
        print(f"  {project.upper()}   ({base_name} n={len(base_runs)}  vs  "
              f"{mod_name} n={len(mod_runs)})")
        print("=" * 78)
        print(f"  {'metrica':<17} {'baseline (media)':>18} {'reemplazo (media)':>18} "
              f"{'cambio':>10} {'p':>8}")
        print(f"  {'-'*17} {'-'*18} {'-'*18} {'-'*10} {'-'*8}")

        for label, key, spec in metrics:
            a, b = series(base_runs, key), series(mod_runs, key)
            if not a or not b:
                continue
            ma, mb = st.mean(a), st.mean(b)
            change = f"{(mb - ma) / abs(ma) * 100:+.2f}%" if ma else ""

            pval = ""
            if _scipy_stats is not None and len(a) > 1 and len(b) > 1:
                if st.pstdev(a) == 0 and st.pstdev(b) == 0:
                    # Metrica determinista: la prueba t no aplica ni hace falta.
                    pval = "exacta"
                else:
                    # Con una de las dos variantes constante, scipy avisa por
                    # cancelacion catastrofica; el p-valor igual sirve.
                    with warnings.catch_warnings():
                        warnings.simplefilter("ignore")
                        p = _scipy_stats.ttest_ind(a, b, equal_var=False).pvalue
                    pval = f"{p:.4f}"

            print(f"  {label:<17} {spec.format(ma):>18} {spec.format(mb):>18} "
                  f"{change:>10} {pval:>8}")
            # El rango importa tanto como la media: si los rangos de las dos
            # variantes se solapan, la diferencia de medias puede ser ruido.
            print(f"  {'':<17} {'[' + spec.format(min(a)) + ', ' + spec.format(max(a)) + ']':>18} "
                  f"{'[' + spec.format(min(b)) + ', ' + spec.format(max(b)) + ']':>18}")

    if not found:
        print("No hay repeticiones todavía. Corré ./scripts/run_repeats.sh")
        return 1
    if _scipy_stats is None:
        print("\n  (sin scipy instalado: no se calcularon los p-valores)")
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
