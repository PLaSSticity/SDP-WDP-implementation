package tools.wdc.event;

import rr.tool.RR;

public class ReorderingTimeout extends Throwable {

    private static final long REORDER_TIMEOUT = RR.brReorderTimeout.get();
    private static final boolean DO_CHECK_TIMEOUT = REORDER_TIMEOUT != 0;
    private static long start;

    public static void reorderStarted() {
        if (DO_CHECK_TIMEOUT) start = System.currentTimeMillis();
    }

    public static void checkTimeout() throws ReorderingTimeout {
        if (DO_CHECK_TIMEOUT && System.currentTimeMillis() - start > REORDER_TIMEOUT) {
            throw new ReorderingTimeout();
        }
    }
}
