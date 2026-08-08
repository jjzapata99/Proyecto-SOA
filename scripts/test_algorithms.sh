#!/usr/bin/env bash
# Uso:  ./scripts/test_algorithms.sh [countsketch-java|cuckoo|countsketch-go|binaryfuse|all]
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"
WHICH="${1:-all}"
FAILED=0

run() { echo; echo "############ $1 ############"; }

# 1. CountSketch (Java) y CuckooFilterCore
if [ "$WHICH" = "all" ] || [ "$WHICH" = "countsketch-java" ] || [ "$WHICH" = "cuckoo" ]; then
  run "CountSketch (Java) + CuckooFilterCore — via JDK en Docker"
  docker run --rm -v "$ROOT:/src:ro" -w /work eclipse-temurin:21-jdk bash -c '
    set -e
    mkdir -p src/com/github/benmanes/caffeine/cache/simulator/admission/countsketch
    mkdir -p src/org/apache/cassandra/utils
    cp /src/caffeine/patch/CountSketch.java src/com/github/benmanes/caffeine/cache/simulator/admission/countsketch/
    cp /src/cassandra/patch/CuckooFilterCore.java src/org/apache/cassandra/utils/
    cp /src/scripts/java_tests/*.java src/
    javac -d out $(find src -name "*.java")
    echo; java -cp out CountSketchTest
    echo; java -Xmx3g -cp out CuckooFilterCoreTest
  ' || FAILED=1
fi

# 2. Count-Sketch (Go) — contra el paquete real de Ristretto
if [ "$WHICH" = "all" ] || [ "$WHICH" = "countsketch-go" ]; then
  run "Count-Sketch (Go) — contra ristretto v2.4.2 real"
  docker build -q -t caches-research-gotest -f - ./ristretto <<'EOF' >/dev/null
FROM golang:1.25
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
RUN git clone --branch v2.4.2 --depth 1 https://github.com/dgraph-io/ristretto.git src
WORKDIR /workspace/src
COPY count_sketch.go freq_sketch_iface.go sketch_selector_baseline.go sketch_selector_countsketch.go count_sketch_test.go ./
RUN go mod download
CMD ["go", "test", "-v", "-run", "TestCountSketch|TestSketchMemory", "."]
EOF
  docker run --rm caches-research-gotest || FAILED=1
fi

# 3. Binary Fuse Filter — round-trip serializar/deserializar
if [ "$WHICH" = "all" ] || [ "$WHICH" = "binaryfuse" ]; then
  run "Binary Fuse Filter — round-trip serializar/deserializar"
  docker build -q -t caches-research-fusetest -f - ./scripts <<'EOF' >/dev/null
FROM debian:bookworm
RUN apt-get update && apt-get install -y --no-install-recommends g++ git ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /w
RUN git clone --depth 1 https://github.com/FastFilter/xor_singleheader.git
COPY binary_fuse_roundtrip.cc .
RUN g++ -std=c++17 -O2 -I xor_singleheader/include binary_fuse_roundtrip.cc -o test
CMD ["./test"]
EOF
  docker run --rm caches-research-fusetest || FAILED=1
fi

echo
if [ "$FAILED" -eq 0 ]; then
  echo "===== TODOS LOS TESTS DE ALGORITMOS PASARON ====="
else
  echo "===== HUBO TESTS FALLIDOS ====="
fi
exit "$FAILED"
