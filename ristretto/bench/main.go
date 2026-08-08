package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"strconv"

	ristretto "github.com/dgraph-io/ristretto/v2"
)

func main() {
	traceFile := getenv("TRACE_FILE", "/traces/zipfian_1e6.trace")
	outputPrefix := getenv("OUTPUT_PREFIX", "baseline")
	variant := getenv("SKETCH_VARIANT", "baseline")

	// Capacidad de la caché en número de items (cada item cuesta 1).
	maxCost := getenvInt("CACHE_SIZE", 10_000)
	// Regla general de Ristretto: ~10x la capacidad esperada de la caché.
	numCounters := getenvInt("NUM_COUNTERS", maxCost*10)

	cache, err := ristretto.NewCache(&ristretto.Config[string, int64]{
		NumCounters: numCounters,
		MaxCost:     maxCost,
		BufferItems: 64,
		Metrics:     true, // imprescindible: sin esto cache.Metrics queda nil
		// Sin esto Ristretto le suma a cada item ~56 bytes de su estructura
		// interna, y un MaxCost de 10000 daría una caché de ~175 items.
		IgnoreInternalCost: true,
	})
	if err != nil {
		panic(fmt.Sprintf("no se pudo crear la cache: %v", err))
	}
	defer cache.Close()

	f, err := os.Open(traceFile)
	if err != nil {
		panic(fmt.Sprintf("no se pudo abrir la traza %s: %v", traceFile, err))
	}
	defer f.Close()

	distinct := make(map[string]struct{})
	scanner := bufio.NewScanner(f)
	var ops int64
	for scanner.Scan() {
		key := scanner.Text()
		if key == "" {
			continue
		}
		distinct[key] = struct{}{}
		if _, found := cache.Get(key); !found {
			cache.Set(key, 1, 1)
		}
		ops++
	}
	if err := scanner.Err(); err != nil {
		panic(fmt.Sprintf("error leyendo la traza: %v", err))
	}
	cache.Wait() // procesa el buffer pendiente antes de leer métricas

	m := cache.Metrics
	if m == nil {
		panic("cache.Metrics es nil — Config.Metrics quedó en false")
	}

	// Va al JSON para poder verificar que ambas variantes gastan la misma
	// memoria y para dejar a la vista la diferencia de rango (15 vs 127).
	sketchBytes, sketchCounters, sketchCounterMax := ristretto.FreqSketchGeometry(numCounters)

	result := map[string]any{
		"variant":            variant,
		"sketch":             ristretto.FreqSketchName(), // lo define el build tag
		"sketch_bytes":       sketchBytes,
		"sketch_counters":    sketchCounters,
		"sketch_counter_max": sketchCounterMax,
		"ops":                ops,
		"distinct_keys":      len(distinct),
		"cache_size":         maxCost,
		"num_counters":       numCounters,
		"hits":          m.Hits(),
		"misses":        m.Misses(),
		"ratio":         m.Ratio(),
		"keys_added":    m.KeysAdded(),
		"keys_evicted":  m.KeysEvicted(),
		"cost_evicted":  m.CostEvicted(),
	}

	out, _ := json.MarshalIndent(result, "", "  ")
	if err := os.WriteFile(fmt.Sprintf("/results/%s.json", outputPrefix), out, 0o644); err != nil {
		panic(err)
	}
	fmt.Println(string(out))
}

func getenv(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func getenvInt(key string, def int64) int64 {
	if v := os.Getenv(key); v != "" {
		n, err := strconv.ParseInt(v, 10, 64)
		if err != nil {
			panic(fmt.Sprintf("%s no es un entero valido: %q", key, v))
		}
		return n
	}
	return def
}
