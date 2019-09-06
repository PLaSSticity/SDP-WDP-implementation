package tools.br.event;

import java.util.HashSet;
import java.util.Set;

public class BranchNode extends EventNode {
    public final Set<RdWrNode> dependsOn = new HashSet<>();

    public BranchNode(long eventNumber, long exampleNumber, int threadId, AcqRelNode inCS) {
        super(eventNumber, exampleNumber, threadId, inCS, "branch");
    }
}
