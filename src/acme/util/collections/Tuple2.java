package acme.util.collections;

public class Tuple2 <X, Y> {
    private final X first;
    private final Y second;

    public Tuple2(X first, Y second) {
        this.first = first;
        this.second = second;
    }

    public final X first() { return first; }
    public final Y second() { return second; }
}
