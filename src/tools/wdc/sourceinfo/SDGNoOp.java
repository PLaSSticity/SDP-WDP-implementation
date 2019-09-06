package tools.wdc.sourceinfo;

import acme.util.count.ThreadLocalCounter;

public class SDGNoOp implements SDGI {
    @Override
    public boolean dependsOn(String readLocation, String branchLocation, int tid) {
        // With the SDG information disabled, we must assume that all branches depend on all reads
        return true;
    }
}
