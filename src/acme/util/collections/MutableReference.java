package acme.util.collections;

/** Just a wrapper around some object.
 *
 * Used to get around final variable limitations of lambdas.
 */
public class MutableReference<T> {
    public T t;

    public MutableReference() {}
    public MutableReference(T t) { this.t = t; }
}
