package acme.util.collections;

public class Tuple3<X, Y, Z> {
    private final X first;
    private final Y second;
    private final Z third;

    Tuple3(X first, Y second, Z third) {
        this.first = first;
        this.second = second;
        this.third = third;
    }

    public final X first() { return first; }
    public final Y second() { return second; }
    public final Z third() { return third; }
}
