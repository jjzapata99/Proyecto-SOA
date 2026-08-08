#!/usr/bin/env bash
# Uso:  ./scripts/run_all.sh [proyecto...]
set -euo pipefail

cd "$(dirname "$0")/.."

TRACE="traces/zipfian_1e6.trace"
if [ ! -s "$TRACE" ]; then
  echo "Generando la traza (no existía)..."
  python3 traces/generate_zipfian.py --out "$TRACE"
fi

ALL_PROJECTS=(caffeine ristretto rocksdb cassandra)
PROJECTS=("${@:-${ALL_PROJECTS[@]}}")

declare -A VARIANTS=(
  [caffeine]="caffeine-baseline caffeine-countsketch"
  [ristretto]="ristretto-baseline ristretto-countsketch"
  [rocksdb]="rocksdb-baseline rocksdb-binaryfuse"
  [cassandra]="cassandra-baseline cassandra-cuckoo"
)

FAILED=()

# En serie, no en paralelo: el throughput es una de las metricas medidas, asi
# que contencion de CPU haria esos numeros incomparables entre variantes.
for project in "${PROJECTS[@]}"; do
  for service in ${VARIANTS[$project]}; do
    echo
    echo "======================================================"
    echo "  $service"
    echo "======================================================"
    if ! docker compose run --rm "$service"; then
      echo "!!! FALLÓ: $service"
      FAILED+=("$service")
    fi
  done
done

echo
echo "======================================================"
echo "  Resumen"
echo "======================================================"
find results -name "*.json" -type f | sort | while read -r f; do
  printf '  %s\n' "$f"
done

if [ "${#FAILED[@]}" -gt 0 ]; then
  echo
  echo "Variantes fallidas: ${FAILED[*]}"
  exit 1
fi

echo
echo "Todas las variantes corrieron. Comparación rápida:"
python3 scripts/compare_results.py 2>/dev/null || true
