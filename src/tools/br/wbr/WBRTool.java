package tools.br.wbr;

import acme.util.Assert;
import acme.util.decorations.Decoration;
import acme.util.decorations.DecorationFactory;
import acme.util.decorations.DefaultValue;
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
import rr.tool.Tool;
import tools.br.*;
import tools.br.event.*;


import java.util.*;

@Abbrev("WBR")
public class WBRTool extends BRToolBase {
    public WBRTool(String name, Tool next, CommandLine commandLine) {
        super(name, next, commandLine);
    }

    @Override
    protected String getToolName() {
        return "WBR";
    }


    private static final Decoration<ShadowLock, WBRLockData> wbrLockData = ShadowLock.makeDecoration("BR:ShadowLock", DecorationFactory.Type.MULTIPLE,
            (DefaultValue<ShadowLock, WBRLockData>) WBRLockData::new);

    private static WBRLockData get(final ShadowLock ld) {
        return wbrLockData.get(ld);
    }

    static private Integer getThreadEpoch(ShadowThread td) {
        return ts_get_wbr(td).get(td.getTid());
    }

    @Override
    public void init() {
        super.init();
        getBaseLock = WBRTool::get;
        getThreadEpoch = WBRTool::getThreadEpoch;
    }

    private static final Decoration<ShadowVolatile,BRVolatileData> brVolatileData = ShadowVolatile.makeDecoration("BR:shadowVolatile", DecorationFactory.Type.MULTIPLE,
            (DefaultValue<ShadowVolatile, BRVolatileData>) BRVolatileData::new);

    private static BRVolatileData get(final ShadowVolatile ld) {
        return brVolatileData.get(ld);
    }

    private static CV ts_get_wbr(ShadowThread ts) { Assert.panic("Bad");	return null; }
    private static void ts_set_wbr(ShadowThread ts, CV cv) { Assert.panic("Bad");  }


    @Override
    public ShadowVar makeShadowVar(AccessEvent ae) {
        if (ae.getKind() == AccessEvent.Kind.VOLATILE) {
            get(((VolatileAccessEvent)ae).getShadowVolatile());
            return super.makeShadowVar(ae);
        } else {
            return new WBRGuardState();
        }
    }


    @Override
    protected void handleCreate(NewThreadEvent e, ShadowThread currentThread) {
        CV wbr = ts_get_wbr(currentThread);

        if (wbr == null) {
            wbr = new CV(INIT_CV_SIZE);
            wbr.inc(currentThread.getTid());
            ts_set_wbr(currentThread, wbr);

            ts_set_reads(currentThread, new HashMap<>());
        }
    }

    @Override
    protected void handleExit(ShadowThread main, ShadowThread joining) {
        ts_get_wbr(main).max(ts_get_wbr(joining));
    }

    @Override
    protected boolean isFirstEvent(ShadowThread thread) {
        return ts_get_wbr(thread).get(0) == 0;
    }

    @Override
    protected void drawHardEdge(ShadowThread from, ShadowThread to) {
        ts_get_wbr(to).max(ts_get_wbr(from));
        // Every caller to drawHardEdge handles event graph edges themselves
    }



    @Override
    protected void handleAcquire(ShadowThread td, ShadowLock shadowLock, boolean isHardEdge) {
        //TODO: The lockData is protected by the lock corresponding to the shadowLock being currently held
        final WBRLockData lockData = get(shadowLock);
        
        if (BUILD_EVENT_GRAPH) {
        	// Establishes observed order of critical sections for event nodes
        	AcqRelNode currentEvent = (AcqRelNode) ts_get_br_last_event(td);
			currentEvent.eventNumber = lockData.latestRelNode == null ? currentEvent.eventNumber : Math.max(currentEvent.eventNumber, lockData.latestRelNode.eventNumber+1);
        }
        
        if (VERBOSE) Assert.assertTrue(lockData.readVars.isEmpty() && lockData.writeVars.isEmpty());
        CV wbr = ts_get_wbr(td);
        CV wbrClone = new CV(wbr);

        if (isHardEdge) {
            wbr.max(lockData.wbr);
            if (BUILD_EVENT_GRAPH) ts_get_br_event_clock(td).max(lockData.eventClock);
        }

        // Rule (b) queues
        for (ShadowThread otherTD : ShadowThread.getThreads()) {
            if (otherTD != td) {
                PerThreadQueue<CV> ptQueue = lockData.wbrAcqQueueMap.get(otherTD);
                if (ptQueue == null) {
                    ptQueue = lockData.wbrAcqQueueGlobal.clone();
                    lockData.wbrAcqQueueMap.put(otherTD, ptQueue);
                }
                ptQueue.addLast(td, wbrClone);
            }
        }

        // Rule (b) queues threads that get created in the future
        PerThreadQueue<CV> acqPTQueue = lockData.wbrAcqQueueMap.get(td);
        if (acqPTQueue == null) {
            acqPTQueue = lockData.wbrAcqQueueGlobal.clone();
            lockData.wbrAcqQueueMap.put(td, acqPTQueue);
        }
        PerThreadQueue<CV> relPTQueue = lockData.wbrRelQueueMap.get(td);
        if (relPTQueue == null) {
            relPTQueue = lockData.wbrRelQueueGlobal.clone();
            lockData.wbrRelQueueMap.put(td, relPTQueue);
        }
        lockData.wbrAcqQueueGlobal.addLast(td, wbrClone); // Also add to the queue that we'll use for any threads that haven't been created yet
    }

