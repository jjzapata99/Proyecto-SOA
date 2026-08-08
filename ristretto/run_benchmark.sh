#!/bin/bash
set -euo pipefail

VARIANT="${SKETCH_VARIANT:-baseline}"

echo "== Ristretto bench — variante: ${VARIANT} =="
echo "commit de ristretto usado: $(cat /RISTRETTO_COMMIT.txt)"

if [ "$VARIANT" = "countsketch" ]; then
  exec /usr/local/bin/bench-countsketch
else
  exec /usr/local/bin/bench-baseline
fi
