FROM gcc:13 as builder
WORKDIR /build
COPY mi_nuevo_filtro.c .
RUN wget https://raw.githubusercontent.com/redis/redis/unstable/src/redismodule.h && \
    gcc -fPIC -shared -O3 -o mi_nuevo_filtro.so mi_nuevo_filtro.c

FROM redis:7
WORKDIR /usr/lib/redis/modules
COPY --from=builder /build/mi_nuevo_filtro.so .
EXPOSE 6379
CMD ["redis-server", "--loadmodule", "/usr/lib/redis/modules/mi_nuevo_filtro.so"]
