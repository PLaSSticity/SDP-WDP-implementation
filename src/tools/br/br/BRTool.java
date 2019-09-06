package tools.br.br;

import acme.util.Assert;
import acme.util.Util;
import acme.util.decorations.Decoration;
import acme.util.decorations.DecorationFactory;
import acme.util.decorations.DefaultValue;
import acme.util.io.XMLWriter;
import acme.util.option.CommandLine;
import rr.annotations.Abbrev;
import rr.event.AccessEvent;
import rr.event.ClassInitializedEvent;
import rr.event.NewThreadEvent;
import rr.event.VolatileAccessEvent;
import rr.meta.ClassInfo;
import rr.state.ShadowLock;
import rr.state.ShadowThread;
import rr.state.ShadowVar;
import rr.state.ShadowVolatile;
import rr.tool.RR;
import rr.tool.Tool;
import tools.br.*;
import tools.br.event.AcqRelNode;
import tools.br.event.BranchNode;
import tools.br.event.EventNode;
import tools.br.event.RdWrNode;

import java.util.*;

@Abbrev("BR")
public class BRTool extends BRToolBase {
    public BRTool(String name, Tool next, CommandLine commandLine) {
        super(name, next, commandLine);
    }

    @Override
    protected String getToolName() {
        return "BR";
    }

    private static final Decoration<ShadowLock,BRLockData> brLockData = ShadowLock.makeDecoration("BR:ShadowLock", DecorationFactory.Type.MULTIPLE,
            (DefaultValue<ShadowLock, BRLockData>) BRLockData::new);

    private static BRLockData get(final ShadowLock ld) {
        return brLockData.get(ld);
    }

    static private Integer getThreadEpoch(ShadowThread td) {
        return ts_get_br_hb(td).get(td.getTid());
    }

    @Override
    public void init() {
        super.init();
        getBaseLock = BRTool::get;
        getThreadEpoch = BRTool::getThreadEpoch;
    }

    private static final Decoration<ShadowVolatile,BRVolatileData> brVolatileData = ShadowVolatile.makeDecoration("BR:shadowVolatile", DecorationFactory.Type.MULTIPLE,
            (DefaultValue<ShadowVolatile, BRVolatileData>) BRVolatileData::new);

    private static BRVolatileData get(final ShadowVolatile ld) {
        return brVolatileData.get(ld);
    }

    // When running tools in parallel, if two tools have the same ts_get_ method, then the values get shared.
    // Using a unique name to be able to run BR in parallel with DC.
    private static CV ts_get_br_hb(ShadowThread ts) { Assert.panic("Bad");	return null; }
    private static void ts_set_br_hb(ShadowThread ts, CV cv) { Assert.panic("Bad");  }

    private static CV ts_get_br(ShadowThread ts) { Assert.panic("Bad");	return null; }
    private static void ts_set_br(ShadowThread ts, CV cv) { Assert.panic("Bad");  }


    @Override
    public ShadowVar makeShadowVar(AccessEvent ae) {
        if (ae.getKind() == AccessEvent.Kind.VOLATILE) {
            get(((VolatileAccessEvent)ae).getShadowVolatile());
            return super.makeShadowVar(ae);
        } else {
            return new BRGuardState();
        }
    }


    @Override
    protected void handleCreate(NewThreadEvent e, ShadowThread currentThread) {
        CV hb = ts_get_br_hb(currentThread);

        if (hb == null) {
            hb = new CV(INIT_CV_SIZE);
            ts_set_br_hb(currentThread, hb);
            hb.inc(currentThread.getTid());

            CV br = new CV(INIT_CV_SIZE);
            ts_set_br(currentThread, br);

            ts_set_reads(currentThread, new HashMap<>());
        }
    }

    @Override
    protected void handleExit(ShadowThread main, ShadowThread joining) {
        ts_get_br_hb(main).max(ts_get_br_hb(joining));
        ts_get_br(main).max(ts_get_br_hb(joining));
    }

    @Override
    protected boolean isFirstEvent(ShadowThread thread) {
        return ts_get_br_hb(thread).get(0) == 0;
    }

    @Override
    protected void drawHardEdge(ShadowThread from, ShadowThread to) {
        int fromTid = from.getTid();
        final CV fromHB = ts_get_br_hb(from);
        ts_get_br(to).max(fromHB); // Use HB here because this is a hard WCP edge
        ts_get_br_hb(to).max(fromHB);
        fromHB.inc(fromTid);
    }


