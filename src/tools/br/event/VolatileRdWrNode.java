package tools.br.event;

import tools.br.BRVolatileData;

public class VolatileRdWrNode extends RdWrNode {

    public VolatileRdWrNode(long eventNumber, long exampleNumber, boolean isWrite, BRVolatileData var, int threadID, AcqRelNode currentCriticalSection) {
        super(eventNumber, exampleNumber, isWrite, var, threadID, currentCriticalSection);
    }

    @Override
    public String getNodeLabel(){
        return "vrd(?) by T"+ threadID;
    }
}
