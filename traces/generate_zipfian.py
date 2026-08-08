#!/usr/bin/env python3
import argparse
import bisect
import random


def build_zipf_cdf(num_keys: int, skew: float) -> list:
    # CDF acumulada normalizada de Zipf(skew) truncada: cdf[i] es P(rank <= i+1).
    # Precomputarla cuesta O(num_keys) una vez y luego cada muestra es una
    # busqueda binaria; el muestreo por rechazo necesitaria ~8700 intentos
    # por muestra con los defaults.
    cdf = []
    total = 0.0
    for rank in range(1, num_keys + 1):
        total += 1.0 / (rank ** skew)
        cdf.append(total)
    # Normalizar in-place para que cdf[-1] == 1.0
    inv = 1.0 / total
    for i in range(len(cdf)):
        cdf[i] *= inv
    return cdf


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--num-keys", type=int, default=100_000,
                         help="tamaño del espacio de llaves (cardinalidad)")
    parser.add_argument("--num-ops", type=int, default=1_000_000,
                         help="cantidad de accesos a generar")
    parser.add_argument("--skew", type=float, default=0.99,
                         help="parámetro de sesgo Zipf (0=uniforme, ~1=muy sesgado)")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--out", type=str, default="zipfian_1e6.trace")
    args = parser.parse_args()

    rng = random.Random(args.seed)
    cdf = build_zipf_cdf(args.num_keys, args.skew)

    # En bloques, para no pagar una llamada a write() por linea.
    # Salida: enteros planos, uno por linea (formato "lirs" de Caffeine).
    distinct = set()
    buf = []
    with open(args.out, "w") as f:
        for i in range(args.num_ops):
            rank = bisect.bisect_left(cdf, rng.random()) + 1  # rank en [1, num_keys]
            key_id = rank - 1
            distinct.add(key_id)
            buf.append(f"{key_id}\n")
            if len(buf) >= 65536:
                f.write("".join(buf))
                buf.clear()
        if buf:
            f.write("".join(buf))

    print(f"Traza escrita en {args.out}: {args.num_ops} ops sobre "
          f"{args.num_keys} llaves posibles (skew={args.skew}); "
          f"{len(distinct)} llaves distintas aparecieron efectivamente")


if __name__ == "__main__":
    main()