    @Override
    protected void handleAcquire(ShadowThread td, ShadowLock shadowLock, boolean isHardEdge) {

        //TODO: The lockData is protected by the lock corresponding to the shadowLock being currently held
        final BRLockData lockData = get(shadowLock);

        // Compute HB
        CV hb = ts_get_br_hb(td);
        hb.max(lockData.hb);

        // BR's right composition with HB
        if (VERBOSE) Assert.assertTrue(lockData.readVars.isEmpty() && lockData.writeVars.isEmpty());
        CV br = ts_get_br(td);
        assert br != null;
        if (isHardEdge) {
            br.max(lockData.hb);
        } else {
            // If the last release of the lock was BR ordered after anything, that information is stored in lockData.br.
            // By acquiring the same lock, we are HB ordered after that release, so this is BR-HB right composition.
            br.max(lockData.br);
        }
        int tid = td.getTid();
        CV brUnionPO = new CV(br);
        brUnionPO.set(tid, hb.get(tid));

        //TODO: The acqQueueMap is protected by the lock corresponding to the shadowLock being currently held
        // Queues for rule (c), partially ordered critical section's releases must be totally ordered.
        for (ShadowThread otherTD : ShadowThread.getThreads()) {
            if (otherTD != td) {
                ArrayDeque<CV> queue = lockData.brAcqQueueMap.get(otherTD);
                if (queue == null) {
                    queue = lockData.brAcqQueueGlobal.clone(); // Include any stuff that didn't get added because otherTD hadn't been created yet
                    lockData.brAcqQueueMap.put(otherTD, queue);
                }
                queue.addLast(brUnionPO);
            }
        }

        // Also add to the queue that we'll use for any threads that haven't been created yet.
        // But before doing that, be sure to initialize *this thread's* queues for the lock using the global queues.
        ArrayDeque<CV> acqQueue = lockData.brAcqQueueMap.get(td);
        if (acqQueue == null) {
            acqQueue = lockData.brAcqQueueGlobal.clone();
            lockData.brAcqQueueMap.put(td, acqQueue);
        }
        ArrayDeque<CV> relQueue = lockData.brRelQueueMap.get(td);
        if (relQueue == null) {
            relQueue = lockData.brRelQueueGlobal.clone();
            lockData.brRelQueueMap.put(td, relQueue);
        }
        lockData.brAcqQueueGlobal.addLast(brUnionPO);

        if (PRINT_EVENT) Util.log("acq("+Util.objectToIdentityString(shadowLock.getLock())+") by T"+td.getTid()
                + " | BR " + br + "| BR+PO " + brUnionPO);
    }