    @Override
    protected void handleRelease(ShadowThread td, ShadowLock shadowLock) {
        WBRLockData lockData = get(shadowLock);

        EventClock eventClock = null;
        if (BUILD_EVENT_GRAPH) {
            eventClock = ts_get_br_event_clock(td);
            lockData.eventClock.max(eventClock);
            lockData.latestRelNode = ts_get_br_last_event(td);
        }
        
        CV wbr = ts_get_wbr(td);

        // Rule (b)
        PerThreadQueue<CV> acqPTQueue = lockData.wbrAcqQueueMap.get(td);
        if (VERBOSE) Assert.assertTrue(acqPTQueue.isEmpty(td));
        PerThreadQueue<CV> relPTQueue = lockData.wbrRelQueueMap.get(td);
        for (ShadowThread otherTD : ShadowThread.getThreads()) {
            if (otherTD != td) {
                while (!acqPTQueue.isEmpty(otherTD) && !acqPTQueue.peekFirst(otherTD).anyGt(wbr)) {
                    acqPTQueue.removeFirst(otherTD);
                    CV prevRel = relPTQueue.removeFirst(otherTD);
                    wbr.max(prevRel);
                }
            }
        }

        // Maintain BR time of the last write to each variable
        for (ShadowVar var : lockData.writeVars) {
            CV cv = lockData.wbrWriteMap.put(var, new CV(wbr));
            if (BUILD_EVENT_GRAPH) { // Maintain DC event clock of the last write to each variable
                lockData.wbrWriteEventMap.put(var, new EventClock(eventClock));
            }
        }

        // Maintain WBR time of last release on the lock (used for hard edges on acquires)
        lockData.wbr.max(wbr);

        // rule (b) release queues
        CV wdcCVE = new CV(wbr);
        for (ShadowThread otherTD : ShadowThread.getThreads()) {
            if (otherTD != td) {
                PerThreadQueue<CV> queue = lockData.wbrRelQueueMap.get(otherTD);
                if (queue == null) {
                    queue = lockData.wbrRelQueueGlobal.clone();
                    lockData.wbrRelQueueMap.put(otherTD, queue);
                }
                queue.addLast(td, wdcCVE);
            }
        }
        lockData.wbrRelQueueGlobal.addLast(td, wdcCVE); // Also add to the queue that we'll use for any threads that haven't been created yet

        // Clear readVars and writeVars
        lockData.readVars = new HashSet<>();
        lockData.unprocessedReadVars = new HashMap<>();
        lockData.writeVars = new HashSet<>();
        lockData.branchSources = new HashSet<>();
        lockData.wbrWriteMap = getPotentiallyShrunkMap(lockData.wbrWriteMap);
        if (BUILD_EVENT_GRAPH) lockData.wbrWriteEventMap = getPotentiallyShrunkMap(lockData.wbrWriteEventMap);

        wbr.inc(td.getTid());
    }

    @Override
    protected void handleClassInit(ShadowThread thread, ClassInfo classInfo) {
        BRVolatileData initTime = classInitTime.get(classInfo);
        ts_get_wbr(thread).max(initTime.br);
        /* TODO:
        if (BUILD_EVENT_GRAPH) {
            if (initTime.wdc.anyGt(tdWDC)) {
                EventNode.addEdge(initTime.wdc.eventNode, thisEventNode);
            }
        }
        */
    }

