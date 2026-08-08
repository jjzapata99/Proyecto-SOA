// Binary Fuse Filter de FastFilter/xor_singleheader
// (https://github.com/FastFilter/xor_singleheader, Apache 2.0).

#pragma once

#include <algorithm>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "rocksdb/filter_policy.h"
#include "rocksdb/slice.h"
#include "rocksdb/status.h"
// Header interno: FilterBitsBuilder/FilterBitsReader viven acá desde RocksDB 7.0.
#include "table/block_based/filter_policy_internal.h"

extern "C" {
#include "binaryfusefilter.h"
}

namespace rocksdb_research {

// Builder y reader TIENEN que hashear idéntico o el filtro daría falsos
// negativos; por eso el hash vive afuera de ambas clases. FNV-1a de 64 bits.
inline uint64_t HashSliceTo64(const ROCKSDB_NAMESPACE::Slice& key) {
  uint64_t h = 1469598103934665603ULL;
  for (size_t i = 0; i < key.size(); i++) {
    h ^= static_cast<unsigned char>(key.data()[i]);
    h *= 1099511628211ULL;
  }
  return h;
}

class BinaryFuseFilterBitsBuilder : public ROCKSDB_NAMESPACE::FilterBitsBuilder {
 public:
  void AddKey(const ROCKSDB_NAMESPACE::Slice& key) override {
    hashed_keys_.push_back(HashSliceTo64(key));
  }

  // RocksDB registra la llave y, como alternativa, su prefijo; se guardan ambas
  // y la deduplicación va en Finish().
  void AddKeyAndAlt(const ROCKSDB_NAMESPACE::Slice& key,
                    const ROCKSDB_NAMESPACE::Slice& alt) override {
    hashed_keys_.push_back(HashSliceTo64(key));
    hashed_keys_.push_back(HashSliceTo64(alt));
  }

  size_t EstimateEntriesAdded() override { return hashed_keys_.size(); }

  ROCKSDB_NAMESPACE::Slice Finish(std::unique_ptr<const char[]>* buf) override {
    return Finish(buf, nullptr);
  }

  ROCKSDB_NAMESPACE::Slice Finish(std::unique_ptr<const char[]>* buf,
                                  ROCKSDB_NAMESPACE::Status* status) override {
    if (status != nullptr) {
      *status = ROCKSDB_NAMESPACE::Status::OK();
    }

    // binary_fuse8_populate() falla si hay llaves repetidas, y RocksDB
    // explícitamente documenta que puede pasarlas repetidas.
    std::sort(hashed_keys_.begin(), hashed_keys_.end());
    hashed_keys_.erase(std::unique(hashed_keys_.begin(), hashed_keys_.end()),
                       hashed_keys_.end());

    if (hashed_keys_.empty()) {
      buf->reset(nullptr);
      return ROCKSDB_NAMESPACE::Slice();
    }

    binary_fuse8_t filter;
    if (!binary_fuse8_allocate(static_cast<uint32_t>(hashed_keys_.size()),
                               &filter)) {
      // Sin memoria suficiente: degradar a "no hay filtro" en vez de tronar.
      // RocksDB interpreta un filtro vacío como "todo podría estar" y deja pasar
      // todo, lo cual es seguro aunque poco útil.
      buf->reset(nullptr);
      hashed_keys_.clear();
      return ROCKSDB_NAMESPACE::Slice();
    }

    if (!binary_fuse8_populate(hashed_keys_.data(),
                               static_cast<uint32_t>(hashed_keys_.size()),
                               &filter)) {
      binary_fuse8_free(&filter);
      buf->reset(nullptr);
      hashed_keys_.clear();
      return ROCKSDB_NAMESPACE::Slice();
    }

    size_t size = binary_fuse8_serialization_bytes(&filter);
    char* data = new char[size];
    binary_fuse8_serialize(&filter, data);
    binary_fuse8_free(&filter);

    buf->reset(data);
    hashed_keys_.clear();
    return ROCKSDB_NAMESPACE::Slice(data, size);
  }

  // Binary Fuse8 ocupa ~9.1 bits por llave (~1.13 bytes), incluyendo el overhead
  // de la estructura.
  size_t ApproximateNumEntries(size_t bytes) override {
    return static_cast<size_t>(static_cast<double>(bytes) / 1.13);
  }

 private:
  std::vector<uint64_t> hashed_keys_;
};

class BinaryFuseFilterBitsReader : public ROCKSDB_NAMESPACE::FilterBitsReader {
 public:
  explicit BinaryFuseFilterBitsReader(const ROCKSDB_NAMESPACE::Slice& contents) {
    if (contents.size() > 0) {
      valid_ = binary_fuse8_deserialize(&filter_, contents.data());
    }
  }

  ~BinaryFuseFilterBitsReader() override {
    if (valid_) {
      binary_fuse8_free(&filter_);
    }
  }

  bool MayMatch(const ROCKSDB_NAMESPACE::Slice& key) override {
    // Filtro vacío o corrupto: responder que sí. Un filtro nunca puede decir "no
    // está" sin certeza — sería un falso negativo y RocksDB perdería datos.
    if (!valid_) return true;
    return binary_fuse8_contain(HashSliceTo64(key), &filter_);
  }

 private:
  binary_fuse8_t filter_;
  bool valid_ = false;
};

class BinaryFuseFilterPolicy : public ROCKSDB_NAMESPACE::FilterPolicy {
 public:
  const char* Name() const override { return "BinaryFuseFilterPolicy"; }

  // No pertenece a la familia read-compatible de Bloom/Ribbon: RocksDB indica
  // devolver Name() en ese caso.
  const char* CompatibilityName() const override { return Name(); }

  ROCKSDB_NAMESPACE::FilterBitsBuilder* GetBuilderWithContext(
      const ROCKSDB_NAMESPACE::FilterBuildingContext&) const override {
    return new BinaryFuseFilterBitsBuilder();
  }

  ROCKSDB_NAMESPACE::FilterBitsReader* GetFilterBitsReader(
      const ROCKSDB_NAMESPACE::Slice& contents) const override {
    return new BinaryFuseFilterBitsReader(contents);
  }
};

}  // namespace rocksdb_research
