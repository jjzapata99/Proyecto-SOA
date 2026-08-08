package org.apache.cassandra.utils;

import java.util.Random;

public final class CuckooFilterCore {

  private static final int BUCKET_SIZE = 4;       // huellas por bucket
  private static final int MAX_KICKS = 500;        // desalojos antes de darse por vencido
  static final int FINGERPRINT_BITS = 16;          // huella de 16 bits (short)

  // Tope de slots (2^30 = 2 GiB de short[]). Por encima se satura: un filtro
  // saturado degrada su FPP de forma predecible, un OutOfMemoryError mata el nodo.
  private static final long MAX_SLOTS = 1L << 30;

  // Arreglo plano, no short[][]: el bucket b ocupa [b*BUCKET_SIZE, (b+1)*BUCKET_SIZE).
  // Un arreglo por bucket costaría ~24 bytes de cabecera por 8 útiles, y este
  // filtro se compara contra el BloomFilter justamente por memoria.
  private final short[] slots;

  private final int numBuckets;
  private final int bucketMask;  // numBuckets - 1 (numBuckets siempre es potencia de 2)
  private final Random rng = new Random(42); // fijo, para que las corridas sean reproducibles

  private final boolean saturated; // el tamaño pedido no cabía y hubo que saturar

  public CuckooFilterCore(long expectedInsertions) {
    if (expectedInsertions < 0) {
      throw new IllegalArgumentException("expectedInsertions no puede ser negativo: " + expectedInsertions);
    }
    // Factor de carga objetivo ~50%: 2 slots por elemento esperado. Todo el
    // cálculo va en long — en int, un SSTable grande desbordaría y dejaría un
    // filtro minúsculo sin avisar.
    long desiredSlots = Math.max(BUCKET_SIZE, expectedInsertions * 2L);
    this.saturated = desiredSlots > MAX_SLOTS;
    long cappedSlots = Math.min(desiredSlots, MAX_SLOTS);

    long buckets = nextPowerOfTwo(Math.max(1L, cappedSlots / BUCKET_SIZE));
    // nextPowerOfTwo puede empujar justo por encima del tope: bajar un nivel.
    while (buckets * BUCKET_SIZE > MAX_SLOTS) {
      buckets >>= 1;
    }
    this.numBuckets = (int) buckets;
    this.bucketMask = this.numBuckets - 1;
    this.slots = new short[this.numBuckets * BUCKET_SIZE];
  }

  public boolean add(long item) {
    short fp = fingerprint(item);
    int i1 = indexFor(item);
    int i2 = altIndex(i1, fp);

    // Camino feliz: hay lugar en alguno de los dos buckets candidatos.
    if (insertIntoBucket(i1, fp) || insertIntoBucket(i2, fp)) {
      return true;
    }

    // Ambos llenos: desalojar a un ocupante y mandarlo a su bucket alternativo,
    // que a su vez puede desalojar a otro, en cadena.
    int i = rng.nextBoolean() ? i1 : i2;
    for (int kick = 0; kick < MAX_KICKS; kick++) {
      int victimSlot = rng.nextInt(BUCKET_SIZE);
      int pos = i * BUCKET_SIZE + victimSlot;
      short victimFp = slots[pos];
      slots[pos] = fp;   // entra la huella nueva
      fp = victimFp;     // y ahora hay que reubicar la desalojada
      i = altIndex(i, fp);
      if (insertIntoBucket(i, fp)) {
        return true;
      }
    }
    // Se agotaron los desalojos: el filtro está demasiado lleno, igual que un
    // Bloom filter mal dimensionado. La última huella desalojada se pierde acá
    // (comportamiento estándar de un cuckoo saturado), por lo que conviene
    // dimensionar con holgura.
    return false;
  }

  // true = podría estar; false garantiza que no está.
  public boolean mightContain(long item) {
    short fp = fingerprint(item);
    int i1 = indexFor(item);
    int i2 = altIndex(i1, fp);
    return bucketHas(i1, fp) || bucketHas(i2, fp);
  }

  public boolean remove(long item) {
    short fp = fingerprint(item);
    int i1 = indexFor(item);
    int i2 = altIndex(i1, fp);
    return removeFromBucket(i1, fp) || removeFromBucket(i2, fp);
  }

  public void clear() {
    java.util.Arrays.fill(slots, (short) 0);
  }

  public long sizeInBytes() {
    return (long) slots.length * Short.BYTES;
  }

  public int numBuckets() {
    return numBuckets;
  }

  public boolean isSaturated() {
    return saturated;
  }

  short[] rawSlots() {
    return slots;
  }

  private boolean insertIntoBucket(int bucket, short fp) {
    int base = bucket * BUCKET_SIZE;
    for (int slot = 0; slot < BUCKET_SIZE; slot++) {
      if (slots[base + slot] == 0) { // 0 == hueco vacío
        slots[base + slot] = fp;
        return true;
      }
    }
    return false;
  }

  private boolean bucketHas(int bucket, short fp) {
    int base = bucket * BUCKET_SIZE;
    for (int slot = 0; slot < BUCKET_SIZE; slot++) {
      if (slots[base + slot] == fp) return true;
    }
    return false;
  }

  private boolean removeFromBucket(int bucket, short fp) {
    int base = bucket * BUCKET_SIZE;
    for (int slot = 0; slot < BUCKET_SIZE; slot++) {
      if (slots[base + slot] == fp) {
        slots[base + slot] = 0; // vuelve a quedar como hueco vacío
        return true;
      }
    }
    return false;
  }

  private int indexFor(long item) {
    long h = mix(item, 0x51235DE1CA5E1L);
    return (int) (h & bucketMask);
  }

  // XOR involutivo: altIndex(altIndex(i, fp), fp) == i. Por eso se navega entre
  // los dos buckets teniendo solo la huella, sin la llave original.
  private int altIndex(int index, short fp) {
    long h = mix(fp, 0xB4B82725A5D5L);
    return (index ^ (int) (h & bucketMask)) & bucketMask;
  }

  private short fingerprint(long item) {
    long h = mix(item, 0x2545F4914F6CDD1DL);
    short fp = (short) (h & 0xFFFF);
    return fp == 0 ? 1 : fp; // 0 está reservado para "slot vacío"
  }

  // splitmix64: reparte los bits de entrada por los 64 de salida.
  private static long mix(long item, long seed) {
    long z = item + seed;
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  private static long nextPowerOfTwo(long v) {
    if (v <= 1) return 1L;
    return Long.highestOneBit(v - 1) << 1;
  }
}
