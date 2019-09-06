package test;


public class Figure2B {
    /**
     * This is Figure 2.b in the paper.
     * The execution has a race on the accesses to y.
     *
     * WDP (WBR) will find this race, while other relations miss it.
     */


    static int x = 0;
    static int y = 0;
    static final Object m = new Object();

    public static class Thread1 extends Thread implements Runnable {
        @Override
        public void run() {
            int t;
            // ------------[  0 @ 0  ]------------
            synchronized (m) {
                y = 1;
                t = x;
                if (t > 0) return;
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
