FROM ubuntu:22.04
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y \
    build-essential \
    librocksdb-dev \
    libsnappy-dev \
    libz-dev \
    libbz2-dev \
    libzstd-dev \
    liblz4-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY main.cpp .
RUN g++ -O3 -fno-rtti main.cpp -o rocksdb_bench -lrocksdb -lpthread -lsnappy -lz -lbz2 -lzstd -llz4

ENTRYPOINT ["./rocksdb_bench"]
