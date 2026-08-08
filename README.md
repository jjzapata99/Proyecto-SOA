# Proyecto de investigación: estructuras probabilísticas en sistemas de cacheo

Harness Docker para comparar, en 4 sistemas de cacheo open source reales, la
estructura probabilística original contra un reemplazo propuesto:

| Proyecto | Estructura original | Reemplazo | Carpeta | Versión fijada |
|---|---|---|---|---|
| Caffeine (Java) | Count-Min Sketch (4 bits) | Count-Sketch | `caffeine/` | `v3.2.0` |
| Ristretto (Go) | Count-Min Sketch (4 bits) | Count-Sketch | `ristretto/` | `v2.4.2` |
| RocksDB (C++) | Bloom filter | Binary Fuse Filter | `rocksdb/` | `v9.7.4` |
| Cassandra (Java) | Bloom filter (`IFilter`) | Cuckoo filter | `cassandra/` | `cassandra-4.1.8` |

Cada proyecto se compila desde su repositorio upstream real en el tag fijado,
con la estructura de reemplazo inyectada en el camino de admisión o de lookup
que usa el sistema de verdad. Las 8 variantes (4 baselines + 4 reemplazos)
corren de punta a punta.

## Cómo correr

```bash
python3 traces/generate_zipfian.py --out traces/zipfian_1e6.trace
docker compose build
./scripts/run_all.sh          # NO uses `docker compose up` — ver abajo
python3 scripts/compare_results.py
```

Los resultados quedan en `results/<proyecto>/<variante>.json` (con los logs
crudos al lado).

**No uses `docker compose up`.** Levanta los 8 servicios en paralelo, y como
parte de lo que medimos es throughput (lookups/segundo en RocksDB, op rate
en Cassandra), 8 contenedores compitiendo por la CPU hacen que esos números
no sean comparables entre variantes. Además dos nodos de Cassandra
simultáneos piden varios GB de RAM cada uno. `scripts/run_all.sh` los corre
en serie.

Para testear solo los algoritmos, sin esperar los builds largos:

```bash
./scripts/test_algorithms.sh              # los tres
./scripts/test_algorithms.sh cuckoo       # o uno solo
```

Estructura del repositorio:

```
caffeine/ cassandra/ ristretto/ rocksdb/   un proyecto por carpeta:
                                           Dockerfile + patch/ + run_benchmark.sh
scripts/     run_all.sh, compare_results.py, tests de los algoritmos
traces/      generador zipfiano + la traza generada
results/     <proyecto>/<variante>.json (+ logs crudos)
CLAUDE.md    instrucciones para Claude Code: comandos, arquitectura y las
             restricciones no obvias de cada proyecto
```

## Metodología

**Una imagen por proyecto, dos variantes adentro.** Cada Dockerfile clona el
repo upstream, aplica los parches de integración y compila ambas variantes;
una variable de entorno elige cuál corre. Comparar baseline contra reemplazo
no requiere reconstruir nada, así que las dos corren sobre exactamente el
mismo binario del sistema anfitrión.

**Los parches de integración están verificados, no asumidos.** Cada `sed`
sobre un archivo upstream va precedido de un `grep` del patrón esperado (el
build falla ruidosamente si upstream cambió) y seguido de un `grep` del
resultado, de modo que un parche que no aplicó no puede dejar corriendo el
baseline en silencio. Además, `scripts/compare_results.py` marca como
SUSPICIOUS cualquier variante cuyas métricas sean idénticas a su baseline,
que es la firma típica de un reemplazo mal conectado.

**Los sketches de frecuencia se comparan a igual memoria.** El Count-Sketch
de Caffeine y el de Ristretto están dimensionados para gastar exactamente los
mismos bytes que el Count-Min Sketch original, y el JSON de resultados
incluye `sketch_bytes`, `sketch_counters` y `sketch_counter_max` para poder
verificarlo.

