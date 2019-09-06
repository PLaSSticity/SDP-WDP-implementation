package tools.wdc.event;


public class BranchNode extends EventNode {
    public BranchNode(long eventNumber, long exampleNumber, int threadId, AcqRelNode inCS) {
        super(eventNumber, exampleNumber, threadId, inCS, "branch");
    }

    @Override
    public String getNodeLabel() {
        return "br()";
    }
}
