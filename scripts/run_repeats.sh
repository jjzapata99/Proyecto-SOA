#!/usr/bin/env bash
# Uso:  ./scripts/run_repeats.sh [N] [proyecto...]
#
# Corre N veces cada variante y deja los resultados en
# results/<proyecto>/repeats/, sin pisar los de run_all.sh. Sirve para las
# métricas que varían entre corridas (hit ratio de Ristretto, throughput de
# RocksDB y Cassandra); resumí con scripts/summarize_repeats.py.
set -euo pipefail

cd "$(dirname "$0")/.."

N="${1:-3}"
if ! [[ "$N" =~ ^[0-9]+$ ]]; then
  echo "El primer argumento es el número de repeticiones. Uso: $0 [N] [proyecto...]" >&2
  exit 1
fi
shift || true

ALL_PROJECTS=(caffeine ristretto rocksdb cassandra)
PROJECTS=("${@:-${ALL_PROJECTS[@]}}")

declare -A VARIANTS=(
  [caffeine]="baseline:caffeine-baseline countsketch:caffeine-countsketch"
  [ristretto]="baseline:ristretto-baseline countsketch:ristretto-countsketch"
  [rocksdb]="baseline:rocksdb-baseline binaryfuse:rocksdb-binaryfuse"
  [cassandra]="baseline:cassandra-baseline cuckoo:cassandra-cuckoo"
)

FAILED=()

# En serie, igual que run_all.sh: el throughput es una de las metricas medidas.
for project in "${PROJECTS[@]}"; do
  mkdir -p "results/$project/repeats"
  for i in $(seq 1 "$N"); do
    for pair in ${VARIANTS[$project]}; do
      name="${pair%%:*}"
      service="${pair##*:}"
      echo "== $service — repetición $i/$N"
      # Prefijo PLANO, sin subcarpeta: los harnesses de RocksDB y Cassandra usan
      # OUTPUT_PREFIX tambien para rutas internas (el directorio de la DB, los
      # logs del server), asi que un prefijo con '/' apunta a un directorio que
      # no existe y la corrida muere. Se mueven despues.
      if docker compose run --rm -e OUTPUT_PREFIX="rep_${name}_${i}" "$service" >/dev/null 2>&1; then
        mv "results/$project"/rep_"${name}_${i}"* "results/$project/repeats/" 2>/dev/null || true
      else
        echo "!!! FALLÓ: $service (repetición $i)"
        FAILED+=("$service#$i")
      fi
    done
  done
done

echo
if [ "${#FAILED[@]}" -gt 0 ]; then
  echo "Repeticiones fallidas: ${FAILED[*]}"
  exit 1
fi

echo "Listo. Resumen:"
python3 scripts/summarize_repeats.py "${PROJECTS[@]}"
