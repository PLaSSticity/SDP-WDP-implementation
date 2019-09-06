package tools.wdc;

import tools.wdc.event.RdWrNode;

// Only used for the source location of the read in wr-rd edges when delaying such edges in WBR.
public class CVED extends CVE {
    String readSourceLoc;
    RdWrNode readEvent;

    public CVED(CVE cve, RdWrNode readEvent, String readSourceLoc) {
        super(cve);
        this.readSourceLoc = readSourceLoc;
        this.readEvent = readEvent;
    }
}