    @Override
    protected void handleRelease(ShadowThread td, ShadowLock shadowLock) {
        final BRLockData lockData = get(shadowLock);

        CV hb = ts_get_br_hb(td);
        CV br = ts_get_br(td);
        int tid = td.getTid();
        CV brUnionPO = new CV(br);
        brUnionPO.set(tid, hb.get(tid));

        // Process rule (c) queue
        ArrayDeque<CV> acqQueue = lockData.brAcqQueueMap.get(td);
        ArrayDeque<CV> relQueue = lockData.brRelQueueMap.get(td);
        while (!acqQueue.isEmpty() && !acqQueue.peekFirst().anyGt(brUnionPO)) {
            acqQueue.removeFirst();
            br.max(relQueue.removeFirst());
        }

        // Apply delayed rd-wr edges
        for (ShadowVar var : lockData.writeVars) {
            if (var instanceof BRGuardState) { // Should we also draw rd-wr edges for volatiles?
                CV rdWr = lockData.brDelayedRd.get(var);
                if (rdWr != null) {
                    br.max(rdWr);

                }

                // If wr-wr edges are enabled, apply them too
                if (RR.brWrWrEdges.get()) {
                    br.max(lockData.brDelayedWrWr.get(var));
                }
            }
        }

        // Conservative BR, draw edges even if there were no branches
        if (RR.brConservative.get()) {
            Map<ShadowVar, SourceLocWithNode> reads = ts_get_reads(td);
            // Draw the wr-rd edges that were delayed
            for (ShadowVar var : reads.keySet()) {
                drawWrRdEdge(td, hb, br, var);
            }
            // All wr-rd edges have been processed
            reads.clear();

            // TODO: Fix rd-wr edges for the conservative option
        }

        // Maintain the HB times of variables read/written to in the current critical section
        for (ShadowVar var : lockData.readVars) {
            CV cv = lockData.brReadMap.get(var);
            if (cv == null) {
                cv = new CV(BRToolBase.INIT_CV_SIZE);
                lockData.brReadMap.put(var, cv);
            }
            cv.max(hb);
        }
        for (ShadowVar var : lockData.writeVars) {
            CV cv = lockData.brWriteMap.get(var);
            if (cv == null) {
                cv = new CV(BRToolBase.INIT_CV_SIZE);
                lockData.brWriteMap.put(var, cv);
            }
            cv.max(hb);
            if (BUILD_EVENT_GRAPH) lockData.brWriteNodeMap.put(var, (AcqRelNode) ts_get_br_last_event(td));
        }

        // Prepare rd-wr edges
        for (String sourceLocation : lockData.branchSources) {
            for (Iterator<Map.Entry<ShadowVar, String>> iterator = lockData.unprocessedReadVars.entrySet().iterator(); iterator.hasNext(); ) {
                Map.Entry<ShadowVar, String> readPair = iterator.next();
                ShadowVar read = readPair.getKey();
                String readSourceLoc = readPair.getValue();
                CV delayedRead = new CV(INIT_CV_SIZE);
                if (read instanceof BRGuardState) { // no rd-wr edges for volatiles
                    BRGuardState readVar = (BRGuardState) read;
                    if (SDG.dependsOn(readSourceLoc, sourceLocation, super.missbranch, super.missread, td.getTid())) {
                        delayedRead.max(hb);
                        iterator.remove();
                        lockData.brDelayedRd.put(readVar, delayedRead);
                    }
                }
            }
        }

        // Compute HB
        lockData.hb.assignWithResize(hb);
        // BR time during the release, for right-composition with HB (see acquire)
        lockData.br.assignWithResize(br);

        // BR release queues
        CV hbCopy = new CV(hb);
        for (ShadowThread otherTD : ShadowThread.getThreads()) {
            if (otherTD != td) {
                ArrayDeque<CV> queue = lockData.brRelQueueMap.get(otherTD);
                if (queue == null) {
                    queue = lockData.brRelQueueGlobal.clone(); // Include any stuff that didn't get added because otherTD hadn't been created yet
                    lockData.brRelQueueMap.put(otherTD, queue);
                }
                queue.addLast(hbCopy);
            }
        }
        // Also add to the queue that we'll use for any threads that haven't been created yet
        lockData.brRelQueueGlobal.addLast(hbCopy);

        // Clear readVars and writeVars
        lockData.readVars = new HashSet<ShadowVar>();
        lockData.unprocessedReadVars = new HashMap<>();
        lockData.writeVars = new HashSet<ShadowVar>();
        lockData.branchSources = new HashSet<>();
        lockData.brReadMap = getPotentiallyShrunkMap(lockData.brReadMap);
        lockData.brWriteMap = getPotentiallyShrunkMap(lockData.brWriteMap);

        // Do the increments last
        //TODO: Accessed by only this thread
        hb.inc(td.getTid());
        // BR not incremented because it doesn't contain PO

        if (PRINT_EVENT) Util.log("rel("+Util.objectToIdentityString(shadowLock.getLock())+") by T"+td.getTid()
                + " | BR " + br + "| BR+PO " + brUnionPO);
    }

    @Override
    protected void handleClassInit(ShadowThread thread, ClassInfo classInfo) {
        BRVolatileData initTime = classInitTime.get(classInfo);
        ts_get_br(thread).max(initTime.hb); // union with HB since this is effectively a hard BR edge
        ts_get_br_hb(thread).max(initTime.hb);
    }

    @Override
    protected void handleClassInitialized(ShadowThread thread, ClassInitializedEvent e) {
        final CV hb = ts_get_br_hb(thread);
        CV br = ts_get_br(thread);
        int tid = thread.getTid();

        BRVolatileData vd = classInitTime.get(e.getRRClass());
        if (vd.hb == null) vd.hb = new CV(INIT_CV_SIZE);
        vd.hb.max(hb);

        hb.inc(tid);

        vd.br.max(br);
    }

