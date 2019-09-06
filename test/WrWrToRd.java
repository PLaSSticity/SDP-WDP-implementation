package test;

public class WrWrToRd {
    /**
     * This is an example demonstrating the need in SDP for adding an edge to a read after a write-write conflict.
     * No predictable race exists in this example.
     * SDP (NWC) adds an edge from the release in Thread1 to the rd(x) in Thread 2, and correctly won't report any races.
     * WDP (WBR) will report a false race on y.
     *
     * a(m)
     * wr(x)
     * s(o)
     * wr(y)
     * r(m)
     *       a(m)
     *       wr(x)
     *       r(m)
     *       s(o)
     *       rd(x)
     *       br
     *       wr(y)
     */
    static int x = 0;
    static int y = 0;
    static final Object m = new Object();
    static final Object o = new Object();
    static int oV = 0;

    public static class Thread1 extends Thread implements Runnable {
        public void run() {
            // ------------[  0 @ 0  ]------------
            synchronized(m) {
                x = 1;
                synchronized (o) {oV++;}
                y = 1;
            }
        }
    }

    public static class Thread2 extends Thread implements Runnable {
        public void run() {
            try{Thread.sleep(200);}catch(Exception e){}
            // ------------[  1 @ 200  ]------------
            synchronized(m) {
                x = 2;
            }
            synchronized (o) {oV++;}
            int t = x;
            if (t == 1) {}
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
