#include <iostream>
#include <string>
#include <vector>
#include <chrono>
#include <fstream>
#include <unistd.h>
#include <rocksdb/db.h>
#include <rocksdb/filter_policy.h>
#include <rocksdb/slice_transform.h>
#include <rocksdb/table.h>

using namespace std;
using namespace std::chrono;

class DummyFilterPolicy : public rocksdb::FilterPolicy {
public:
    const char* Name() const override { return "DummyFilterPolicy"; }
    void CreateFilter(const rocksdb::Slice* keys, int n, std::string* dst) const override {
        dst->append(1, '1');
    }
    bool KeyMayMatch(const rocksdb::Slice& key, const rocksdb::Slice& filter) const override {
        return true;
    }
};

long get_rss_kb() {
    long rss = 0;
    ifstream statm("/proc/self/statm");
    if (statm >> rss >> rss) {
        long page_size_kb = sysconf(_SC_PAGESIZE) / 1024;
        return rss * page_size_kb;
    }
    return 0;
}

int main(int argc, char** argv) {
    if (argc < 2) {
        cerr << "Uso: " << argv[0] << " [--baseline | --modificado]" << endl;
        return 1;
    }

    string modo = argv[1];
    rocksdb::DB* db;
    rocksdb::Options options;
    options.create_if_missing = true;
    
    rocksdb::BlockBasedTableOptions table_options;
    if (modo == "--baseline") {
        table_options.filter_policy.reset(rocksdb::NewBloomFilterPolicy(10, false));
        options.table_factory.reset(rocksdb::NewBlockBasedTableFactory(table_options));
        cout << "[RocksDB Baseline] Configurado con BloomFilterPolicy original." << endl;
    } else if (modo == "--modificado") {
        table_options.filter_policy.reset(new DummyFilterPolicy());
        options.table_factory.reset(rocksdb::NewBlockBasedTableFactory(table_options));
        cout << "[RocksDB Modificado] Configurado con DummyFilterPolicy." << endl;
    } else {
        cerr << "Modo no reconocido: " << modo << endl;
        return 1;
    }

    string db_path = "/tmp/rocksdb_test_" + modo;
    rocksdb::Status status = rocksdb::DB::Open(options, db_path, &db);
    if (!status.ok()) {
        cerr << "Error abriendo DB: " << status.ToString() << endl;
        return 1;
    }

    const int TOTAL_KEYS = 1000000;
    cout << "Insertando " << TOTAL_KEYS << " pares clave-valor aleatorios..." << endl;

    auto start = high_resolution_clock::now();
    for (int i = 0; i < TOTAL_KEYS; ++i) {
        string key = "key_" + to_string(rand() % TOTAL_KEYS);
        string val = "val_" + to_string(i);
        db->Put(rocksdb::WriteOptions(), key, val);
    }
    auto end = high_resolution_clock::now();

    auto duration = duration_cast<milliseconds>(end - start).count();
    long rss_kb = get_rss_kb();

    cout << "-------------------------------------------" << endl;
    cout << "Tiempo de inserción : " << duration << " ms" << endl;
    cout << "Throughput          : " << (TOTAL_KEYS * 1000 / (duration > 0 ? duration : 1)) << " ops/s" << endl;
    cout << "Memoria RSS consumida: " << rss_kb << " KB (" << (rss_kb / 1024.0) << " MB)" << endl;
    cout << "-------------------------------------------" << endl;

    delete db;
    return 0;
}
