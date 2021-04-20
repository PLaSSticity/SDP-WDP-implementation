package test;

public class XDPNestedLocksRace {
    /**
     * The example has a predictable race.
     *
     * acq(l2)
     * rel(l2)
     * acq(l1)
     * rd(y)
     * wr(x)
     * rel(l1)
     *          acq(l2)
     *          acq(l1)
     *          wr(x)
     *          rel(l1)
     *          rel(l2)
     *          wr(y)
     */
    static int x = 0;
    static int y = 0;
    static final Object l1 = new Object();
    static final Object l2 = new Object();

    public static class Thread1 extends Thread implements Runnable {
        public void run() {
            // ------------[  0 @ 0  ]------------
            synchronized (l2) {}
            synchronized (l1) {
                int t = y;
                x = 1;
            }
        }
    }

    public static class Thread2 extends Thread implements Runnable {
        public void run() {
            try{Thread.sleep(200);}catch(Exception e){}
            // ------------[  1 @ 200  ]------------
            synchronized (l2) {
                synchronized (l1) {
                    x = 2;
                }
            }
            y = 2;
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
