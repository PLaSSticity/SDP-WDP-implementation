package test;

public class VindicatorFigure5B {
    /* Fig. 5.b in Vindicator

    a(m)
    wr(x)
    wr(y)
    r(m)
           a(m)
           a(n)
           wr(z)
           r(n)
                  a(n)
                  rd(z)
                  br()
                  wr(z)
                  r(n)
                  wr(x)
           a(n)
           rd(z)
           br()
           r(n)
           rd(y)
           br()
           r(m)

    */
    static int x = 0;
    static int y = 0;
    static int z = 0;
    static final Object m = new Object();
    static final Object n = new Object();

    public static class Thread1 extends Thread implements Runnable {
        public void run() {
            // ------------[  0 @ 0  ]------------
            synchronized(m) {
                x = 1;
                y = 1;
            }
        }
    }

    public static class Thread2 extends Thread implements Runnable {
        public void run() {
            try{Thread.sleep(200);}catch(Exception e){}
            // ------------[  1 @ 200  ]------------
            synchronized(m) {
                synchronized (n) {
                    z = 2;
                }
                try{Thread.sleep(400);}catch(Exception e){}
                // ------------[  3 @ 600  ]------------
                synchronized (n) {
                    int t = z;
                    if (t == 3) {}
                }
                int t = y;
                if (t == 3) {}
            }
        }
    }

    public static class Thread3 extends Thread implements Runnable {
        public void run() {
            int t;
            try{Thread.sleep(400);}catch(Exception e){}
            // ------------[  2 @ 400  ]------------
            synchronized(n) {
                if (z == 2) {}
                z = 3;
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
