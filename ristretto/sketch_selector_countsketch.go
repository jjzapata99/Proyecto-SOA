//go:build countsketch

package ristretto

func newFreqSketch(numCounters int64) freqSketch {
	return newCountSketch(numCounters)
}

// Va al JSON de resultados: si el build tag no tuvo efecto, se ve ahí.
func FreqSketchName() string { return "count-sketch (reemplazo)" }

// countSketch: 4 filas de next2Power(numCounters)/2 contadores int8. Mismos
// bytes que el cmSketch, mitad de contadores, rango ±127 en vez de 0..15.
func FreqSketchGeometry(numCounters int64) (bytes, counters, counterMax int64) {
	w := int64(nextPow2(uint64(numCounters)) / 2)
	if w == 0 {
		w = 1
	}
	return 4 * w, 4 * w, 127
}
