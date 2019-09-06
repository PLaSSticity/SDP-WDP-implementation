package test;

/*
This example demonstrates the need for right composition with HB.
Assume that sync operations contain a read, branch and write.

a(m)
s(o)
     s(o)
     wr(y)
     s(o)
s(o)
wr(x)
r(m)
          a(m)
          rd(y)
          br(y)
          r(m)
          wr(x)

Without right composition, a false race would be reported for both y and x.
*/


public class TestHBRightComposition {

    static int x = 0;
    static int y = 0;
    static int oV = 0;
    static final Object o = new Object();
    static final Object m = new Object();

    public static class Thread1 extends Thread implements Runnable {
        public void run() {
            // ------------[  0 @ 0  ]------------
            synchronized(m) {
                synchronized (o) {int t = oV; if (t != -1) oV = t + 1;}
                try{Thread.sleep(400);}catch(Exception e){}
                // ------------[  2 @ 400  ]------------
                synchronized (o) {int t = oV; if (t != -1) oV = t + 1;}
                x = 1;
            }
        }
    }

    public static class Thread2 extends Thread implements Runnable {
        public void run() {
            try{Thread.sleep(200);}catch(Exception e){}
            // ------------[  1 @ 200  ]------------
            synchronized (o) {int t = oV; if (t != -1) oV = t + 1;}
            y = 1;
            synchronized (o) {int t = oV; if (t != -1) oV = t + 1;}
        }
    }

    public static class Thread3 extends Thread implements Runnable {
        public void run() {
            try{Thread.sleep(600);}catch(Exception e){}
            // ------------[  3 @ 600  ]------------
            synchronized (m) {
                int t = y;
                if (t != -1) {}
            }
            x = 2;
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
