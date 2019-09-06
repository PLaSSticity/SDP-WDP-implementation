package tools.br.sourceinfo;

import acme.util.count.ThreadLocalCounter;

public class SDGNoOp implements SDGI {
    @Override
    public boolean dependsOn(String readLocation, String branchLocation,ThreadLocalCounter missbranch,ThreadLocalCounter missread, int Tid) {
        // With the SDG information disabled, we must assume that all branches depend on all reads
        return true;
    }
}
