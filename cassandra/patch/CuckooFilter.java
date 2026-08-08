package org.apache.cassandra.utils;

import org.apache.cassandra.utils.concurrent.Ref;
import org.apache.cassandra.utils.concurrent.WrappedSharedCloseable;

public final class CuckooFilter extends WrappedSharedCloseable implements IFilter
{
    private final CuckooFilterCore core;

    public CuckooFilter(long numElements)
    {
        // No hay recursos nativos que liberar (a diferencia del OffHeapBitSet de
        // BloomFilter), así que se envuelve un AutoCloseable que no hace nada.
        super(NO_OP);
        this.core = new CuckooFilterCore(numElements);
    }

    private CuckooFilter(CuckooFilter copy)
    {
        super(copy);
        this.core = copy.core; // la copia comparte el filtro; solo cambia el refcount
    }

    private static final AutoCloseable NO_OP = () -> {};

    @Override
    public void add(FilterKey key)
    {
        core.add(hashOf(key));
    }

    @Override
    public boolean isPresent(FilterKey key)
    {
        return core.mightContain(hashOf(key));
    }

    @Override
    public void clear()
    {
        core.clear();
    }

    @Override
    public IFilter sharedCopy()
    {
        return new CuckooFilter(this);
    }

    // Devuelve el tamaño real y no 0 para que la métrica de memoria sea
    // comparable contra el serializedSize del BloomFilter.
    @Override
    public long serializedSize()
    {
        return heapSizeInBytes();
    }

    // Este filtro vive en el heap, no off-heap.
    @Override
    public long offHeapSize()
    {
        return 0;
    }

    public long heapSizeInBytes()
    {
        return core.sizeInBytes();
    }

    @Override
    public void addTo(Ref.IdentityCollection identities)
    {
        super.addTo(identities);
    }

    @Override
    public String toString()
    {
        return "CuckooFilter[buckets=" + core.numBuckets()
             + ";bytes=" + core.sizeInBytes()
             + (core.isSaturated() ? ";SATURADO" : "") + ']';
    }

    // Cassandra entrega dos hashes por llave; al filtro le alcanza con el primero.
    private static long hashOf(FilterKey key)
    {
        long[] hashes = new long[2];
        key.filterHash(hashes);
        return hashes[0];
    }
}
