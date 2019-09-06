package tools.wdc.event;


import tools.wdc.WDCTool;


public class BranchNode extends EventNode {
    public BranchNode(long eventNumber, int threadId, AcqRelNode inCS, String sourceLoc) {
        super(eventNumber, threadId, inCS, sourceLoc, "branch");
    }

    private static final short DEPS_INIT_SIZE = 1;
    private static final float DEPS_GROWTH_FACTOR = 1.25f;
    private RdWrNode[] deps = new RdWrNode[DEPS_INIT_SIZE];
    private short size = 0;

    public void addDep(RdWrNode e) {
        if (size >= deps.length) {
            RdWrNode[] newDeps = new RdWrNode[(int)Math.ceil(deps.length * DEPS_GROWTH_FACTOR)];
            System.arraycopy(deps, 0, newDeps, 0, deps.length);
            deps = newDeps;
        }
        deps[size++] = e;
    }

    public RdWrNode[] getDeps() {
        return deps;
    }

    @Override
    public String getNodeLabel() {
        if (WDCTool.NO_SDG)
            return "br()";

        StringBuilder sb = new StringBuilder();
        sb.append("br(");
        for (int i = 0; i < size; i++) {
            if (i != 0) sb.append(", ");
            sb.append(deps[i]);
        }
        sb.append(")");
        return sb.toString();
    }
}
