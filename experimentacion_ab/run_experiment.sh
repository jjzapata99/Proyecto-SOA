#!/usr/bin/env bash
set -e

mkdir -p resultados

limpiar_cache_os() {
    echo ">> Limpiando caché del sistema operativo..."
    sync || true
    if echo 3 | sudo tee /proc/sys/vm/drop_caches > /dev/null 2>&1; then
        echo "   (Caché de memoria vaciada exitosamente)"
    else
        echo "   (Nota: WSL2/kernel restringe modificar drop_caches; se continúa con sync del buffer de disco)"
    fi
}

echo "=========================================================="
echo "   INICIANDO SUITE DE EXPERIMENTACIÓN A/B (256MB RAM)     "
echo "=========================================================="

# ---------------------------------------------------------
# 1. REDIS
# ---------------------------------------------------------
echo -e "\n--- [Proyecto 1: Redis Baseline] ---"
limpiar_cache_os
docker compose --profile baseline --profile modificado --profile redis down --remove-orphans 2>/dev/null || true
docker compose --profile baseline up -d redis-baseline
sleep 3
echo "Inyectando 1,000,000 operaciones BF.ADD en Redis Baseline..."
docker exec redis-baseline redis-benchmark -p 6379 -n 1000000 -r 1000000 -P 50 BF.ADD bloom_bench __rand_int__ > resultados/redis_baseline_bench.txt
docker exec redis-baseline redis-cli INFO MEMORY > resultados/redis_baseline_mem.txt
docker compose --profile baseline --profile redis down --remove-orphans

echo -e "\n--- [Proyecto 1: Redis Modificado] ---"
limpiar_cache_os
docker compose --profile modificado up -d redis-modificado
sleep 3
echo "Inyectando 1,000,000 operaciones MF.ADD en Redis Modificado..."
docker exec redis-modificado redis-benchmark -p 6379 -n 1000000 -r 1000000 -P 50 MF.ADD mod_bench __rand_int__ > resultados/redis_modificado_bench.txt
docker exec redis-modificado redis-cli INFO MEMORY > resultados/redis_modificado_mem.txt
docker compose --profile modificado --profile redis down --remove-orphans

# ---------------------------------------------------------
# 2. ROCKSDB
# ---------------------------------------------------------
echo -e "\n--- [Proyecto 2: RocksDB Baseline] ---"
limpiar_cache_os
docker compose --profile baseline run --rm rocksdb-baseline | tee resultados/rocksdb_baseline.txt

echo -e "\n--- [Proyecto 2: RocksDB Modificado] ---"
limpiar_cache_os
docker compose --profile modificado run --rm rocksdb-modificado | tee resultados/rocksdb_modificado.txt

# ---------------------------------------------------------
# 3. CAFFEINE
# ---------------------------------------------------------
echo -e "\n--- [Proyecto 3: Caffeine Baseline] ---"
limpiar_cache_os
docker compose --profile baseline run --rm caffeine-baseline | tee resultados/caffeine_baseline.txt

echo -e "\n--- [Proyecto 3: Caffeine Modificado] ---"
limpiar_cache_os
docker compose --profile modificado run --rm caffeine-modificado | tee resultados/caffeine_modificado.txt

echo -e "\n=========================================================="
echo " Experimentos finalizados. Generando reporte..."
echo "=========================================================="

python3 generar_reporte.py || true
