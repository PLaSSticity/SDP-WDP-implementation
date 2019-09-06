package test;


public class Figure3C {
    /**
     * This is Figure 3.c in the paper.
     * There is a predictable race on y.
     *
     * WDP (WBR) and SDP (NWC) will find this race, other relations will miss it.
     */


    static int x = 0;
    static int y = 0;
    static final Object m = new Object();
    static int oVar = 0;
    static final Object o = new Object();


    public static class Thread1 extends Thread implements Runnable {
        @Override
        public void run() {
            // ------------[  0 @ 0  ]------------
            synchronized (m) {
                x = 1;
                synchronized (o) {int t_ = oVar; oVar = t_ + 1; if (t_ != 0) t_++;} // sync(o)
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
            synchronized (o) {int t_ = oVar; oVar = t_ + 1; if (t_ != 0) t_++;} // sync(o)
            t = x;
            if (t != 2) return;
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