    @Override
    protected void handleRead(AccessEvent fae, BRGuardBase baseVar, ShadowThread td) {
        final EventNode currentEvent = ts_get_br_last_event(td);
        CV wbr = ts_get_wbr(td);
        EventClock eventClock = ts_get_br_event_clock(td);

        WBRGuardState var = (WBRGuardState)baseVar;

        // Compute wr-rd edges, delay them
//        CV wrRdEdges = new CV(BRToolBase.INIT_CV_SIZE);
//        EventClock wrRdEventEdge = null;
//        if (BUILD_EVENT_GRAPH) {
//            wrRdEventEdge = new EventClock();
//        }
//        for (int i = td.getNumLocksHeld() - 1; i >= 0; i--) {
//            ShadowLock lock = td.getHeldLock(i);
//            WBRLockData lockData = get(lock);
//            CV write = lockData.wbrWriteMap.get(var);
//            if (write != null) {
//                wrRdEdges.max(write);
//                if (BUILD_EVENT_GRAPH) { // Compute event clock like DC. Note that we don't draw edges yet.
//                    wrRdEventEdge.max(lockData.wbrWriteEventMap.get(var));
//                }
//            }
//        }


        // Check for races
        synchronized (var) {
//            if (BUILD_EVENT_GRAPH) var.brDelayedWrEvents.put(td, wrRdEventEdge);

            // Prepare for any Rule A edges
            if (var.lastWriter != BRGuardBase.NO_LAST_WRITER) {
                ShadowLock ruleACommon = findCommonLock(var, td, ShadowThread.get(var.lastWriter), true);
                if (ruleACommon != null) {
                    WBRLockData commonLock = get(ruleACommon);
                    var.brDelayedWr.put(td, commonLock.wbrWriteMap.get(var));
                    if (BUILD_EVENT_GRAPH) var.brDelayedWrEvents.put(td, commonLock.wbrWriteEventMap.get(var));
                }
            }

            CV lastWrite = var.lastWrites;
            int gtTid = -1;
            while ((gtTid = lastWrite.nextGt(wbr, gtTid + 1)) != -1) {
                boolean noCommonLock = noCommonLock(var, td, ShadowThread.get(gtTid), true);
                if (!noCommonLock) {
                    if (COUNT_EVENT) locksetPreventsRace.inc(td.getTid());
                } else { //race
                    error(fae, "write by T", gtTid, var.lastWriteLocs[gtTid], "read by T", td.getTid());

        			// Record the WBR-race for later vindication
                    recordRace(fae, var.lastWriteLocs[gtTid]);

                    // Draw an edge whenever a race is detected
                    wbr.max(lastWrite);
                    if (BUILD_EVENT_GRAPH) {
                        EventNode.addEdge(var.lastWriteLocs[gtTid].eventNode, currentEvent);
                    }
                }
            }

            // Store the last read information for others to race check
            var.heldLocksRead.put(td, new HashSet<>(td.getLocksHeld()));
            var.lastReads.set(td.getTid(), wbr.get(td.getTid()));
            if (BUILD_EVENT_GRAPH) {
            	var.lastReadLocs[td.getTid()] = new DynamicSourceLocation(fae, fae.getAccessInfo().getLoc().getMethod(), currentEvent);
                setLastWriter(td, var);
            } else {
            	var.lastReadLocs[td.getTid()] = new DynamicSourceLocation(fae, fae.getAccessInfo().getLoc().getMethod());
            }
        }

        for (int i = td.getNumLocksHeld() - 1; i >= 0; i--) {
            WBRLockData lock = get(td.getHeldLock(i));
            lock.readVars.add(var);
        }
        int numLocksHeld = td.getNumLocksHeld();
        if (numLocksHeld > 0) {
            String sourceLocation = getSourceLocation(fae);

            // TODO: removed condition to account for branches with dependent reads that have no latest write. Please check for correctness
            Map<ShadowVar, SourceLocWithNode> reads = ts_get_reads(td);
            reads.put(var, new SourceLocWithNode(sourceLocation, ts_get_br_last_event(td)));
//            // for wr-rd edges
//            if (wrRdEdges.anyGt(wbr)) { // only add it if there is an actual wr-rd edge
//                Map<ShadowVar, SourceLocWithNode> reads = ts_get_reads(td);
//                reads.put(var, new SourceLocWithNode(sourceLocation, ts_get_br_last_event(td)));
//            }
        }
    }

