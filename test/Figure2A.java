package test;


public class Figure2A {
    /**
     * This is Figure 2.a in the paper.
     * No predictable race exists.
     *
     * WDP (WBR) will report a false race here.
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
                t = x;
                if (t > 0) return;
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
