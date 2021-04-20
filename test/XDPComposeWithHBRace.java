package test;

public class XDPComposeWithHBRace {
    /**
     * The example has a predictable race, but no SDP-race.
     *
     * acq(l1)
     * wr(x)
     * rel(l1)
     *          acq(l1)
     *          wr(y)
     *          wr(x)
     *          rel(l1)
     *                   acq(l1)
     *                   wr(x)
     *                   rel(l1)
     *                   wr(y)
     */
    static int x = 0;
    static int y = 0;
    static final Object l1 = new Object();

    public static class Thread1 extends Thread implements Runnable {
        public void run() {
            // ------------[  0 @ 0  ]------------
            synchronized (l1) {
                x = 1;
            }
        }
    }

    public static class Thread2 extends Thread implements Runnable {
        public void run() {
            try{Thread.sleep(200);}catch(Exception e){}
            // ------------[  1 @ 200  ]------------
            synchronized (l1) {
                y = 1;
                x = 2;
            }
        }
    }

    public static class Thread3 extends Thread implements Runnable {
        public void run() {
            try{Thread.sleep(200);}catch(Exception e){}
            // ------------[  1 @ 200  ]------------
            synchronized (l1) {
                x = 3;
            }
            y = 3;
        }
    }

    public static void main(String args[]) throws Exception {
        final Thread1 t1 = new Thread1();
        final Thread2 t2 = new Thread2();
        final Thread3 t3 = new Thread3();

        t1.start();
        t2.start();
        t3.start();

        t1.join();
        t2.join();
        t3.join();
    }
}
