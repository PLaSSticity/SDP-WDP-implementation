package test;

public class ReleaseReleaseEdge {
    /**
     * This is an example demonstrating the need in SDP for adding release to release edge.
     * The example has no predictable race.
     *
     * acq(l1)
     * acq(l2)
     * acq(l3)
     * wr(x)
     * rel(l3)
     * rel(l2)
     * acq(l4)
     * rel(l4)
     * wr(y)
     * rel(l1)
     *          acq(l2)
     *          wr(x)
     *          rel(l2)
     *          acq(l1)
     *          rel(l1)
     *          acq(l4)
     *          acq(l3)
     *          rd(x)
     *          br
     *          rel(l3)
     *          rd(y)
     *          rel(l4)
     */
    static int x = 0;
    static int y = 0;
    static final Object l1 = new Object();
    static final Object l2 = new Object();
    static final Object l3 = new Object();
    static final Object l4 = new Object();

    public static class Thread1 extends Thread implements Runnable {
        public void run() {
            // ------------[  0 @ 0  ]------------
            synchronized (l1) {
                synchronized (l2) {
                    synchronized (l3) {
                        x = 1;
                    }
                }
                synchronized (l4) {}
                y = 1;
            }
        }
    }

    public static class Thread2 extends Thread implements Runnable {
        public void run() {
            try{Thread.sleep(200);}catch(Exception e){}
            // ------------[  1 @ 200  ]------------
            synchronized (l2) {
                x = 2;
            }
            synchronized (l1) {}
            synchronized (l4) {
                synchronized (l3) {
                    int t = x;
                    if (t == 1) return;
                }
                int t = y;
            }
        }
    }

    public static void main(String args[]) throws Exception {
        final Thread1 t1 = new Thread1();
        final Thread2 t2 = new Thread2();

        t1.start();
        t2.start();

        t1.join();
        t2.join();
    }
}
