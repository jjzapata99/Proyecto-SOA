FROM debian:bookworm AS build

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential git libgflags-dev libsnappy-dev zlib1g-dev \
    libbz2-dev liblz4-dev libzstd-dev ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
RUN git clone --branch v9.7.4 --depth 1 https://github.com/facebook/rocksdb.git
RUN git clone --depth 1 https://github.com/FastFilter/xor_singleheader.git

WORKDIR /workspace/rocksdb
# DISABLE_WARNING_AS_ERROR=1: GCC 12 reporta un falso positivo de
# -Wmaybe-uninitialized dentro de <immintrin.h> y el Makefile usa -Werror.
# PORTABLE=1: evita -march=native, para que la imagen sirva en cualquier CPU.
RUN make -j"$(nproc)" DISABLE_WARNING_AS_ERROR=1 PORTABLE=1 static_lib

WORKDIR /workspace
COPY patch/binary_fuse_filter_policy.h patch/bench_harness.cc ./

# Si RocksDB mueve estos headers internos otra vez, fallar acá con un mensaje
# claro y no con 200 líneas de errores de plantillas.
RUN test -f rocksdb/table/block_based/filter_policy_internal.h || \
      (echo "ERROR: no está table/block_based/filter_policy_internal.h en este tag de RocksDB — ver README" && exit 1)
RUN grep -q "ApproximateNumEntries" rocksdb/table/block_based/filter_policy_internal.h || \
      (echo "ERROR: FilterBitsBuilder cambió de API (falta ApproximateNumEntries) — ver README" && exit 1)

# -fno-rtti es obligatorio: librocksdb.a se compila sin RTTI, así que no
# contiene el typeinfo de sus clases y el enlazado fallaría con undefined
# reference. Este código no usa dynamic_cast ni typeid.
ENV ROCKSDB_CXXFLAGS="-std=c++17 -O2 -fno-rtti -DROCKSDB_PLATFORM_POSIX -DOS_LINUX -DROCKSDB_LIB_IO_POSIX -DSNAPPY -DZLIB -DBZIP2 -DLZ4 -DZSTD"

# Baseline: solo depende de los headers públicos de RocksDB
RUN g++ ${ROCKSDB_CXXFLAGS} \
      -I rocksdb/include \
      bench_harness.cc rocksdb/librocksdb.a \
      -lpthread -lsnappy -lz -lbz2 -llz4 -lzstd -ldl \
      -o bin-baseline

# Necesita además la raíz del árbol de RocksDB, por el header interno.
RUN g++ ${ROCKSDB_CXXFLAGS} -DUSE_BINARY_FUSE_FILTER \
      -I rocksdb/include -I rocksdb -I xor_singleheader/include \
      bench_harness.cc rocksdb/librocksdb.a \
      -lpthread -lsnappy -lz -lbz2 -llz4 -lzstd -ldl \
      -o bin-binaryfuse

FROM debian:bookworm-slim AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends \
    libsnappy1v5 zlib1g libbz2-1.0 liblz4-1 libzstd1 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/bin-baseline /usr/local/bin/bin-baseline
COPY --from=build /workspace/bin-binaryfuse /usr/local/bin/bin-binaryfuse
COPY run_benchmark.sh /run_benchmark.sh
RUN chmod +x /run_benchmark.sh

ENTRYPOINT ["/run_benchmark.sh"]
