package acme.util;



@FunctionalInterface
public interface ThrowingPredicate<T, E extends Throwable> {

    boolean test(T elem) throws E;
}
