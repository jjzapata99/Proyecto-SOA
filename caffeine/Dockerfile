FROM eclipse-temurin:21-jdk AS build

# python3 se usa en run_benchmark.sh para convertir el CSV del simulador a JSON.
RUN apt-get update && apt-get install -y --no-install-recommends git python3 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

# Pin a versión específica para reproducibilidad.
RUN git clone --branch v3.2.0 --depth 1 https://github.com/ben-manes/caffeine.git

# Copiamos nuestra implementación al paquete nuevo `admission.countsketch`
COPY patch/CountSketch.java \
     caffeine/simulator/src/main/java/com/github/benmanes/caffeine/cache/simulator/admission/countsketch/CountSketch.java
COPY patch/CountSketchFrequencyAdapter.java \
     caffeine/simulator/src/main/java/com/github/benmanes/caffeine/cache/simulator/admission/countsketch/CountSketchFrequencyAdapter.java

WORKDIR /workspace/caffeine

ENV TINYLFU=simulator/src/main/java/com/github/benmanes/caffeine/cache/simulator/admission/TinyLfu.java

# Patch de TinyLfu.java: agregar la rama "count-sketch" al switch de makeSketch().
RUN grep -q 'case "count-min-64": return new CountMin64TinyLfu(settings.config());' "$TINYLFU" || \
      (echo "ERROR: no se encontró el case 'count-min-64' en TinyLfu.java — el switch cambió, ver README" && exit 1)

# 1) El import de nuestra clase, junto a los de las clases hermanas.
RUN grep -q 'import com.github.benmanes.caffeine.cache.simulator.admission.countmin64.CountMin64TinyLfu;' "$TINYLFU" || \
      (echo "ERROR: no se encontró el import de CountMin64TinyLfu en TinyLfu.java" && exit 1)
RUN sed -i 's|import com.github.benmanes.caffeine.cache.simulator.admission.countmin64.CountMin64TinyLfu;|import com.github.benmanes.caffeine.cache.simulator.admission.countmin64.CountMin64TinyLfu;\nimport com.github.benmanes.caffeine.cache.simulator.admission.countsketch.CountSketchFrequencyAdapter;|' "$TINYLFU"

# 2) El case nuevo en el switch de makeSketch().
RUN sed -i 's|      case "count-min-64": return new CountMin64TinyLfu(settings.config());|      case "count-min-64": return new CountMin64TinyLfu(settings.config());\n      case "count-sketch": return new CountSketchFrequencyAdapter(settings.config());|' "$TINYLFU"

# 3) Sin esta verificación, la variante "countsketch" correría en silencio con
# el CM-4 por defecto.
RUN grep -q 'case "count-sketch": return new CountSketchFrequencyAdapter(settings.config());' "$TINYLFU" || \
      (echo "ERROR: el patch de TinyLfu.java no quedó aplicado" && exit 1)
RUN grep -q 'import com.github.benmanes.caffeine.cache.simulator.admission.countsketch.CountSketchFrequencyAdapter;' "$TINYLFU" || \
      (echo "ERROR: el import de CountSketchFrequencyAdapter no quedó aplicado" && exit 1)

# --no-daemon: en un contenedor efímero el daemon de Gradle no aporta.
# Se saltan los checks de estilo: interesa que compile y corra, no el linting.
RUN ./gradlew :simulator:compileJava --no-daemon \
      -x spotbugsMain -x pmdMain -x checkstyleMain

COPY run_benchmark.sh /workspace/run_benchmark.sh
RUN chmod +x /workspace/run_benchmark.sh

ENTRYPOINT ["/workspace/run_benchmark.sh"]
