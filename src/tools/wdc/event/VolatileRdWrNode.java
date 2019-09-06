package tools.wdc.event;

import tools.wdc.WDCVolatileData;

public class VolatileRdWrNode extends RdWrNode {

    public VolatileRdWrNode(long eventNumber, boolean isWrite, WDCVolatileData var, int threadID, AcqRelNode currentCriticalSection, String sourceLoc) {
        super(eventNumber, isWrite, var, threadID, currentCriticalSection, sourceLoc);
    }

    @Override
    public String getNodeLabel(){
        StringBuilder sb = new StringBuilder();
        sb.append(isWrite ? "vwr(" : "vrd(");
        sb.append(var.hashCode());
        sb.append(") ");
        return sb.toString();
    }
}
