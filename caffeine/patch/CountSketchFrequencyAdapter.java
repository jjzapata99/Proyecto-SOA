package com.github.benmanes.caffeine.cache.simulator.admission.countsketch;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Frequency;
import com.google.common.math.IntMath;
import com.typesafe.config.Config;

public final class CountSketchFrequencyAdapter implements Frequency {

  private static final int DEPTH = 4; // mismo número de filas que el Count-Min de Caffeine

  private final CountSketch sketch;
  private final long sizeInBytes;

  public CountSketchFrequencyAdapter(Config config) {
    var settings = new BasicSettings(config);

    // Cuántos bytes ocuparía el CountMin4 con esta configuración: reserva
    // ceilingPowerOfTwo(counters) longs, cada uno con 16 contadores de 4 bits.
    double countersMultiplier = settings.tinyLfu().countMin4().countersMultiplier();
    long counters = (long) (countersMultiplier * settings.maximumSize());
    int maximum = (int) Math.min(counters, Integer.MAX_VALUE >>> 1);
    int cm4TableLongs = (maximum == 0) ? 1 : IntMath.ceilingPowerOfTwo(maximum);
    long cm4Bytes = cm4TableLongs * 8L;

    // Repartir esos mismos bytes en DEPTH filas de contadores de 1 byte:
    // bytes = DEPTH * width * 1  =>  width = cm4Bytes / DEPTH
    long targetWidth = Math.max(1L, cm4Bytes / DEPTH);
    // El ancho debe ser potencia de 2. Se toma la INFERIOR para no pasarse del
    // presupuesto: si se pasara, el sketch ganaría por memoria y no por diseño.
    int width = Integer.highestOneBit((int) Math.min(targetWidth, Integer.MAX_VALUE >>> 1));
    if (width == 0) {
      width = 1;
    }

    // Misma cadencia de envejecimiento que PeriodicResetCountMin4, que usa
    // period = 10 * table.length (longs). Sin esto el reemplazo envejecería la
    // mitad de seguido que el baseline y esa diferencia de memoria efectiva se
    // confundiría con el efecto del estimador con signo.
    int resetThreshold = (int) Math.min(10L * cm4TableLongs, Integer.MAX_VALUE);

    this.sketch = new CountSketch(DEPTH, width, resetThreshold);
    this.sizeInBytes = (long) DEPTH * width;
  }

  @Override
  public int frequency(long e) {
    return sketch.estimate(e);
  }

  @Override
  public void increment(long e) {
    sketch.increment(e);
  }

  // No es parte de Frequency: se expone para registrar en el reporte que ambas
  // variantes gastaron la misma memoria.
  public long sizeInBytes() {
    return sizeInBytes;
  }
}
