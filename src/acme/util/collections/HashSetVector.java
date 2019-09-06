package acme.util.collections;

import java.util.*;

/**
 * A combination of a Vector and a HashSet.
 *
 * Provides constant time lookup for checking whether an element exists in the collection,
 * constant time indexing, and amortized constant time insertion/deletion at the end.
 *
 * Allows duplicates to be inserted, however the behavior of remove/removeAll is undefined if duplicates
 * are inserted.
 */
public class HashSetVector<T> implements List<T> {
    private static final Object PRESENT = new Object();
    private final HashMap<T, Object> map;
    private final Vector<T> vector;

    public HashSetVector() {
        map = new HashMap<>();
        vector = new Vector<>();
    }
    public HashSetVector(int initialCapacity) {
        map = new HashMap<>(initialCapacity);
        vector = new Vector<>(initialCapacity);
    }
    public HashSetVector(int initialCapacity, float loadFactor) {
        map = new HashMap<>(initialCapacity, loadFactor);
        vector = new Vector<>(initialCapacity);
    }


    @Override
    public boolean addAll(int i, Collection<? extends T> collection) {
        boolean changed = false;
        for (T t : collection) {
            map.put(t, PRESENT);
            changed |= vector.add(t);
        }
        return changed;
    }

    @Override
    public T get(int i) {
        return vector.get(i);
    }

    @Override
    public T set(int i, T t) {
        T existing = vector.get(i);
        map.remove(existing);
        map.put(t, PRESENT);
        return vector.set(i, t);
    }

    @Override
    public void add(int i, T t) {
        map.put(t, PRESENT);
        vector.add(i, t);
    }

    @Override
    public T remove(int i) {
        T t = vector.get(i);
        map.remove(t);
        vector.remove(i);
        return t;
    }

    public T removeLast() {
        return remove(size() - 1);
    }

    @Override
    public int indexOf(Object o) {
        return vector.indexOf(o);
    }

    @Override
    public int lastIndexOf(Object o) {
        return vector.lastIndexOf(o);
    }

    @Override
    public ListIterator<T> listIterator() {
        return vector.listIterator();
    }

    @Override
    public ListIterator<T> listIterator(int i) {
        return vector.listIterator(i);
    }

    @Override
    public List<T> subList(int i, int i1) {
        return vector.subList(i, i1);
    }

    @Override
    public int size() {
        return vector.size();
    }

    @Override
    public boolean isEmpty() {
        return vector.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        return map.containsKey(o);
    }

    @Override
    public Iterator<T> iterator() {
        return vector.iterator();
    }

    @Override
    public Object[] toArray() {
        return vector.toArray();
    }

    @Override
    public <T1> T1[] toArray(T1[] t1s) {
        return vector.toArray(t1s);
    }

    @Override
    public boolean add(T t) {
        map.put(t, PRESENT);
        return vector.add(t);
    }

    @Override
    public boolean remove(Object o) {
        map.remove(o);
        return vector.remove(o);
    }

    @Override
    public boolean containsAll(Collection<?> collection) {
        boolean contains = true;
        for (Object c : collection) {
            contains &= contains(c);
        }
        return contains;
    }

    @Override
    public boolean addAll(Collection<? extends T> collection) {
        boolean changed = false;
        for (T t : collection) {
            changed |= add(t);
        }
        return changed;
    }

    @Override
    public boolean removeAll(Collection<?> collection) {
        boolean removed = false;
        for (Object o : collection) {
            removed |= remove(o);
        }
        return removed;
    }

    @Override
    public boolean retainAll(Collection<?> collection) {
        boolean removed = false;
        for (Iterator<T> iterator = vector.iterator(); iterator.hasNext(); ) {
            T t = iterator.next();
            if (!collection.contains(t)) {
                iterator.remove();
                map.remove(t);
                removed = true;
            }
        }
        return removed;
    }

    @Override
    public void clear() {
        map.clear();
        vector.clear();
    }
}
