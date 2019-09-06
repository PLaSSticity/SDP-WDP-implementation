package tools.br;

import acme.util.identityhash.WeakIdentityHashMap;
import rr.state.ShadowLock;
import rr.state.ShadowVar;
import tools.br.event.RdWrNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class BRLockBase {
    public final ShadowLock peer;

    public HashSet<ShadowVar> readVars; // variables read during critical section
    public HashSet<ShadowVar> writeVars; // variables written during critical section
    public HashSet<String> branchSources; // source locations for the branch events within this critical section
    public Map<ShadowVar, String> unprocessedReadVars; // Maps read vars to last read source location, used for rd-wr edges

    public BRLockBase(ShadowLock peer) {
        this.peer = peer;
        this.readVars = new HashSet<>();
        this.writeVars = new HashSet<>();
        this.branchSources = new HashSet<>();
        this.unprocessedReadVars = new HashMap<>();
    }
}
