#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <unordered_set>
#include <vector>

extern "C" {
#include "binaryfusefilter.h"
}

static int failures = 0;

static void check(const char* name, bool cond, const std::string& detail) {
  if (cond) {
    printf("  [OK]   %s%s%s\n", name, detail.empty() ? "" : " - ", detail.c_str());
  } else {
    printf("  [FAIL] %s - %s\n", name, detail.c_str());
    failures++;
  }
}

// El mismo hash que binary_fuse_filter_policy.h (FNV-1a de 64 bits).
static uint64_t HashTo64(const std::string& key) {
  uint64_t h = 1469598103934665603ULL;
  for (char c : key) {
    h ^= static_cast<unsigned char>(c);
    h *= 1099511628211ULL;
  }
  return h;
}

int main() {
  printf("== BinaryFuseRoundTripTest ==\n");

  const size_t N = 100000;

  // --- Construir el set de llaves, igual que haria el builder ---
  std::vector<uint64_t> hashes;
  std::vector<std::string> keys;
  for (size_t i = 0; i < N; i++) {
    std::string k = std::to_string(i);
    keys.push_back(k);
    hashes.push_back(HashTo64(k));
  }

  // Deduplicar, tal como hace Finish() (RocksDB permite llaves repetidas
  // y populate() falla con duplicados).
  std::sort(hashes.begin(), hashes.end());
  hashes.erase(std::unique(hashes.begin(), hashes.end()), hashes.end());
  check("hashes sin colisiones inesperadas", hashes.size() == N,
        "unicos=" + std::to_string(hashes.size()) + "/" + std::to_string(N));

  // --- Poblar ---
  binary_fuse8_t filter;
  check("allocate", binary_fuse8_allocate(static_cast<uint32_t>(hashes.size()), &filter), "");
  check("populate", binary_fuse8_populate(hashes.data(),
                                          static_cast<uint32_t>(hashes.size()), &filter), "");

  // --- Serializar ---
  size_t size = binary_fuse8_serialization_bytes(&filter);
  std::vector<char> buf(size);
  binary_fuse8_serialize(&filter, buf.data());
  double bits_per_key = (size * 8.0) / N;
  check("tamano razonable (~9 bits/llave)", bits_per_key > 8.0 && bits_per_key < 12.0,
        "bytes=" + std::to_string(size) + " bits/llave=" + std::to_string(bits_per_key));
  binary_fuse8_free(&filter);

  // --- Deserializar (LA parte que estaba mal en el wrapper original) ---
  binary_fuse8_t restored;
  bool ok = binary_fuse8_deserialize(&restored, buf.data());
  check("deserialize devuelve true", ok, "");

  // --- Cero falsos negativos tras el round-trip ---
  size_t false_negatives = 0;
  for (const auto& k : keys) {
    if (!binary_fuse8_contain(HashTo64(k), &restored)) false_negatives++;
  }
  check("cero falsos negativos tras round-trip", false_negatives == 0,
        "falsos negativos=" + std::to_string(false_negatives) + "/" + std::to_string(N));

  // --- Tasa de falsos positivos (~1/256 para fuse8) ---
  std::unordered_set<std::string> inserted(keys.begin(), keys.end());
  size_t probes = 0, fp = 0;
  for (size_t i = N; i < N + 200000; i++) {
    std::string k = std::to_string(i);
    if (inserted.count(k)) continue;
    probes++;
    if (binary_fuse8_contain(HashTo64(k), &restored)) fp++;
  }
  double rate = static_cast<double>(fp) / probes;
  char detail[128];
  snprintf(detail, sizeof(detail), "fpp=%.4f%% (%zu/%zu), teorica ~0.39%%",
           rate * 100, fp, probes);
  check("FPP cerca de la teorica de fuse8", rate < 0.01, detail);

  binary_fuse8_free(&restored);

  // --- Caso borde: filtro de una sola llave ---
  {
    std::vector<uint64_t> one{HashTo64("solo-una")};
    binary_fuse8_t f1;
    bool a = binary_fuse8_allocate(1, &f1);
    bool p = a && binary_fuse8_populate(one.data(), 1, &f1);
    check("filtro de 1 llave", p && binary_fuse8_contain(one[0], &f1), "");
    if (a) binary_fuse8_free(&f1);
  }

  printf(failures == 0 ? "\nTODOS OK\n" : "\n%d FALLOS\n", failures);
  return failures == 0 ? 0 : 1;
}
