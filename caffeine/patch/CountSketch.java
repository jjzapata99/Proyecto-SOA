package com.github.benmanes.caffeine.cache.simulator.admission.countsketch;

public final class CountSketch {

  private final int depth;       // filas = funciones hash independientes
  private final int width;       // columnas por fila, siempre potencia de 2
  private final int widthMask;   // width - 1: permite hacer el módulo con un AND
  private final byte[][] table;  // table[fila][columna]
  private final long[] rowSeeds; // una semilla por fila, para independizar los hashes

  private int additions = 0;         // incrementos desde el último reset()
  private final int resetThreshold;  // cada cuántos incrementos se envejece

  public CountSketch(int depth, int width) {
    this(depth, width, /* resetThreshold */ width * 10);
  }

  public CountSketch(int depth, int width, int resetThreshold) {
    // El ancho debe ser potencia de 2 para poder usar widthMask en vez de %.
    if (depth <= 0 || (width & (width - 1)) != 0) {
      throw new IllegalArgumentException("width debe ser potencia de 2 y depth > 0");
    }
    this.depth = depth;
    this.width = width;
    this.widthMask = width - 1;
    this.table = new byte[depth][width];
    this.resetThreshold = resetThreshold;

    // Semillas fijas pero distintas por fila: distintas para que las filas sean
    // independientes, fijas para que dos corridas den el mismo resultado.
    this.rowSeeds = new long[depth];
    long seed = 0x9E3779B97F4A7C15L; // constante de mezcla tipo splitmix64
    for (int i = 0; i < depth; i++) {
      seed += 0x9E3779B97F4A7C15L;
      rowSeeds[i] = seed;
    }
  }

  public void increment(long item) {
    for (int row = 0; row < depth; row++) {
      long h = mix(item, rowSeeds[row]);
      int bucket = (int) (h & widthMask);           // bits bajos del hash: la columna
      int sign = ((h >>> 32) & 1L) == 0 ? 1 : -1;   // bit 32 del hash: el signo
      // Saturar en vez de desbordar: con `byte`, un `+=` sobre 127 da la vuelta
      // a -128 y una llave caliente pasaría a estimarse como fría.
      byte v = table[row][bucket];
      if (sign > 0) {
        if (v < Byte.MAX_VALUE) table[row][bucket] = (byte) (v + 1);
      } else {
        if (v > Byte.MIN_VALUE) table[row][bucket] = (byte) (v - 1);
      }
    }
    if (++additions >= resetThreshold) {
      reset();
    }
  }

  public int estimate(long item) {
    // El signo se recalcula igual que en increment(), así se lee con el mismo
    // con el que se sumó.
    int[] estimates = new int[depth];
    for (int row = 0; row < depth; row++) {
      long h = mix(item, rowSeeds[row]);
      int bucket = (int) (h & widthMask);
      int sign = ((h >>> 32) & 1L) == 0 ? 1 : -1;
      estimates[row] = sign * table[row][bucket];
    }
    // Recorta a 0: la cancelación puede dar mediana negativa, que como
    // frecuencia no significa nada.
    return Math.max(0, median(estimates));
  }

  // Envejecimiento: divide todo por 2 para que lo reciente pese más que lo viejo.
  public void reset() {
    for (int row = 0; row < depth; row++) {
      for (int col = 0; col < width; col++) {
        table[row][col] = (byte) (table[row][col] / 2);
      }
    }
    additions = 0;
  }

  public long sizeInBytes() {
    return (long) depth * width; // 1 byte por contador
  }

  public int counterMax() {
    return Byte.MAX_VALUE;
  }

  public void clear() {
    for (int row = 0; row < depth; row++) {
      java.util.Arrays.fill(table[row], (byte) 0);
    }
    additions = 0;
  }

  private static int median(int[] values) {
    int[] copy = values.clone(); // no reordenar el arreglo del llamador
    java.util.Arrays.sort(copy);
    int n = copy.length;
    return (n % 2 == 1) ? copy[n / 2] : (copy[n / 2 - 1] + copy[n / 2]) / 2;
  }

  // splitmix64: reparte los bits de entrada por los 64 de salida.
  private static long mix(long item, long seed) {
    long z = item + seed;
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }
}
