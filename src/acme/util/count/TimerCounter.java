package acme.util.count;


import acme.util.ThrowingPredicate;

import java.util.function.Predicate;

/** An extended version of Timer that can time statements.
 *
 * To time a statements, pass in a Runnable (or, a lambda with no arguments or returns) to the method time.
 * To use like a stopwatch, call the start and stop methods.
 * Both methods of timing can be mixed together safely.
 * Multiple things can be timed concurrently, but not in parallel.
 * (i.e. same thread can time multiple things with the same timer, but the timer is not threadsafe)
 *
 * Calling get or getTotalTime while the timer is running will give incorrect results.
 *
 * The results will be printed at the end inside the XML output. Times are measured in nanoseconds.
 */
public class TimerCounter extends AbstractCounter {
    public TimerCounter(String group, String name) {
        super(group, name);
    }

    private long totalTime = 0;
    private long count = 0;

    public long time(Runnable r) {
        long start = System.nanoTime();
        r.run();
        long duration = System.nanoTime() - start;
        totalTime += duration;
        count++;
        return duration;
    }

    /** Runs the given function repeatedly until it returns false, timing each iteration.
     *
     * Counts the number of iterations, and passes the current iteration number to the function.
     *
     * @return The number of iterations.
     */
    public int time(Predicate<Integer> iter) {
        int i = 0;
        boolean cont = true;
        while (cont) {
            start();
            cont = iter.test(i++);
            stop();
        }
        return i;
    }

    public <E extends Throwable> int timeEx(ThrowingPredicate<Integer, E> iter) throws E {
        int i = 0;
        boolean cont = true;
        while (cont) {
            start();
            cont = iter.test(i++);
            stop();
        }
        return i;
    }

    public void start() {
        totalTime -= System.nanoTime();
    }

    public void stop() {
        totalTime += System.nanoTime();
        count++;
    }

    @Override
    public String get() {
        return String.format("<total>%d</total> <count>%d</count> <average>%.0f</average>", totalTime, count, count != 0 ? (double)totalTime / count : Double.NaN);
    }

    public long getTotalTime() {
        return totalTime;
    }

    @Override
    public long getCount() {
        return count;
    }
}
