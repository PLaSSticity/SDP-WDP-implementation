package test;


public class Figure1C {
    /**
     * This is Figure 1.c in the paper.
     * The execution has no predictable race.
     *
     * WDP (WBR) will report a false race here.
     */


    static int x = 0;
    static int y = 0;
    static int z = 0;
    static final Object m = new Object();

    public static class Thread1 extends Thread implements Runnable {
        @Override
        public void run() {
            int t;
            // ------------[  0 @ 0  ]------------
            synchronized (m) {
                t = z;
                if (t > 0) return;
                y = 1;
            }
        }
    }

    public static class Thread2 extends Thread implements Runnable {
        @Override
        public void run() {
            try{Thread.sleep(200);}catch(InterruptedException e){}
            // ------------[  1 @ 200  ]------------
            synchronized (m) {
                z = 2;
                x = 2;
            }
        }
    }

    public static class Thread3 extends Thread implements Runnable {
        @Override
        public void run() {
            int t;
            try{Thread.sleep(400);}catch(InterruptedException e){}
            // ------------[  2 @ 400  ]------------
            synchronized (m) {
                t = x;
                if (t != 2) return;
            }
            t = y;
        }
    }

    public static void main(String args[]) throws Exception {
        Thread t1 = new Thread1();
        Thread t2 = new Thread2();
        Thread t3 = new Thread3();

        t1.start();
        t2.start();
        t3.start();

        t1.join();
        t2.join();
        t3.join();
    }
}