    @Override
    protected void handleWrite(AccessEvent fae, BRGuardBase baseVar, ShadowThread td) {
        CV wbr = ts_get_wbr(td);
        WBRGuardState var = (WBRGuardState)baseVar;

        // Check for races
        synchronized (var) {
            CV otherWrite = var.lastWrites;
            int gtTid = -1;
            final EventClock eventClock = ts_get_br_event_clock(td);
            final EventNode currentEvent = ts_get_br_last_event(td);
            while ((gtTid = otherWrite.nextGt(wbr, gtTid + 1)) != -1) {
                boolean noCommonLock = noCommonLock(var, td, ShadowThread.get(gtTid), true);
                if (!noCommonLock) locksetPreventsRace.inc(td.getTid());
                if (noCommonLock) {
                    error(fae, "write by T", gtTid, var.lastWriteLocs[gtTid], "write by T", td.getTid());
        			
                    // Record the WBR-race for later vindication
                    recordRace(fae, var.lastWriteLocs[gtTid]);

                    wbr.max(otherWrite);
                    if (BUILD_EVENT_GRAPH) {
                        EventNode.addEdge(var.lastWriteLocs[gtTid].eventNode, currentEvent);
                    }
                }
            }
            CV otherRead = var.lastReads;
            gtTid = -1;
            while ((gtTid = otherRead.nextGt(wbr, gtTid + 1)) != -1) {
                boolean noCommonLock = noCommonLock(var, td, ShadowThread.get(gtTid), false);
                if (!noCommonLock) locksetPreventsRace.inc(td.getTid());
                if (noCommonLock) {
                    error(fae, "read by T", gtTid, var.lastReadLocs[gtTid], "write by T", td.getTid());
                    
        			// Record the WBR-race for later vindication
                    recordRace(fae, var.lastReadLocs[gtTid]);

                    wbr.max(otherRead);
                    if (BUILD_EVENT_GRAPH) {
                        EventNode.addEdge(var.lastReadLocs[gtTid].eventNode, currentEvent);
                    }
                }
            }

            // Store the last read information for others to race check
            var.heldLocksWrite.put(td, new HashSet<>(td.getLocksHeld()));
            var.lastWrites.set(td.getTid(), wbr.get(td.getTid()));
            if (BUILD_EVENT_GRAPH) {
                var.lastWriteLocs[td.getTid()] = new DynamicSourceLocation(fae, fae.getAccessInfo().getLoc().getMethod(), currentEvent);
                updateLastWriter(td, var);
            } else {
            	var.lastWriteLocs[td.getTid()] = new DynamicSourceLocation(fae, fae.getAccessInfo().getLoc().getMethod());
            }
        }

        for (int i = td.getNumLocksHeld() - 1; i >= 0; i--) {
            WBRLockData lock = get(td.getHeldLock(i));
            lock.writeVars.add(var);
        }
    }

    @Override
    protected void handleBranch(ShadowThread td, String sourceLocation) {
        CV wbr = ts_get_wbr(td);
        Map<ShadowVar, SourceLocWithNode> reads = ts_get_reads(td);

        // Draw the wr-rd edges that were delayed
        EventClock edges = null;
        BranchNode currentEvent = null;
        if (BUILD_EVENT_GRAPH) {
            edges = new EventClock();
            currentEvent = (BranchNode) ts_get_br_last_event(td);
        }
        for (Iterator<Map.Entry<ShadowVar, SourceLocWithNode>> iterator = reads.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<ShadowVar, SourceLocWithNode> read = iterator.next();
            if (SDG.dependsOn(read.getValue().loc, sourceLocation,super.missbranch, super.missread, td.getTid())) {
                if (BUILD_EVENT_GRAPH) {
                    currentEvent.dependsOn.add((RdWrNode) read.getValue().node);
                }
                ShadowVar var = read.getKey();
                drawWrRdEdge(td, wbr, var, edges);
                // Remove processed reads
                iterator.remove();
            }
        }
        if (BUILD_EVENT_GRAPH) {
            for (EventNode dcOrdered : edges) {
                EventNode.addEdge(dcOrdered, currentEvent);
            }
        }
    }

    private static void drawWrRdEdge(ShadowThread td, CV wbr, ShadowVar var, EventClock edges) {
        if (var instanceof WBRGuardState) {
            WBRGuardState readVar = (WBRGuardState) var;
            CV delayedWr = readVar.brDelayedWr.get(td);
            if (delayedWr != null) {
                wbr.max(delayedWr);
                readVar.brDelayedWr.remove(td);
                if (BUILD_EVENT_GRAPH) {
                    edges.max(readVar.brDelayedWrEvents.get(td));
                    readVar.brDelayedWrEvents.remove(td);
                }
            }
        } else if (var instanceof BRVolatileData) {
            BRVolatileData readVar = (BRVolatileData) var;
            wbr.max(readVar.br);
            if (BUILD_EVENT_GRAPH) EventNode.addEdge(readVar.lastWriter, ts_get_br_last_event(td));
        }
    }

    @Override
    protected void handleVolatileAccess(VolatileAccessEvent fae) {
        ShadowThread td = fae.getThread();
        BRVolatileData vd = get(fae.getShadowVolatile());
        if (fae.isWrite()) {
            CV wbr = ts_get_wbr(td);

            vd.br.max(wbr); // WBR
            updateLastWriter(td, vd);
        } else {
            setLastWriter(td, vd);
            // A volatile write-read edge is a hard BR edge, but we'll wait until a branch to draw it
            Map<ShadowVar, SourceLocWithNode> reads = ts_get_reads(td);
            reads.put(vd, new SourceLocWithNode(getSourceLocation(fae), ts_get_br_last_event(td)));
        }
    }

    @Override
    protected void handleClassInitialized(ShadowThread thread, ClassInitializedEvent e) {
        classInitTime.get(e.getRRClass()).br.max(ts_get_wbr(thread));
    }
}
