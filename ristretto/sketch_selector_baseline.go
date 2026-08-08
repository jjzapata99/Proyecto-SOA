//go:build !countsketch

package ristretto

func newFreqSketch(numCounters int64) freqSketch {
	return newCmSketch(numCounters)
}

// Va al JSON de resultados: si el build tag no tuvo efecto, se ve ahí.
func FreqSketchName() string { return "count-min-sketch (original)" }

// cmSketch: 4 filas de next2Power(numCounters) contadores de 4 bits.
func FreqSketchGeometry(numCounters int64) (bytes, counters, counterMax int64) {
	n := next2Power(numCounters)
	return 2 * n, 4 * n, 15
}
