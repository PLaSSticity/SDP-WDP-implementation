package test;

public class VolatileRace {

    static volatile int x;
    static int y;

    public static class Thread1 extends Thread implements Runnable {
        public void run() {
            // ------------[  0 @ 0  ]------------
            y = 1;
            x = 1;
        }
    }

    public static class Thread2 extends Thread implements Runnable {
        public void run() {
            int t;
            try{Thread.sleep(200);}catch(Exception e){}
            // ------------[  1 @ 200  ]------------
            t = x;
            t = y;
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
