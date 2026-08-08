#!/bin/bash
set -euo pipefail

VARIANT="${FILTER_VARIANT:-bloom}"
OUTPUT_PREFIX="${OUTPUT_PREFIX:-$VARIANT}"
NUM_OPS="${NUM_OPS:-200000}"
THREADS="${THREADS:-8}"
OUT="/results/${OUTPUT_PREFIX}.json"

echo "== Cassandra bench — filtro: ${VARIANT} =="

# La lee FilterFactory.createFilter(). JVM_OPTS es la vía de bin/cassandra
# para pasar flags extra al JVM del nodo.
export JVM_OPTS="-Dcassandra.filter.impl=${VARIANT}"

# Cassandra se niega a arrancar como root salvo que se lo permitamos.
export CASSANDRA_CONF=/workspace/cassandra/conf

cd /workspace/cassandra

# Arranca Cassandra en background y espera a que esté lista.
bin/cassandra -f -R > "/results/${OUTPUT_PREFIX}_server.log" 2>&1 &
CASSANDRA_PID=$!

echo "Esperando a que Cassandra levante..."
for i in $(seq 1 120); do
  if bin/nodetool status > /dev/null 2>&1; then
    echo "Cassandra lista (tras ${i} intentos)."
    break
  fi
  if ! kill -0 "$CASSANDRA_PID" 2>/dev/null; then
    echo "ERROR: el proceso de Cassandra murió durante el arranque. Últimas líneas:" >&2
    tail -40 "/results/${OUTPUT_PREFIX}_server.log" >&2
    exit 1
  fi
  sleep 5
done

if ! bin/nodetool status > /dev/null 2>&1; then
  echo "ERROR: Cassandra no levantó en 10 minutos." >&2
  tail -40 "/results/${OUTPUT_PREFIX}_server.log" >&2
  exit 1
fi

# Confirma que el nodo levantó con la implementación de filtro que pedimos.
echo "--- filtro solicitado: ${VARIANT} ---"

# --- Carga ---
tools/bin/cassandra-stress write n="${NUM_OPS}" -rate threads="${THREADS}" \
  | tee "/results/${OUTPUT_PREFIX}_write.log"

# flush y NO compact: en memtable no se consulta ningún filtro, pero `compact`
# dejaría UN solo SSTable y entonces el filtro nunca recibiría una consulta
# negativa (medido: 0 falsos positivos en ambas variantes). Con varios SSTables,
# leer la llave K consulta el filtro de cada uno y K vive en uno solo, así que
# los demás dan negativos reales — que es donde Bloom y Cuckoo se diferencian.
bin/nodetool flush keyspace1 || true

# --- Lectura ---
tools/bin/cassandra-stress read n="${NUM_OPS}" -rate threads="${THREADS}" \
  | tee "/results/${OUTPUT_PREFIX}_read.log"

# Los contadores de tablestats los mantiene SSTableReader sobre CUALQUIER
# IFilter, así que también valen para el cuckoo.
bin/nodetool tablestats keyspace1.standard1 \
  | tee "/results/${OUTPUT_PREFIX}_tablestats.log"

python3 - "$OUTPUT_PREFIX" "$VARIANT" "$NUM_OPS" <<'PY'
import json, re, sys

prefix, variant, num_ops = sys.argv[1], sys.argv[2], int(sys.argv[3])

def read(path):
    try:
        with open(path) as f:
            return f.read()
    except OSError:
        return ""

stats = read(f"/results/{prefix}_tablestats.log")

def stat(pattern, cast=float):
    m = re.search(pattern, stats)
    if not m:
        return None
    try:
        return cast(m.group(1).replace(",", ""))
    except ValueError:
        return None

def stress(path, pattern, cast=float):
    m = re.search(pattern, read(path))
    if not m:
        return None
    try:
        return cast(m.group(1).replace(",", ""))
    except ValueError:
        return None

result = {
    "variant": variant,
    "filter_impl": "CuckooFilter" if variant == "cuckoo" else "BloomFilter",
    "ops_per_phase": num_ops,
    # --- filtro ---
    "bloom_filter_false_positives": stat(r"Bloom filter false positives:\s*([0-9,]+)", int),
    "bloom_filter_false_ratio": stat(r"Bloom filter false ratio:\s*([0-9.]+)"),
    "filter_space_used_bytes": stat(r"Bloom filter space used.*?:\s*([0-9,]+)", int),
    "filter_off_heap_bytes": stat(r"Bloom filter off heap memory used:\s*([0-9,]+)", int),
    # --- throughput ---
    "write_op_rate": stress(f"/results/{prefix}_write.log", r"Op rate\s*:\s*([0-9,]+)"),
    "read_op_rate": stress(f"/results/{prefix}_read.log", r"Op rate\s*:\s*([0-9,]+)"),
    "read_latency_mean_ms": stress(f"/results/{prefix}_read.log", r"Latency mean\s*:\s*([0-9.]+)"),
    "read_latency_p99_ms": stress(f"/results/{prefix}_read.log", r"Latency 99th percentile\s*:\s*([0-9.]+)"),
    # --- punteros a los logs crudos ---
    "logs": {
        "write": f"{prefix}_write.log",
        "read": f"{prefix}_read.log",
        "tablestats": f"{prefix}_tablestats.log",
        "server": f"{prefix}_server.log",
    },
}

with open(f"/results/{prefix}.json", "w") as f:
    json.dump(result, f, indent=2)
print(json.dumps(result, indent=2))
PY

kill "$CASSANDRA_PID" 2>/dev/null || true
wait "$CASSANDRA_PID" 2>/dev/null || true
echo "== Listo. Resultados en ${OUT} =="