**Carga de trabajo.** Zipf(0.99), 1e6 accesos sobre 100k llaves posibles
(~82k distintas), caché de 10.000 entradas — bien por debajo de la
cardinalidad de la traza, para que haya presión de desalojo real y la
política de admisión llegue a influir. Cassandra es la excepción: usa
`cassandra-stress` (200k ops por fase), que genera su propia carga.

### Limitaciones del montaje actual

- **Ristretto no es determinista entre corridas, y `run_all.sh` hace una sola.**
  Su política de desalojo muestrea el mapa de items y Go aleatoriza a propósito
  el orden de iteración de mapas; además `Set` es asíncrono. Medido sobre 5
  corridas de cada variante: el hit ratio del baseline va de 0.750899 a 0.751865
  y el del Count-Sketch de 0.752582 a 0.753063. Los rangos **no se solapan**, así
  que la mejora es reproducible, pero su magnitud depende de qué par de corridas
  compares: entre +0.10% y +0.29%. Una versión previa de este README reportaba
  +0.63% a partir de una sola corrida — más del triple de la diferencia de
  medias. Cualquier conclusión sobre Ristretto necesita repeticiones, no una
  corrida. La variación es todavía mayor en `keys_added` (109k–123k en el
  baseline, 162k–178k en el reemplazo).

- **El filtro cuckoo de Cassandra no se persiste a disco.** Cassandra 4.1.8
  castea duro a `BloomFilter` al escribir el componente `FILTER` de un
  SSTable, así que esa escritura se omite para filtros que no son Bloom. Las
  lecturas medidas sí pasan por el cuckoo, porque el writer reusa el filtro
  en memoria; el costo es que tras reiniciar el nodo esos SSTables quedan sin
  filtro.
- **Cassandra no compara a igual presupuesto de memoria.** Su Bloom usa ~10
  bits/llave y el cuckoo usa fingerprints de 16 bits al 50% de carga (~32
  bits/llave), así que la mejora en falsos positivos está en parte comprada
  con memoria.

## Tests de los algoritmos

`scripts/test_algorithms.sh` corre los cuatro, aislados de los proyectos que
los hospedan:

- **CountSketch (Java)** — exactitud sin colisiones, error mediano bajo
  carga, no-negatividad, valores límite (`Long.MIN_VALUE`/`MAX_VALUE`),
  halving del `reset()`, saturación del aging y preservación del ranking
  bajo Zipf.
- **CuckooFilterCore** — cero falsos negativos hasta la capacidad nominal
  (n=1000 y n=100 000), FPP medida 0.006% (teórica ~0.012%), borrado real
  verificado, comportamiento en sobrecarga y dimensionamientos hasta 4e9.
- **Count-Sketch (Go)** — corre contra el paquete real de Ristretto y
  compara contra el `cmSketch` original: saturación, ranking bajo Zipf, y
  una verificación explícita de que ambos ocupan la misma memoria.
- **Binary Fuse Filter** — round-trip poblar → serializar → deserializar →
  consultar, que es la secuencia exacta que hace RocksDB al escribir un SST
  y releerlo. Medido: 9.5 bits/llave, FPP 0.377% (teórica ~0.39%), cero
  falsos negativos.

## Resultados con la traza sintética por defecto

El óptimo de un oráculo que cachea las 10.000 llaves más frecuentes es
**80.56%** de hit ratio.

| Proyecto | Métrica | Baseline | Reemplazo | Cambio |
|---|---|---|---|---|
| **Caffeine** | hit rate | 77.64% | 76.46% | −1.18 pp |
| **Ristretto** | hit ratio (media de 5 corridas) | 0.7514 | 0.7528 | **+0.18%** |
| **RocksDB** | bits/llave | 10.005 | 9.595 | **−4.1%** |
| **RocksDB** | tasa de falsos positivos | 0.457% | 0.268% | **−41.4%** |
| **RocksDB** | lookups/segundo | 281 050 | 442 710 | **+57.5%** |

Cassandra corre aparte, con `cassandra-stress`:

