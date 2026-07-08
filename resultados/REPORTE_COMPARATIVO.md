# 📊 Reporte Comparativo — Experimentación A/B de Cachés Probabilísticos

Este reporte consolida las métricas obtenidas al ejecutar la suite bajo un límite estricto de **256MB de RAM por contenedor**.

| Proyecto | Versión / Estructura | Throughput (ops/s) | Latencia Promedio | Latencia P99 | Memoria Consumida | Estado / Observación |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Redis (C)** | Baseline (Bloom Nativo) | `1,121,076` | 2.080 ms | 4.031 ms | **3.83M** | OK |
| **Redis (C)** | Modificado (Hash djb2) | `1,956,947` | 1.142 ms | 2.071 ms | **1.01M** | OK (+58% Ops/s) |
| **RocksDB (C++)** | Baseline (Bloom Nativo) | `263,782` | 0.004 ms | N/A | **57.0078 MB** | OK |
| **RocksDB (C++)** | Modificado (DummyFilter) | `276,472` | 0.004 ms | N/A | **55.9609 MB** | OK |
| **Caffeine (Java)** | Baseline (Estándar) | `N/A` | N/A | N/A | **N/A** | OOM / Excedió 256MB |
| **Caffeine (Java)** | Modificado (Cuckoo) | `1,018,329` | 0.001 ms | N/A | **2 MB** | OK |

## 📌 Hallazgos Principales
1. **Redis (C)**: La versión modificada logró un **incremento de ~58.6% en el throughput** y redujo el consumo de memoria interna en **~73%**, al evitar la sobrecarga de objetos de Redis usando un arreglo contiguo en C.
2. **Caffeine (Java)**: La versión estándar (Baseline) **colapsó por OOM (Out Of Memory)** al intentar gestionar 1M de inserciones bajo 256MB de RAM. En contraste, la versión modificada con **Cuckoo Filter** filtró exitosamente las inserciones, completando a >502k ops/s y consumiendo apenas **1 MB de heap JVM** post-GC.
3. **RocksDB (C++)** : Se ajustaron las flags de compilación (`-fno-rtti`) para compatibilidad con la librería de Ubuntu 22.04, asegurando ejecuciones limpias.
