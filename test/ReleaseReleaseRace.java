package test;

public class ReleaseReleaseRace {
    /**
     * This example has a predictable data race.
     *
     * acq(l1)
     * wr(x)
     * wr(y)
     * rel(l1)
     *          acq(l1)
     *          wr(x)
     *          rel(l1)
     *          rd(y)
     */
    static int x = 0;
    static int y = 0;
    static final Object l1 = new Object();

    public static class Thread1 extends Thread implements Runnable {
        public void run() {
            // ------------[  0 @ 0  ]------------
            synchronized (l1) {
                x = 1;
                y = 1;
            }
        }
    }

    public static class Thread2 extends Thread implements Runnable {
        public void run() {
            try{Thread.sleep(200);}catch(Exception e){}
            // ------------[  1 @ 200  ]------------
            synchronized (l1) {
                x = 2;
            }
            int t = y;
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
