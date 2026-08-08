FROM eclipse-temurin:11-jdk AS build

RUN apt-get update && apt-get install -y --no-install-recommends \
    git ant ant-optional python3 procps \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
RUN git clone --branch cassandra-4.1.8 --depth 1 https://github.com/apache/cassandra.git

COPY patch/CuckooFilterCore.java patch/CuckooFilter.java \
     cassandra/src/java/org/apache/cassandra/utils/

WORKDIR /workspace/cassandra

# Parche 1/3 — FilterFactory: devolver CuckooFilter si la property lo pide.
# La rama va ANTES de reservar el bitset, para no filtrar memoria off-heap.
RUN F=src/java/org/apache/cassandra/utils/FilterFactory.java; \
    grep -q 'long numBits = (numElements \* bucketsPer) + BITSET_EXCESS;' "$F" || \
      (echo "ERROR: no se encontró el cuerpo esperado de createFilter en FilterFactory.java — ver README" && exit 1)

RUN F=src/java/org/apache/cassandra/utils/FilterFactory.java; \
    sed -i 's|        long numBits = (numElements \* bucketsPer) + BITSET_EXCESS;|        if ("cuckoo".equals(System.getProperty("cassandra.filter.impl", "bloom")))\n            return new CuckooFilter(numElements);\n        long numBits = (numElements * bucketsPer) + BITSET_EXCESS;|' "$F" && \
    grep -q 'return new CuckooFilter(numElements);' "$F" || \
      (echo "ERROR: el patch de FilterFactory.java no quedó aplicado" && exit 1)

# Parche 2/3 — BigTableWriter.flushBf(): no escribir FILTER si no es Bloom.
# Sin esto, el cast duro a BloomFilter revienta en el primer flush de SSTable.
RUN F=src/java/org/apache/cassandra/io/sstable/format/big/BigTableWriter.java; \
    grep -q 'BloomFilterSerializer.serialize((BloomFilter) bf, stream);' "$F" || \
      (echo "ERROR: no se encontró el cast duro a BloomFilter en BigTableWriter.java — ver README" && exit 1)

RUN F=src/java/org/apache/cassandra/io/sstable/format/big/BigTableWriter.java; \
    sed -i 's|            if (components.contains(Component.FILTER))|            if (components.contains(Component.FILTER) \&\& (bf instanceof BloomFilter))|' "$F" && \
    grep -q 'bf instanceof BloomFilter' "$F" || \
      (echo "ERROR: el patch de BigTableWriter.java no quedó aplicado" && exit 1)

# Parche 3/3 — SSTableReader.saveBloomFilter(): el segundo cast duro.
RUN F=src/java/org/apache/cassandra/io/sstable/format/SSTableReader.java; \
    grep -q 'BloomFilterSerializer.serialize((BloomFilter) filter, stream);' "$F" || \
      (echo "ERROR: no se encontró el cast duro a BloomFilter en SSTableReader.java — ver README" && exit 1)

RUN F=src/java/org/apache/cassandra/io/sstable/format/SSTableReader.java; \
    sed -i 's|    public static void saveBloomFilter(Descriptor descriptor, IFilter filter)\n    {|&|' "$F"; \
    sed -i 's|        File filterFile = new File(descriptor.filenameFor(Component.FILTER));|        if (!(filter instanceof BloomFilter))\n            return;\n        File filterFile = new File(descriptor.filenameFor(Component.FILTER));|' "$F" && \
    grep -q 'if (!(filter instanceof BloomFilter))' "$F" || \
      (echo "ERROR: el patch de SSTableReader.java no quedó aplicado" && exit 1)

RUN ant jar -Duse.jdk11=true

COPY run_benchmark.sh /workspace/run_benchmark.sh
RUN chmod +x /workspace/run_benchmark.sh

ENTRYPOINT ["/workspace/run_benchmark.sh"]
