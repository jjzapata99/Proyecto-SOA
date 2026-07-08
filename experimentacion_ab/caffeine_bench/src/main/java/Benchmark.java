import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.BitSet;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public class Benchmark {

    private static final int TOTAL_ITEMS = 1_000_000;

    static class CuckooFilterSimulado {
        private final BitSet tabla = new BitSet(TOTAL_ITEMS * 2);
        
        public boolean add(String key) {
            int h1 = Math.abs(key.hashCode() % (TOTAL_ITEMS * 2));
            int h2 = Math.abs((h1 ^ key.length()) % (TOTAL_ITEMS * 2));
            if (!tabla.get(h1)) {
                tabla.set(h1);
                return true;
            } else if (!tabla.get(h2)) {
                tabla.set(h2);
                return true;
            }
            return false;
        }
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Uso: java Benchmark [--baseline | --modificado]");
            System.exit(1);
        }

        String modo = args[0];
        Random rand = new Random(42);

        long start = System.currentTimeMillis();

        if ("--baseline".equals(modo)) {
            System.out.println("[Caffeine Baseline] Evaluando Caffeine.newBuilder().maximumSize(500000)...");
            Cache<String, String> cache = Caffeine.newBuilder()
                    .maximumSize(500_000)
                    .build();

            for (int i = 0; i < TOTAL_ITEMS; i++) {
                String key = "key_" + rand.nextInt(TOTAL_ITEMS);
                cache.put(key, "val_" + i);
            }
        } else if ("--modificado".equals(modo)) {
            System.out.println("[Caffeine Modificado] Evaluando ConcurrentHashMap + Cuckoo Filter...");
            ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();
            CuckooFilterSimulado filtro = new CuckooFilterSimulado();

            for (int i = 0; i < TOTAL_ITEMS; i++) {
                String key = "key_" + rand.nextInt(TOTAL_ITEMS);
                if (filtro.add(key) || map.containsKey(key)) {
                    if (map.size() < 500_000) {
                        map.put(key, "val_" + i);
                    }
                }
            }
        } else {
            System.err.println("Modo no reconocido: " + modo);
            System.exit(1);
        }

        long duration = System.currentTimeMillis() - start;
        Runtime runtime = Runtime.getRuntime();
        runtime.gc();
        long memUsed = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);

        System.out.println("-------------------------------------------");
        System.out.println("Tiempo de ejecución : " + duration + " ms");
        System.out.println("Throughput          : " + (TOTAL_ITEMS * 1000L / (duration > 0 ? duration : 1)) + " ops/s");
        System.out.println("Memoria JVM usada   : " + memUsed + " MB");
        System.out.println("-------------------------------------------");
    }
}
