package acme.util.collections;


import java.util.function.BiFunction;
import java.util.function.Supplier;

public class PerThread<T> {
    private final T[] items;
    private final Supplier<T> supplier;
    private final BiFunction<T, T, T> merger;
    private final int maxThreads;

    /* Creates thread local objects.
     *
     * The objects are created using the supplier. When getMerged is called, the
     * merger will be used to merge together the instances. The merge is a reduce (i.e. left fold)
     * over the created instances, starting with a fresh instance created using the supplier
     * and the instance of the earliest thread. The first argument of merger
     * is the fresh instance, or the object that was returned by merger during the last call.
     *
     * The per-thread instances are created lazily, as threads access the object.
     */
    public PerThread(int maxThreads, Supplier<T> supplier, BiFunction<T, T, T> merger) {
        items = (T[]) new Object[maxThreads * 8];
        this.supplier = supplier;
        this.merger = merger;
        this.maxThreads = maxThreads;
    }

    /* Get the local instance for thread with given id.
    *
    * Thread-safe, but only if all threads only access their own id.
    */
    public T getLocal(int tid) {
        T t = items[tid << 3];
        if (t == null) {
            t = supplier.get();
            items[tid << 3] = t;
        }
        return t;
    }

    /* Merges together all instances. Not thread safe. */
    public T getMerged() {
        return getMerged(false);
    }

    /* Merges together all instances. Not thread safe.
    *
    * If destroy is set, this data structure will drop all references to the unmerged objects.
    */
    public T getMerged(boolean destroy) {
        T carry = supplier.get();
        for (int i = 0; i < maxThreads; i++) {
            T t = items[i << 3];
            if (t == null) continue;
            carry = merger.apply(carry, t);
            if (destroy) items[i << 3] = null;
        }
        return carry;
    }
}
