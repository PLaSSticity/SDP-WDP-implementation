package test;

public class RaceWithPrevWr {

    /*

 T0    T1    T2
_____ _____ _____
a(m)
wr(x)
r(m)
      a(m)
      wr(x)
      r(m)
      a(n)
      wr(y)
      r(n)
            a(n)
            rd(y)
            br()
            r(n)
            wr(x)

The wr(x) on T2 races with wr(x) on T0, but not with T1.
SDP (NWC) and WDP (WBR) finds this race, while other relations miss it.

*/

    static int x = 0;
    static int y = 0;
    static final Object m = new Object();
    static final Object n = new Object();

    public static class Thread1 extends Thread implements Runnable {
        public void run() {
            // ------------[  0 @ 0  ]------------
            synchronized(m) {
                x = 1;
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
            synchronized (n) {
                y = 2;
            }
        }
    }

    public static class Thread3 extends Thread implements Runnable {
        @Override
        public void run() {
            try{Thread.sleep(400);}catch(Exception e){}
            // ------------[  2 @ 400  ]------------
            synchronized (n) {
                int t = y;
                if (t == 2) {}
            }
            x = 3;
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
