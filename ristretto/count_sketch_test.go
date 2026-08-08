package ristretto

import (
	"fmt"
	"math/rand"
	"sort"
	"testing"
)

func TestCountSketchExactSingleKey(t *testing.T) {
	s := newCountSketch(100000)
	for i := 0; i < 100; i++ {
		s.Increment(12345)
	}
	if got := s.Estimate(12345); got != 100 {
		t.Errorf("una sola llave sin colisiones: Estimate=%d, esperado 100", got)
	}
}

func TestCountSketchSaturation(t *testing.T) {
	// int8 satura en 127: incrementar 1000 veces no debe desbordar a negativo.
	s := newCountSketch(100000)
	for i := 0; i < 1000; i++ {
		s.Increment(999)
	}
	got := s.Estimate(999)
	if got < 0 {
		t.Errorf("saturacion: Estimate=%d, no puede ser negativo", got)
	}
	if got > 127 {
		t.Errorf("saturacion: Estimate=%d, no puede pasar de 127 con contadores int8", got)
	}
	t.Logf("tras 1000 incrementos, Estimate=%d (saturado en 127 por int8)", got)
}

func TestCountSketchNeverNegative(t *testing.T) {
	s := newCountSketch(100000)
	r := rand.New(rand.NewSource(1))
	for i := 0; i < 50000; i++ {
		s.Increment(r.Uint64())
	}
	r2 := rand.New(rand.NewSource(999))
	for i := 0; i < 10000; i++ {
		if got := s.Estimate(r2.Uint64()); got < 0 {
			t.Fatalf("Estimate devolvio negativo: %d", got)
		}
	}
}

func TestCountSketchResetAndClear(t *testing.T) {
	s := newCountSketch(100000)
	for i := 0; i < 100; i++ {
		s.Increment(7)
	}
	before := s.Estimate(7)
	s.Reset()
	after := s.Estimate(7)
	if after != before/2 {
		t.Errorf("Reset: esperado %d (mitad de %d), obtenido %d", before/2, before, after)
	}
	s.Clear()
	if got := s.Estimate(7); got != 0 {
		t.Errorf("Clear: esperado 0, obtenido %d", got)
	}
}

// EL TEST QUE IMPORTA PARA EL EXPERIMENTO: bajo una carga zipfiana, el
// sketch tiene que distinguir llaves calientes de llaves frias. Si las
// llaves calientes estiman parecido a las frias, la politica de admision
// no puede tomar buenas decisiones.
func TestCountSketchVsCmSketchUnderZipf(t *testing.T) {
	const numCounters = 100000
	cs := newCountSketch(numCounters)
	cm := newCmSketch(numCounters)

	r := rand.New(rand.NewSource(42))
	truth := map[uint64]int64{}
	// Zipf sobre 82k llaves distintas, 1M de accesos — igual que la traza real.
	zipf := rand.NewZipf(r, 1.01, 1, 81984)
	for i := 0; i < 1000000; i++ {
		k := zipf.Uint64()
		truth[k]++
		cs.Increment(k)
		cm.Increment(k)
	}

	type row struct {
		key         uint64
		real        int64
		csEst, cmEst int64
	}
	var rows []row
	for k, v := range truth {
		rows = append(rows, row{k, v, cs.Estimate(k), cm.Estimate(k)})
	}
	sort.Slice(rows, func(i, j int) bool { return rows[i].real > rows[j].real })

	fmt.Println("  llaves mas calientes (real / count-sketch / cm-sketch):")
	for i := 0; i < 8 && i < len(rows); i++ {
		fmt.Printf("    key=%-7d real=%-8d cs=%-6d cm=%-6d\n",
			rows[i].key, rows[i].real, rows[i].csEst, rows[i].cmEst)
	}
	// Una llave fria representativa
	cold := rows[len(rows)-1]
	fmt.Printf("    llave fria: real=%d cs=%d cm=%d\n", cold.real, cold.csEst, cold.cmEst)

	// La llave mas caliente debe estimarse claramente por encima de una fria.
	hottest := rows[0]
	if hottest.csEst <= cold.csEst {
		t.Errorf("count-sketch no distingue caliente (%d) de fria (%d)",
			hottest.csEst, cold.csEst)
	}

	// Cuantas de las top-1000 llaves estima el count-sketch en CERO? Si son
	// muchas, la admision las tratara como si nunca se hubieran visto.
	zerosCS, zerosCM := 0, 0
	for i := 0; i < 1000 && i < len(rows); i++ {
		if rows[i].csEst == 0 {
			zerosCS++
		}
		if rows[i].cmEst == 0 {
			zerosCM++
		}
	}
	fmt.Printf("  top-1000 llaves estimadas en CERO: count-sketch=%d cm-sketch=%d\n",
		zerosCS, zerosCM)
	if zerosCS > 100 {
		t.Errorf("count-sketch estima en cero %d de las top-1000 llaves — "+
			"la politica de admision no podria distinguirlas de llaves nuevas", zerosCS)
	}
}

// Verifica que ambos sketches ocupen memoria comparable, que es la
// condicion para que la comparacion del experimento sea justa.
func TestSketchMemoryFootprint(t *testing.T) {
	const numCounters = 100000
	cs := newCountSketch(numCounters)
	csBytes := cs.depth * cs.width // 1 byte por contador (int8)

	cm := newCmSketch(numCounters)
	// cmSketch: 4 filas de contadores de 4 bits -> media celda por contador
	cmBytes := 0
	for i := range cm.rows {
		cmBytes += len(cm.rows[i])
	}

	fmt.Printf("  memoria: count-sketch=%d bytes, cm-sketch=%d bytes (ratio %.2fx)\n",
		csBytes, cmBytes, float64(csBytes)/float64(cmBytes))

	ratio := float64(csBytes) / float64(cmBytes)
	if ratio < 0.5 || ratio > 2.0 {
		t.Errorf("los sketches no son comparables en memoria: ratio %.2fx", ratio)
	}
}
