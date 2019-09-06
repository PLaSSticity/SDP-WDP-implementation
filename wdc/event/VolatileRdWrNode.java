package tools.wdc.event;

import tools.wdc.WDCVolatileData;

public class VolatileRdWrNode extends RdWrNode {

    public VolatileRdWrNode(long eventNumber, long exampleNumber, boolean isWrite, WDCVolatileData var, int threadID, AcqRelNode currentCriticalSection) {
        super(eventNumber, exampleNumber, isWrite, var, threadID, currentCriticalSection);
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
