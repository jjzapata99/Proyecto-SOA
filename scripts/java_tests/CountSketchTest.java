import com.github.benmanes.caffeine.cache.simulator.admission.countsketch.CountSketch;
import java.util.*;

/** Test standalone (sin JUnit) del CountSketch del scaffold. */
public class CountSketchTest {

  static int failures = 0;

  static void check(String name, boolean cond, String detail) {
    if (cond) {
      System.out.println("  [OK]   " + name + (detail.isEmpty() ? "" : " — " + detail));
    } else {
      System.out.println("  [FAIL] " + name + " — " + detail);
      failures++;
    }
  }

  public static void main(String[] args) {
    System.out.println("== CountSketchTest ==");

    testExactSingleKey();
    testNoCollisionAccuracyManyKeys();
    testNeverSeenKeyIsNonNegative();
    testEdgeValues();
    testResetHalves();
    testClear();
    testAgingDoesNotExplode();
    testZipfianRankingPreserved();

    System.out.println(failures == 0 ? "\nTODOS OK" : "\n" + failures + " FALLOS");
    System.exit(failures == 0 ? 0 : 1);
  }

  /** Una sola llave incrementada N veces debe estimarse exactamente N (sin colisiones posibles). */
  static void testExactSingleKey() {
    System.out.println("\n-- una sola llave, sin colisiones --");
    // resetThreshold enorme para que no haya aging durante el test
    CountSketch s = new CountSketch(4, 1024, Integer.MAX_VALUE);
    for (int i = 0; i < 100; i++) s.increment(12345L);
    check("estimate == 100", s.estimate(12345L) == 100,
        "estimate=" + s.estimate(12345L));
  }

  /** Con el sketch bien dimensionado, el error mediano debe ser chico. */
  static void testNoCollisionAccuracyManyKeys() {
    System.out.println("\n-- 1000 llaves, sketch ancho --");
    int n = 1000;
    CountSketch s = new CountSketch(4, 16384, Integer.MAX_VALUE);
    Map<Long, Integer> truth = new HashMap<>();
    Random r = new Random(7);
    for (int i = 0; i < n; i++) {
      long key = r.nextLong();
      int times = 1 + r.nextInt(50);
      truth.put(key, times);
      for (int t = 0; t < times; t++) s.increment(key);
    }
    List<Integer> errors = new ArrayList<>();
    for (Map.Entry<Long, Integer> e : truth.entrySet()) {
      errors.add(Math.abs(s.estimate(e.getKey()) - e.getValue()));
    }
    Collections.sort(errors);
    int medianErr = errors.get(errors.size() / 2);
    int p95Err = errors.get((int) (errors.size() * 0.95));
    check("error mediano <= 1", medianErr <= 1, "mediana=" + medianErr + " p95=" + p95Err);
  }

  /** Una llave nunca vista no debe dar estimaciones negativas ni absurdas. */
  static void testNeverSeenKeyIsNonNegative() {
    System.out.println("\n-- llaves nunca vistas --");
    CountSketch s = new CountSketch(4, 1024, Integer.MAX_VALUE);
    Random r = new Random(11);
    for (int i = 0; i < 5000; i++) s.increment(r.nextLong());

    int negatives = 0, huge = 0;
    Random r2 = new Random(999);
    for (int i = 0; i < 2000; i++) {
      int est = s.estimate(r2.nextLong());
      if (est < 0) negatives++;
      if (est > 50) huge++;
    }
    check("ninguna estimacion negativa", negatives == 0, "negativas=" + negatives);
    check("pocas sobreestimaciones grandes", huge < 100, "est>50: " + huge + "/2000");
  }

