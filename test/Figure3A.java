package test;


public class Figure3A {
    /**
     * This is Figure 3.a in the paper.
     * There is a predictable race on y.
     *
     * WDP (WBR) and SDP (NWC) will find this race, other relations will miss it.
     */


    static int x = 0;
    static int y = 0;
    static final Object m = new Object();

    public static class Thread1 extends Thread implements Runnable {
        @Override
        public void run() {
            // ------------[  0 @ 0  ]------------
            synchronized (m) {
                x = 1;
                y = 1;
            }
        }
    }

    public static class Thread2 extends Thread implements Runnable {
        @Override
        public void run() {
            int t;
            try{Thread.sleep(200);}catch(InterruptedException e){}
            // ------------[  1 @ 200  ]------------
            synchronized (m) {
                x = 2;
            }
            t = y;
            synchronized (m) {
                t = x;
            }
            if (t != 2) return;
        }
    }

    public static void main(String args[]) throws Exception {
        Thread t1 = new Thread1();
        Thread t2 = new Thread2();

        t1.start();
        t2.start();

        t1.join();
        t2.join();
    }
}
