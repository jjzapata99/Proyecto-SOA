package ristretto

type freqSketch interface {
	Increment(hashed uint64)
	Estimate(hashed uint64) int64
	Reset()
	Clear()
}
