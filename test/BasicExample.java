package test;

public class BasicExample {

    /**
     * A very simple race that will be detected by all race detectors.
     */

    static int x = 0;
    static final Object m = new Object();

    public static class Thread1 extends Thread implements Runnable {
        public void run() {
            int t;
            // ------------[  0 @ 0  ]------------
            synchronized (m) {
                t = x;
            }
        }
    }

    public static class Thread2 extends Thread implements Runnable {
        public void run() {
            try{Thread.sleep(200);}catch(InterruptedException e){}
            // ------------[  1 @ 200  ]------------
            x = 0;
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
