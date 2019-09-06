package tools.br;

import rr.state.ShadowLock;
import rr.state.ShadowThread;
import rr.state.ShadowVar;
import rr.tool.RR;
import tools.br.br.BRGuardState;
import tools.br.event.ShadowID;

import java.util.HashMap;
import java.util.HashSet;

public class BRGuardBase implements ShadowVar {
    private final ShadowID id;

    // W_x, Last read by each thread
    public final CV lastReads;
    // R_x, Last write by each thread
    public final CV lastWrites;

    // Used to report races, as well as DC composition edges for races.
    public DynamicSourceLocation[] lastReadLocs;
    public DynamicSourceLocation[] lastWriteLocs;
    // tid of the thread who performed the last write
    public int lastWriter;

    public static final int NO_LAST_WRITER = -1;

    // Read locksets
    public final HashMap<ShadowThread,HashSet<ShadowLock>> heldLocksRead;
    // Write locksets
    public final HashMap<ShadowThread,HashSet<ShadowLock>> heldLocksWrite;

    public BRGuardBase() {
        id = new ShadowID();
        lastReads = new CV(BRToolBase.INIT_CV_SIZE);
        lastWrites = new CV(BRToolBase.INIT_CV_SIZE);
        lastReadLocs = new DynamicSourceLocation[RR.maxTidOption.get()];
        lastWriteLocs = new DynamicSourceLocation[RR.maxTidOption.get()];
        heldLocksRead = new HashMap<>();
        heldLocksWrite = new HashMap<>();
        lastWriter = NO_LAST_WRITER;
    }

    @Override
    public String toString() {
        return "r:" + lastReads + ", w:" + lastWrites;
    }

    public ShadowID getID() {
        return id;
    }
}