| Proyecto | Métrica | Bloom | Cuckoo | Cambio |
|---|---|---|---|---|
| **Cassandra** | falsos positivos | 2 352 | 8 | **−99.7%** |
| **Cassandra** | tasa de falsos positivos | 0.47% | 0.002% | **−99.6%** |
| **Cassandra** | memoria del filtro | 250 KB | 1 311 KB | **+424%** |
| **Cassandra** | read op/s | 10 762 | 11 283 | **+4.8%** |
| **Cassandra** | latencia p99 | 3.3 ms | 2.4 ms | **−27%** |

Lectura rápida:

- **El Binary Fuse Filter le gana al Bloom filter de RocksDB en las tres
  dimensiones a la vez** (más chico, menos falsos positivos y más rápido).
  Es el resultado más contundente del conjunto y no tiene contrapartida.
- **El Cuckoo filter de Cassandra elimina casi todos los falsos positivos,
  pero a 5.2x la memoria.** No es una comparación a igual presupuesto (ver
  limitaciones): la mejora es real pero está *comprada* con memoria.
- **En los sketches de frecuencia el panorama es mixto y de magnitud chica**:
  el Count-Sketch mejora levemente en Ristretto (+0.18%) y empeora levemente
  en Caffeine (−1.52%). A igual memoria, la ventaja del estimador con signo
  se compensa con tener la mitad de contadores. Nótese también que el
  Count-Sketch admite un 49% más de llaves en Ristretto (115k → 171k) y baja
  la tasa de admisión un 33% en Caffeine (6.79% → 4.55%): cambia bastante el
  comportamiento de la política aunque el hit rate se mueva poco.

> **Confound que hay que reportar en el análisis:** a igual memoria, el CM-4
> tiene el doble de contadores pero saturan en 15, mientras que el
> Count-Sketch tiene la mitad pero llegan a ±127. Bajo carga zipfiana eso
> pesa: con CM-4 todas las llaves calientes empatan en 15 y la política de
> admisión no puede ordenarlas entre sí; con Count-Sketch sí. Parte de la
> diferencia de hit rate viene de ese rango dinámico, no solo del estimador
> con signo.

## Trazas reales

`traces/generate_zipfian.py` genera una traza sintética como punto de
partida: enteros planos, uno por línea. Para algo más representativo, dos
caminos:

- Usar [KV-replay](https://github.com/disel-espol/KV-replay) (la
  herramienta de trace replay del mismo grupo de investigación de los papers
  de referencia).
- Bajar una traza pública tipo ARC/S3 y convertirla a un entero por línea.

## Qué queda pendiente

- **Repeticiones en el harness.** `run_all.sh` corre cada variante una sola vez.
  Para Ristretto eso no alcanza (ver limitaciones); haría falta un parámetro de
  repeticiones y reportar media y rango en vez de un único valor.
- **Barrido por tamaño de caché.** El harness corre con un tamaño fijo.
  `CACHE_SIZE` ya es una variable de entorno (`docker-compose.yml`), así que
  el barrido es un `for` sobre `scripts/run_all.sh`, pero graficar cada
  métrica en función del tamaño sigue sin estar hecho.
- **Persistencia del filtro cuckoo**: hoy el filtro no sobrevive a un
  reinicio del nodo. Requiere un `CuckooFilterSerializer` y parchear también
  el camino de carga.
- **Cuckoo a igual memoria que el Bloom de Cassandra.** Bajando
  `FINGERPRINT_BITS` de 16 a 8 y subiendo el factor de carga de 50% a ~90%
  quedaría en ~9 bits/llave, comparable al Bloom, y ahí sí la diferencia de
  falsos positivos sería atribuible a la estructura.
- **Aislar el confound del rango dinámico** en los sketches: para separar
  "estimador con signo" de "contadores más anchos" habría que correr una
  variante con el Count-Sketch limitado a 0..15.
- **Cassandra con la traza zipfiana.** Hoy usa la carga sintética de
  `cassandra-stress`, así que ese experimento no es directamente comparable
  con los otros tres; haría falta un perfil de stress que consuma la traza.
