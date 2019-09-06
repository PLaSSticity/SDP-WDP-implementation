package tools.br.sourceinfo;

import acme.util.count.ThreadLocalCounter;

public interface SDGI {
    public boolean dependsOn(String readLocation, String branchLocation,ThreadLocalCounter missbranch,ThreadLocalCounter missread, int Tid);
}
