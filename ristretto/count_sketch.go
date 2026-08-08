package ristretto

import "math/bits"

// No lleva contador de envejecimiento propio: igual que el cmSketch original,
// el Reset() lo dispara tinyLFU.Increment en policy.go cada resetAt
// incrementos. Un reset interno además de ese sería una vía de envejecimiento
// que el baseline no tiene.
type countSketch struct {
	depth     int
	width     int
	widthMask uint64   // width - 1: permite hacer el módulo con un AND
	rows      [][]int8 // rows[fila][columna], contador con signo
	rowSeeds  []uint64 // una semilla por fila, para independizar los hashes
}

func newCountSketch(numCounters int64) *countSketch {
	// Mitad de columnas por fila que el cmSketch para gastar los mismos bytes:
	// aquel usa contadores de 4 bits, estos son int8.
	const depth = 4
	width := nextPow2(uint64(numCounters)) / 2
	if width == 0 {
		width = 1
	}

	rows := make([][]int8, depth)
	for i := range rows {
		rows[i] = make([]int8, width)
	}

	// Semillas fijas pero distintas por fila: distintas para que las filas sean
	// independientes, fijas para que dos corridas den el mismo resultado.
	seeds := make([]uint64, depth)
	seed := uint64(0x9E3779B97F4A7C15)
	for i := 0; i < depth; i++ {
		seed += 0x9E3779B97F4A7C15
		seeds[i] = seed
	}

	return &countSketch{
		depth:     depth,
		width:     int(width),
		widthMask: width - 1,
		rows:      rows,
		rowSeeds:  seeds,
	}
}

func (s *countSketch) Increment(hashed uint64) {
	for row := 0; row < s.depth; row++ {
		h := mix64(hashed, s.rowSeeds[row])
		bucket := h & s.widthMask // bits bajos del hash: la columna
		sign := int8(1)
		if (h>>32)&1 == 1 { // bit 32 del hash: el signo
			sign = -1
		}
		// Saturar en vez de desbordar: sobre 127, un +1 daría la vuelta a -128 y
		// una llave caliente pasaría a estimarse como fría.
		v := s.rows[row][bucket]
		if sign > 0 && v < 127 {
			s.rows[row][bucket] = v + 1
		} else if sign < 0 && v > -128 {
			s.rows[row][bucket] = v - 1
		}
	}
}

func (s *countSketch) Estimate(hashed uint64) int64 {
	// El signo se recalcula igual que en Increment, así se lee con el mismo con
	// el que se sumó.
	estimates := make([]int64, s.depth)
	for row := 0; row < s.depth; row++ {
		h := mix64(hashed, s.rowSeeds[row])
		bucket := h & s.widthMask
		sign := int64(1)
		if (h>>32)&1 == 1 {
			sign = -1
		}
		estimates[row] = sign * int64(s.rows[row][bucket])
	}
	est := median(estimates)
	// La cancelación puede dar mediana negativa, que como frecuencia no significa nada.
	if est < 0 {
		return 0
	}
	return est
}

// Envejecimiento: divide todo por 2 para que lo reciente pese más que lo viejo.
func (s *countSketch) Reset() {
	for row := 0; row < s.depth; row++ {
		for col := range s.rows[row] {
			s.rows[row][col] /= 2
		}
	}
}

func (s *countSketch) Clear() {
	for row := 0; row < s.depth; row++ {
		for col := range s.rows[row] {
			s.rows[row][col] = 0
		}
	}
}

func median(vals []int64) int64 {
	// depth es 4: un insertion sort sobre una copia es más rápido que sort.Slice,
	// que pasa por reflexión e interfaces.
	cp := make([]int64, len(vals))
	copy(cp, vals)
	for i := 1; i < len(cp); i++ {
		for j := i; j > 0 && cp[j-1] > cp[j]; j-- {
			cp[j-1], cp[j] = cp[j], cp[j-1]
		}
	}
	n := len(cp)
	if n%2 == 1 {
		return cp[n/2]
	}
	return (cp[n/2-1] + cp[n/2]) / 2
}

// splitmix64: reparte los bits de entrada por los 64 de salida.
func mix64(item, seed uint64) uint64 {
	z := item + seed
	z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9
	z = (z ^ (z >> 27)) * 0x94D049BB133111EB
	return z ^ (z >> 31)
}

func nextPow2(v uint64) uint64 {
	if v == 0 {
		return 0
	}
	if v&(v-1) == 0 { // ya es potencia de 2
		return v
	}
	return 1 << bits.Len64(v)
}
