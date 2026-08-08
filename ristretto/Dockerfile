FROM golang:1.25 AS build

RUN apt-get update && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
RUN git clone --branch v2.4.2 --depth 1 https://github.com/dgraph-io/ristretto.git ristretto-src
WORKDIR /workspace/ristretto-src
RUN git rev-parse HEAD > /workspace/RISTRETTO_COMMIT.txt

COPY count_sketch.go freq_sketch_iface.go \
     sketch_selector_baseline.go sketch_selector_countsketch.go ./

# Parche de policy.go: cambiar el tipo del campo `freq` y su constructor.
RUN grep -qE 'freq[[:space:]]+\*cmSketch' policy.go || \
      (echo "ERROR: no se encontró 'freq *cmSketch' en policy.go — edita a mano, ver README" && exit 1)
RUN sed -i -E 's/freq([[:space:]]+)\*cmSketch/freq\1freqSketch/' policy.go
RUN grep -qE 'newCmSketch\(' policy.go || \
      (echo "ERROR: no se encontró newCmSketch(...) en policy.go — edita a mano, ver README" && exit 1)
RUN sed -i -E 's/newCmSketch\(/newFreqSketch(/' policy.go
RUN gofmt -w policy.go

# Si `freq` siguiera siendo *cmSketch, la variante countsketch compilaría igual
# pero no reemplazaría nada.
RUN grep -q 'freq[[:space:]]*freqSketch' policy.go || \
      (echo "ERROR: el parche de policy.go no dejó 'freq freqSketch'" && exit 1)

COPY bench/main.go ./cmd/bench/main.go

# download y no tidy: los archivos agregados solo importan la stdlib, y tidy
# podría mover versiones sin necesidad.
RUN go mod download

RUN go vet ./cmd/bench
RUN go build -o /workspace/bin/bench-baseline ./cmd/bench
RUN go build -tags countsketch -o /workspace/bin/bench-countsketch ./cmd/bench

FROM debian:bookworm-slim AS runtime
COPY --from=build /workspace/bin/bench-baseline /usr/local/bin/bench-baseline
COPY --from=build /workspace/bin/bench-countsketch /usr/local/bin/bench-countsketch
COPY --from=build /workspace/RISTRETTO_COMMIT.txt /RISTRETTO_COMMIT.txt
COPY run_benchmark.sh /run_benchmark.sh
RUN chmod +x /run_benchmark.sh

ENTRYPOINT ["/run_benchmark.sh"]
