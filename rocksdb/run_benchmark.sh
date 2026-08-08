#!/bin/bash
set -euo pipefail

VARIANT="${FILTER_VARIANT:-bloom}"
echo "== RocksDB bench — filtro: ${VARIANT} =="

if [ "$VARIANT" = "binaryfuse" ]; then
  exec /usr/local/bin/bin-binaryfuse
else
  exec /usr/local/bin/bin-baseline
fi
