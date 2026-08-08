import org.apache.cassandra.utils.CuckooFilterCore;
import java.util.*;

/** Test standalone (sin JUnit) del CuckooFilterCore del scaffold. */
public class CuckooFilterCoreTest {

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
    System.out.println("== CuckooFilterCoreTest ==");

    testNoFalseNegatives(1000);
    testNoFalseNegatives(100_000);
    testFalsePositiveRate();
    testRemove();
    testRemoveOnlyTarget();
    testAddReturnsFalseWhenFull();
    testLargeExpectedInsertionsNoOverflow();
    testEdgeValues();

    System.out.println(failures == 0 ? "\nTODOS OK" : "\n" + failures + " FALLOS");
    System.exit(failures == 0 ? 0 : 1);
  }

  /** LA propiedad critica de un filtro: cero falsos negativos. */
  static void testNoFalseNegatives(int n) {
    System.out.println("\n-- sin falsos negativos (n=" + n + ") --");
    CuckooFilterCore f = new CuckooFilterCore(n);
    Random r = new Random(5);
    long[] keys = new long[n];
    int addFailures = 0;
    for (int i = 0; i < n; i++) {
      keys[i] = r.nextLong();
      if (!f.add(keys[i])) addFailures++;
    }
    int falseNegatives = 0;
    for (long k : keys) {
      if (!f.mightContain(k)) falseNegatives++;
    }
    check("add() nunca falla al dimensionar para n", addFailures == 0,
        "add fallidos=" + addFailures + "/" + n);
    check("cero falsos negativos", falseNegatives == 0,
        "falsos negativos=" + falseNegatives + "/" + n);
  }

  /** Con fingerprint de 16 bits y 2 buckets de 4, la FPP teorica es ~2*4/2^16 = 0.012%. */
  static void testFalsePositiveRate() {
    System.out.println("\n-- tasa de falsos positivos --");
    int n = 50_000;
    CuckooFilterCore f = new CuckooFilterCore(n);
    Set<Long> inserted = new HashSet<>();
    Random r = new Random(13);
    for (int i = 0; i < n; i++) {
      long k = r.nextLong();
      inserted.add(k);
      f.add(k);
    }
    int probes = 200_000, fp = 0;
    Random r2 = new Random(31337);
    for (int i = 0; i < probes; i++) {
      long k = r2.nextLong();
      if (inserted.contains(k)) continue;
      if (f.mightContain(k)) fp++;
    }
    double rate = (double) fp / probes;
    check("FPP < 1%", rate < 0.01,
        String.format("fpp=%.5f%% (%d/%d), teorica ~0.012%%", rate * 100, fp, probes));
  }

  /** El borrado real es LA ventaja frente a Bloom — tiene que funcionar. */
  static void testRemove() {
    System.out.println("\n-- remove() --");
    int n = 10_000;
    CuckooFilterCore f = new CuckooFilterCore(n);
    Random r = new Random(17);
    long[] keys = new long[n];
    for (int i = 0; i < n; i++) { keys[i] = r.nextLong(); f.add(keys[i]); }

    int removeFailures = 0;
    for (int i = 0; i < n / 2; i++) {
      if (!f.remove(keys[i])) removeFailures++;
    }
    check("remove() devuelve true para llaves presentes", removeFailures == 0,
        "removes fallidos=" + removeFailures + "/" + (n / 2));

    // La mitad que NO borramos debe seguir presente (cero falsos negativos)
    int survivorsLost = 0;
    for (int i = n / 2; i < n; i++) {
      if (!f.mightContain(keys[i])) survivorsLost++;
    }
    check("las llaves no borradas siguen presentes", survivorsLost == 0,
        "perdidas=" + survivorsLost + "/" + (n / 2));

    // Las borradas mayormente ya no deben aparecer (puede quedar algun FP)
    int stillThere = 0;
    for (int i = 0; i < n / 2; i++) {
      if (f.mightContain(keys[i])) stillThere++;
    }
    check("las llaves borradas desaparecen", stillThere < (n / 2) * 0.01,
        "todavia presentes=" + stillThere + "/" + (n / 2));
  }

  /** remove() de una llave que nunca se inserto no debe romper nada. */
  static void testRemoveOnlyTarget() {
    System.out.println("\n-- remove() de llave ausente --");
    CuckooFilterCore f = new CuckooFilterCore(100);
    f.add(1L); f.add(2L); f.add(3L);
    boolean removedAbsent = f.remove(999999L);
    check("remove() de ausente devuelve false", !removedAbsent, "devolvio=" + removedAbsent);
    check("las presentes sobreviven",
        f.mightContain(1L) && f.mightContain(2L) && f.mightContain(3L), "");
  }

  /** Sobrecargado muy por encima de su capacidad, add() debe reportar el fallo. */
  static void testAddReturnsFalseWhenFull() {
    System.out.println("\n-- sobrecarga --");
    CuckooFilterCore f = new CuckooFilterCore(100);
    Random r = new Random(23);
    int failed = 0;
    for (int i = 0; i < 5000; i++) {
      if (!f.add(r.nextLong())) failed++;
    }
    check("add() reporta fallo al saturar", failed > 0, "add fallidos=" + failed + "/5000");
  }

  /** Cassandra puede pedir filtros para SSTables muy grandes. */
  static void testLargeExpectedInsertionsNoOverflow() {
    System.out.println("\n-- dimensionamiento grande (overflow de int) --");
    long[] sizes = {1, 2, 10, 1_000_000, 100_000_000, 500_000_000L, Integer.MAX_VALUE, 4_000_000_000L};
    for (long size : sizes) {
      try {
        CuckooFilterCore f = new CuckooFilterCore(size);
        f.add(123L);
        boolean ok = f.mightContain(123L);
        check("expectedInsertions=" + size, ok, ok ? "" : "mightContain fallo tras add");
      } catch (Throwable t) {
        check("expectedInsertions=" + size, false,
            t.getClass().getSimpleName() + ": " + t.getMessage());
      }
    }
  }

  static void testEdgeValues() {
    System.out.println("\n-- valores limite de llave --");
    CuckooFilterCore f = new CuckooFilterCore(1000);
    long[] edges = {Long.MIN_VALUE, Long.MAX_VALUE, 0L, -1L, 1L};
    boolean allOk = true;
    StringBuilder sb = new StringBuilder();
    for (long e : edges) {
      f.add(e);
      if (!f.mightContain(e)) { allOk = false; sb.append(e).append(" "); }
    }
    check("add/mightContain en bordes", allOk, allOk ? "" : "fallaron: " + sb);
  }
}