  /** Valores limite: Long.MIN_VALUE, MAX_VALUE, 0, -1. */
  static void testEdgeValues() {
    System.out.println("\n-- valores limite --");
    CountSketch s = new CountSketch(4, 1024, Integer.MAX_VALUE);
    long[] edges = {Long.MIN_VALUE, Long.MAX_VALUE, 0L, -1L, 1L};
    try {
      for (long e : edges) {
        for (int i = 0; i < 10; i++) s.increment(e);
      }
      boolean allOk = true;
      StringBuilder sb = new StringBuilder();
      for (long e : edges) {
        int est = s.estimate(e);
        sb.append(e).append("=>").append(est).append(" ");
        if (est < 8 || est > 12) allOk = false;
      }
      check("increment/estimate en bordes", allOk, sb.toString());
    } catch (Exception ex) {
      check("increment/estimate en bordes", false, "excepcion: " + ex);
    }

    // width no potencia de 2 debe rechazarse
    boolean threw = false;
    try { new CountSketch(4, 1000); } catch (IllegalArgumentException ex) { threw = true; }
    check("width no-pow2 rechazado", threw, "");

    boolean threw2 = false;
    try { new CountSketch(0, 1024); } catch (IllegalArgumentException ex) { threw2 = true; }
    check("depth=0 rechazado", threw2, "");
  }

  /** reset() debe partir a la mitad, no borrar. */
  static void testResetHalves() {
    System.out.println("\n-- reset() halving --");
    CountSketch s = new CountSketch(4, 1024, Integer.MAX_VALUE);
    for (int i = 0; i < 100; i++) s.increment(42L);
    s.reset();
    check("estimate ~50 tras reset", s.estimate(42L) == 50, "estimate=" + s.estimate(42L));
  }

  static void testClear() {
    System.out.println("\n-- clear() --");
    CountSketch s = new CountSketch(4, 1024, Integer.MAX_VALUE);
    for (int i = 0; i < 100; i++) s.increment(42L);
    s.clear();
    check("estimate == 0 tras clear", s.estimate(42L) == 0, "estimate=" + s.estimate(42L));
  }

  /** Con aging activado (constructor de 2 args), los contadores no deben explotar. */
  static void testAgingDoesNotExplode() {
    System.out.println("\n-- aging automatico (constructor 2-args) --");
    CountSketch s = new CountSketch(4, 1024); // resetThreshold = width*10 = 10240
    for (int i = 0; i < 100_000; i++) s.increment(7L);
    int est = s.estimate(7L);
    check("estimate acotado tras 100k incrementos", est > 0 && est < 20000,
        "estimate=" + est);
  }

  /**
   * La propiedad que de verdad importa para TinyLFU: si la llave A es mucho mas
   * frecuente que la B, estimate(A) > estimate(B).
   */
  static void testZipfianRankingPreserved() {
    System.out.println("\n-- ranking preservado bajo carga zipfiana --");
    CountSketch s = new CountSketch(4, 4096, Integer.MAX_VALUE);
    Random r = new Random(3);
    Map<Long, Integer> truth = new HashMap<>();
    // 20k accesos sobre 2000 llaves con popularidad muy desigual
    for (int i = 0; i < 20000; i++) {
      long key = (long) (Math.pow(r.nextDouble(), 3) * 2000);
      truth.merge(key, 1, Integer::sum);
      s.increment(key);
    }
    List<Map.Entry<Long, Integer>> sorted = new ArrayList<>(truth.entrySet());
    sorted.sort((a, b) -> b.getValue() - a.getValue());

    int inversions = 0, comparisons = 0;
    // comparar el top-20 contra la cola: el orden debe respetarse casi siempre
    for (int i = 0; i < Math.min(20, sorted.size()); i++) {
      for (int j = sorted.size() - 20; j < sorted.size(); j++) {
        if (j <= i) continue;
        comparisons++;
        if (s.estimate(sorted.get(i).getKey()) <= s.estimate(sorted.get(j).getKey())) {
          inversions++;
        }
      }
    }
    check("ranking top vs cola preservado", inversions * 100 / Math.max(1, comparisons) < 5,
        "inversiones=" + inversions + "/" + comparisons);
  }
}