    @Override
    protected void handleRead(AccessEvent fae, BRGuardBase baseVar, ShadowThread td) {
        BRGuardState var = (BRGuardState) baseVar;
        final CV tdHB = ts_get_br_hb(td);
        CV tdBR = ts_get_br(td);
        CV brUnionPO = new CV(tdBR);
        brUnionPO.set(td.getTid(), tdHB.get(td.getTid()));

        // Compute wr-rd edges, delay them
        CV wrRdEdges = new CV(BRToolBase.INIT_CV_SIZE);
        var.brDelayedWr.put(td, wrRdEdges);
        for (int i = td.getNumLocksHeld() - 1; i >= 0; i--) {
            ShadowLock lock = td.getHeldLock(i);
            BRLockData lockData = get(lock);
            CV write = lockData.brWriteMap.get(var);
            if (write != null) {
                wrRdEdges.max(write);
                if (BUILD_EVENT_GRAPH) EventNode.addEdge(lockData.brWriteNodeMap.get(var), ts_get_br_last_event(td));
            }
        }
        if (RR.brStrict.get()) { // Drawing edges release to access
            tdBR.max(wrRdEdges);
            brUnionPO.max(wrRdEdges);
        }

        // Check for races
        synchronized (var) { // var.lastReads gets printed in error()
            CV otherWrite = var.lastWrites;
            int gtTid = -1;
            while ((gtTid = otherWrite.nextGt(brUnionPO, gtTid + 1)) != -1) {
                if (noCommonLock(var, td, ShadowThread.get(gtTid), true)) {
                    error(fae, "write by T", gtTid, var.lastWriteLocs[gtTid], "read by T", td.getTid());
                    // Draw an edge whenever a race is detected
                    tdHB.max(otherWrite);
                    tdBR.max(otherWrite);
                    if (BUILD_EVENT_GRAPH) EventNode.addEdge(var.lastWriteLocs[gtTid].eventNode, ts_get_br_last_event(td));
                }
            }

            // Store the last read information for others to race check
            var.heldLocksRead.put(td, new HashSet<>(td.getLocksHeld()));
            var.lastReads.set(td.getTid(), brUnionPO.get(td.getTid()));
            if (BUILD_EVENT_GRAPH) {
                var.lastReadLocs[td.getTid()] = new DynamicSourceLocation(fae, fae.getAccessInfo().getLoc().getMethod(), ts_get_br_last_event(td));
                setLastWriter(td, var);
            } else {
                var.lastReadLocs[td.getTid()] = new DynamicSourceLocation(fae, fae.getAccessInfo().getLoc().getMethod());
            }
        }

        for (int i = td.getNumLocksHeld() - 1; i >= 0; i--) {
            BRLockData lock = get(td.getHeldLock(i));
            lock.readVars.add(var);
        }
        int numLocksHeld = td.getNumLocksHeld();
        if (numLocksHeld > 0) {
            String sourceLocation = getSourceLocation(fae);

            // for wr-rd edges
            if (wrRdEdges.anyGt(tdBR)) { // only add it if there is an actual wr-rd edge
                Map<ShadowVar, SourceLocWithNode> reads = ts_get_reads(td);
                reads.put(var, new SourceLocWithNode(sourceLocation, BUILD_EVENT_GRAPH ? ts_get_br_last_event(td) : null));
            }

            for (int iLock = 0; iLock < numLocksHeld; iLock++) {
                // for rd-wr edges
                BRLockData lock = get(td.getHeldLock(iLock));
                lock.unprocessedReadVars.put(var, sourceLocation);
            }
        }
    }

