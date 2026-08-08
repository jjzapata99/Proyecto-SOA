#!/bin/bash
set -euo pipefail

VARIANT="${SKETCH_VARIANT:-baseline}"
OUTPUT_PREFIX="${OUTPUT_PREFIX:-$VARIANT}"
TRACE_FILE="${TRACE_FILE:-/traces/zipfian_1e6.trace}"
CACHE_SIZE="${CACHE_SIZE:-10000}"
OUT="/results/${OUTPUT_PREFIX}.json"
CSV="/results/${OUTPUT_PREFIX}.csv"

echo "== Caffeine simulator — variante: ${VARIANT} =="

# El nombre del sketch tal como lo espera el switch de TinyLfu.makeSketch().
if [ "$VARIANT" = "countsketch" ]; then
  SKETCH="count-sketch"
else
  SKETCH="count-min-4"
fi

# application.conf y no -D: `files.paths` es una lista y las listas no viajan
# como propiedades del sistema; además Gradle corre el simulador en un JVM
# forkeado. El módulo declara dependsOn(processResources), así que este archivo
# entra al classpath del run siguiente.
CONF=/workspace/caffeine/simulator/src/main/resources/application.conf
cat > "$CONF" <<EOF
caffeine.simulator {
  files {
    # Enteros planos, una llave por línea == formato "lirs"
    paths = [ "${TRACE_FILE}" ]
    format = lirs
  }

  maximum-size = ${CACHE_SIZE}

  # La única política que consulta el sketch de frecuencia vía TinyLfu.
  policies = [ sketch.WindowTinyLfu ]
  admission = [ TinyLfu ]

  tiny-lfu {
    sketch = ${SKETCH}
  }

  report {
    format = csv
    output = "${CSV}"
  }
}
EOF

echo "--- application.conf efectivo ---"
cat "$CONF"
echo "---------------------------------"

# Tarea `run` y no `simulate`: esta última es para barridos de parámetros, y acá
# la configuración ya la fija application.conf.
cd /workspace/caffeine
./gradlew :simulator:run --no-daemon \
    -x spotbugsMain -x pmdMain -x checkstyleMain \
  | tee "/results/${OUTPUT_PREFIX}.log"

if [ ! -s "$CSV" ]; then
  echo "ERROR: el simulador no produjo ${CSV}" >&2
  exit 1
fi

echo "--- CSV crudo ---"
cat "$CSV"
echo "-----------------"

# El CSV trae una fila por política: Policy, Hit Rate, Hits, Misses, ...
python3 - "$CSV" "$OUT" "$VARIANT" "$SKETCH" "$CACHE_SIZE" <<'PY'
import csv, json, sys
csv_path, out_path, variant, sketch, cache_size = sys.argv[1:6]
with open(csv_path, newline="") as f:
    rows = list(csv.DictReader(f))
if not rows:
    raise SystemExit("CSV sin filas de resultados")
row = rows[0]
def num(key, cast=float):
    for k, v in row.items():
        if k and k.strip().lower() == key:
            try:
                return cast(str(v).strip().replace("%", "").replace(",", ""))
            except (TypeError, ValueError):
                return None
    return None
result = {
    "variant": variant,
    "sketch": sketch,
    "cache_size": int(cache_size),
    "policy": row.get("Policy"),
    "hit_rate_percent": num("hit rate"),
    "hits": num("hits", lambda s: int(float(s))),
    "misses": num("misses", lambda s: int(float(s))),
    "evictions": num("evictions", lambda s: int(float(s))),
    "admit_rate_percent": num("admit rate"),
    "raw_csv_row": row,
}
with open(out_path, "w") as f:
    json.dump(result, f, indent=2)
print(json.dumps(result, indent=2))
PY

echo "== Listo. Resultados en ${OUT} =="
