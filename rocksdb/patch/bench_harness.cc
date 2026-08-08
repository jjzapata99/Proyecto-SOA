#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <random>
#include <string>
#include <unordered_set>
#include <vector>

#include "rocksdb/db.h"
#include "rocksdb/filter_policy.h"
#include "rocksdb/options.h"
#include "rocksdb/statistics.h"
#include "rocksdb/table.h"
#include "rocksdb/table_properties.h"

#ifdef USE_BINARY_FUSE_FILTER
#include "binary_fuse_filter_policy.h"
#endif

namespace {

std::vector<std::string> LoadTrace(const std::string& path) {
  std::vector<std::string> keys;
  std::ifstream f(path);
  std::string line;
  while (std::getline(f, line)) {
    if (!line.empty() && line.back() == '\r') line.pop_back();  // traza de Windows
    if (!line.empty()) keys.push_back(line);
  }
  return keys;
}

std::string Getenv(const char* name, const std::string& def) {
  const char* v = std::getenv(name);
  return v ? std::string(v) : def;
}

}  // namespace

int main() {
  const std::string trace_file = Getenv("TRACE_FILE", "/traces/zipfian_1e6.trace");
  const std::string output_prefix = Getenv("OUTPUT_PREFIX", "baseline");
  const std::string filter_variant = Getenv("FILTER_VARIANT", "bloom");
  const std::string db_path = "/tmp/rocksdb-bench-" + output_prefix;

  std::vector<std::string> trace = LoadTrace(trace_file);
  if (trace.empty()) {
    std::cerr << "traza vacía o no encontrada: " << trace_file << std::endl;
    return 1;
  }

  // La traza tiene repeticiones (es zipfiana); para llenar la DB solo
  // necesitamos las llaves distintas.
  std::vector<std::string> unique_keys;
  {
    std::unordered_set<std::string> seen;
    for (const auto& k : trace) {
      if (seen.insert(k).second) unique_keys.push_back(k);
    }
  }

  rocksdb::DestroyDB(db_path, rocksdb::Options());

  rocksdb::Options options;
  options.create_if_missing = true;
  options.statistics = rocksdb::CreateDBStatistics();

  rocksdb::BlockBasedTableOptions table_options;
  table_options.whole_key_filtering = true;
  if (filter_variant == "binaryfuse") {
#ifdef USE_BINARY_FUSE_FILTER
    table_options.filter_policy.reset(
        new rocksdb_research::BinaryFuseFilterPolicy());
#else
    std::cerr << "Binario compilado sin USE_BINARY_FUSE_FILTER" << std::endl;
    return 1;
#endif
  } else {
    // 10 bits/llave: el default histórico que documenta RocksDB.
    table_options.filter_policy.reset(rocksdb::NewBloomFilterPolicy(10));
  }
  options.table_factory.reset(rocksdb::NewBlockBasedTableFactory(table_options));

  rocksdb::DB* db = nullptr;
  rocksdb::Status status = rocksdb::DB::Open(options, db_path, &db);
  if (!status.ok()) {
    std::cerr << "Error abriendo DB: " << status.ToString() << std::endl;
    return 1;
  }

  // --- Fase de carga: insertamos las llaves distintas de la traza ---
  rocksdb::WriteOptions wo;
  for (const auto& k : unique_keys) {
    db->Put(wo, k, "v");
  }
  db->Flush(rocksdb::FlushOptions());  // fuerza a crear el/los SST + filtro
  // Compactar deja todo en SSTs (no en memtable), que es donde el filtro
  // efectivamente se consulta.
  db->CompactRange(rocksdb::CompactRangeOptions(), nullptr, nullptr);

  // Tamaño real del filtro. filter_size de TableProperties es lo correcto:
  // "estimate-table-readers-mem" incluiría también los índices.
  uint64_t filter_size = 0, num_filter_entries = 0, num_sst = 0;
  {
    rocksdb::TablePropertiesCollection props;
    if (db->GetPropertiesOfAllTables(&props).ok()) {
      for (const auto& kv : props) {
        filter_size += kv.second->filter_size;
        num_filter_entries += kv.second->num_filter_entries;
        num_sst++;
      }
    }
  }
  if (filter_size == 0) {
    std::cerr << "ERROR: filter_size = 0 — el FilterPolicy no llegó a "
                 "construir ningún filtro. El experimento no mediría nada."
              << std::endl;
    delete db;
    return 1;
  }

  // --- Fase de lectura: mezcla de llaves reales (hits) y llaves que NO
  // existen (misses reales), 50/50, para que el filtro tenga oportunidad
  // real de evitar lecturas de disco en la mitad de los casos ---
  std::mt19937_64 rng(42);
  std::vector<std::string> lookups;
  lookups.reserve(trace.size() * 2);
  for (const auto& k : trace) {
    lookups.push_back(k);                    // hit real
    lookups.push_back(k + "__no_existe__");  // miss real
  }
  std::shuffle(lookups.begin(), lookups.end(), rng);

  auto start = std::chrono::steady_clock::now();
  std::string value;
  int64_t found = 0, not_found = 0;
  for (const auto& k : lookups) {
    rocksdb::Status s = db->Get(rocksdb::ReadOptions(), k, &value);
    if (s.ok()) {
      found++;
    } else {
      not_found++;
    }
  }
  auto end = std::chrono::steady_clock::now();
  double elapsed_s = std::chrono::duration<double>(end - start).count();

  auto stats = options.statistics;
  // Aunque digan "BLOOM_", estos tickers los incrementa la capa genérica de
  // filtros, así que también cuentan para el Binary Fuse.
  // Veces que el filtro dijo "no está" y evitó leer el bloque de datos.
  uint64_t filter_useful = stats->getTickerCount(rocksdb::BLOOM_FILTER_USEFUL);
  // Veces que el filtro dijo "podría estar".
  uint64_t filter_positive = stats->getTickerCount(rocksdb::BLOOM_FILTER_FULL_POSITIVE);
  // ...de las cuales, las que además resultaron estar de verdad.
  uint64_t filter_true_positive =
      stats->getTickerCount(rocksdb::BLOOM_FILTER_FULL_TRUE_POSITIVE);

  if (filter_useful == 0 && filter_positive == 0) {
    std::cerr << "ERROR: los contadores del filtro quedaron en cero — el "
                 "filtro nunca se consultó durante los lookups."
              << std::endl;
    delete db;
    return 1;
  }

  // Falsos positivos: dijo "podría estar" pero no estaba.
  uint64_t false_positives =
      filter_positive > filter_true_positive ? filter_positive - filter_true_positive : 0;
  // Denominador correcto: consultas por llaves que realmente NO estaban.
  uint64_t true_negatives_probed = filter_useful + false_positives;
  double fpp = true_negatives_probed > 0
                   ? static_cast<double>(false_positives) / true_negatives_probed
                   : 0.0;
  double bits_per_key = num_filter_entries > 0
                            ? (filter_size * 8.0) / num_filter_entries
                            : 0.0;

  std::ofstream out("/results/" + output_prefix + ".json");
  out << "{\n"
      << "  \"variant\": \"" << filter_variant << "\",\n"
      << "  \"filter_policy\": \""
      << (filter_variant == "binaryfuse" ? "BinaryFuseFilterPolicy"
                                         : "BloomFilterPolicy(10 bits/key)")
      << "\",\n"
      << "  \"num_keys_loaded\": " << unique_keys.size() << ",\n"
      << "  \"num_sst_files\": " << num_sst << ",\n"
      << "  \"num_lookups\": " << lookups.size() << ",\n"
      << "  \"found\": " << found << ",\n"
      << "  \"not_found\": " << not_found << ",\n"
      << "  \"elapsed_seconds\": " << elapsed_s << ",\n"
      << "  \"lookups_per_second\": " << (lookups.size() / elapsed_s) << ",\n"
      << "  \"filter_size_bytes\": " << filter_size << ",\n"
      << "  \"num_filter_entries\": " << num_filter_entries << ",\n"
      << "  \"filter_bits_per_key\": " << bits_per_key << ",\n"
      << "  \"filter_useful\": " << filter_useful << ",\n"
      << "  \"filter_full_positive\": " << filter_positive << ",\n"
      << "  \"filter_full_true_positive\": " << filter_true_positive << ",\n"
      << "  \"filter_false_positives\": " << false_positives << ",\n"
      << "  \"filter_false_positive_rate\": " << fpp << "\n"
      << "}\n";
  out.close();

  std::cout << "Listo. filtro=" << filter_size << " bytes ("
            << bits_per_key << " bits/llave), fpp=" << fpp
            << ", lookups/s=" << (lookups.size() / elapsed_s) << std::endl;
  std::cout << "Resultados en /results/" << output_prefix << ".json" << std::endl;

  delete db;
  return 0;
}