    @Override
    protected void handleWrite(AccessEvent fae, BRGuardBase baseVar, ShadowThread td) {
        BRGuardState var = (BRGuardState) baseVar;
        final CV tdHB = ts_get_br_hb(td);
        CV tdBR = ts_get_br(td);
        CV brUnionPO = new CV(tdBR);
        brUnionPO.set(td.getTid(), tdHB.get(td.getTid()));

        if (RR.brStrict.get()) { // Drawing edges release to access
            // TODO: Fix strict rd-wr edges
        }

        // Add wr-wr edges if enabled
        if (RR.brWrWrEdges.get()) {
            // TODO: Fix wr-wr edges (and strict wr-wr edges)
        }

        // Check for races
        synchronized (var) {
            CV otherWrite = var.lastWrites;
            int gtTid = -1;
            while ((gtTid = otherWrite.nextGt(brUnionPO, gtTid + 1)) != -1) {
                if (noCommonLock(var, td, ShadowThread.get(gtTid), true)) {
                    error(fae, "write by T", gtTid, var.lastWriteLocs[gtTid], "write by T", td.getTid());
                    tdHB.max(otherWrite);
                    tdBR.max(otherWrite);
                }
            }
            CV otherRead = var.lastReads;
            gtTid = -1;
            while ((gtTid = otherRead.nextGt(brUnionPO, gtTid + 1)) != -1) {
                if (noCommonLock(var, td, ShadowThread.get(gtTid), false)) {
                    error(fae, "read by T", gtTid, var.lastReadLocs[gtTid], "write by T", td.getTid());
                    tdHB.max(otherRead);
                    tdBR.max(otherRead);
                }
            }

            // Store the last read information for others to race check
            var.heldLocksWrite.put(td, new HashSet<>(td.getLocksHeld()));
            var.lastWrites.set(td.getTid(), brUnionPO.get(td.getTid()));
            if (BUILD_EVENT_GRAPH) {
                var.lastWriteLocs[td.getTid()] = new DynamicSourceLocation(fae, fae.getAccessInfo().getLoc().getMethod(), ts_get_br_last_event(td));
                updateLastWriter(td, var);
            } else {
                var.lastWriteLocs[td.getTid()] = new DynamicSourceLocation(fae, fae.getAccessInfo().getLoc().getMethod());
            }
        }

        for (int i = td.getNumLocksHeld() - 1; i >= 0; i--) {
            BRLockData lock = get(td.getHeldLock(i));
            lock.writeVars.add(var);
        }
    }

    @Override
    protected void handleBranch(ShadowThread td, String sourceLocation) {

        CV tdHB = ts_get_br_hb(td);
        CV tdBR = ts_get_br(td);
        Map<ShadowVar, SourceLocWithNode> reads = ts_get_reads(td);

        // Draw the wr-rd edges that were delayed
        for (Iterator<Map.Entry<ShadowVar, SourceLocWithNode>> iterator = reads.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<ShadowVar, SourceLocWithNode> read = iterator.next();
            if (SDG.dependsOn(read.getValue().loc, sourceLocation, super.missbranch, super.missread, td.getTid())) {
                if (BUILD_EVENT_GRAPH) ((BranchNode)ts_get_br_last_event(td)).dependsOn.add((RdWrNode) read.getValue().node);
                ShadowVar var = read.getKey();
                drawWrRdEdge(td, tdHB, tdBR, var);
                // Remove processed reads
                iterator.remove();
            }
        }
        // Prepare for rd-wr edges
        for (int iLock = 0; iLock < td.getNumLocksHeld(); iLock++) {
            BRLockData lock = get(td.getHeldLock(iLock));
            lock.branchSources.add(sourceLocation);
        }
    }

    @Override
    protected void handleVolatileAccess(VolatileAccessEvent fae) {
        ShadowThread td = fae.getThread();
        BRVolatileData vd = get(fae.getShadowVolatile());
        if (fae.isWrite()) {
            CV hb = ts_get_br_hb(td);
            vd.hb.max(hb);
            vd.br.max(ts_get_br(td));
            hb.inc(td.getTid());
        } else {
            // A volatile write-read edge is a hard BR edge, but we'll wait until a branch to draw it
            Map<ShadowVar, SourceLocWithNode> reads = ts_get_reads(td);
            reads.put(vd, new SourceLocWithNode(getSourceLocation(fae), BUILD_EVENT_GRAPH ? ts_get_br_last_event(td) : null));
        }
    }

    private static void drawWrRdEdge(ShadowThread td, CV hb, CV br, ShadowVar var) {
        if (var instanceof BRGuardState) {
            BRGuardState readVar = (BRGuardState) var;
            CV delayedWr = readVar.brDelayedWr.get(td);
            if (delayedWr != null) br.max(delayedWr);
        } else if (var instanceof BRVolatileData) {
            BRVolatileData readVar = (BRVolatileData) var;
            hb.max(readVar.hb);
            br.max(readVar.br);
        }
    }

    public static String toString(final ShadowThread td) {
        return String.format("[tid=%-2d   hb=%s   br=%s]", td.getTid(), ts_get_br_hb(td), ts_get_br(td));
    }

    @Override
    public void printXML(XMLWriter xml) {
        for (ShadowThread td : ShadowThread.getThreads()) {
            xml.print("thread %s", toString(td));
        }
    }
}
