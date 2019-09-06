package tools.br;

import acme.util.Assert;
import acme.util.Util;
import acme.util.Yikes;
import acme.util.count.ThreadLocalCounter;
import acme.util.decorations.Decoration;
import acme.util.decorations.DecorationFactory;
import acme.util.decorations.DecorationFactory.Type;
import acme.util.decorations.DefaultValue;
import acme.util.decorations.NullDefault;
import acme.util.identityhash.WeakIdentityHashMap;
import acme.util.option.CommandLine;
import rr.barrier.BarrierEvent;
import rr.barrier.BarrierListener;
import rr.barrier.BarrierMonitor;
import rr.error.ErrorMessage;
import rr.error.ErrorMessages;
import rr.event.*;
import rr.event.AccessEvent.Kind;
import rr.instrument.classes.ArrayAllocSiteTracker;
import rr.meta.*;
import rr.org.objectweb.asm.Opcodes;
import rr.state.ShadowLock;
import rr.state.ShadowThread;
import rr.state.ShadowVar;
import rr.tool.RR;
import rr.tool.Tool;
import tools.br.br.BRGuardState;
import tools.br.event.*;
import tools.br.sourceinfo.SDG;
import tools.br.sourceinfo.SDGI;
import tools.br.sourceinfo.SDGNoOp;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Function;


public abstract class BRToolBase extends Tool implements BarrierListener<BRBarrierState>, Opcodes {

	public static final int INIT_CV_SIZE = 4;
	private static final ThreadLocalCounter total = new ThreadLocalCounter("BR", "Total Events", RR.maxTidOption.get());
	private static final ThreadLocalCounter exit = new ThreadLocalCounter("BR", "Exit", RR.maxTidOption.get());
	private static final ThreadLocalCounter fake_fork = new ThreadLocalCounter("BR", "Fake Fork", RR.maxTidOption.get());
	private static final ThreadLocalCounter acquire = new ThreadLocalCounter("BR", "Acquire", RR.maxTidOption.get());
	private static final ThreadLocalCounter release = new ThreadLocalCounter("BR", "Release", RR.maxTidOption.get());
	private static final ThreadLocalCounter write = new ThreadLocalCounter("BR", "Write", RR.maxTidOption.get());
	private static final ThreadLocalCounter read = new ThreadLocalCounter("BR", "Read", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeFP = new ThreadLocalCounter("BR", "WriteFastPath", RR.maxTidOption.get());
	private static final ThreadLocalCounter readFP = new ThreadLocalCounter("BR", "ReadFastPath", RR.maxTidOption.get());
	private static final ThreadLocalCounter branchFP = new ThreadLocalCounter("BR", "BranchFastPath", RR.maxTidOption.get());
	private static final ThreadLocalCounter volatile_write = new ThreadLocalCounter("BR", "Volatile Write", RR.maxTidOption.get());
	private static final ThreadLocalCounter volatile_read = new ThreadLocalCounter("BR", "Volatile Read", RR.maxTidOption.get());
	private static final ThreadLocalCounter start = new ThreadLocalCounter("BR", "Start", RR.maxTidOption.get());
	private static final ThreadLocalCounter join = new ThreadLocalCounter("BR", "Join", RR.maxTidOption.get());
	private static final ThreadLocalCounter preWait = new ThreadLocalCounter("BR", "Pre Wait", RR.maxTidOption.get());
	private static final ThreadLocalCounter postWait = new ThreadLocalCounter("BR", "Post Wait", RR.maxTidOption.get());
	private static final ThreadLocalCounter class_init = new ThreadLocalCounter("BR", "Class Initialized", RR.maxTidOption.get());
	private static final ThreadLocalCounter class_accessed = new ThreadLocalCounter("BR", "Class Accessed", RR.maxTidOption.get());
	private static final ThreadLocalCounter example = new ThreadLocalCounter("BR", "Total Example Events", RR.maxTidOption.get());
	private static final ThreadLocalCounter branch = new ThreadLocalCounter("BR", "Branch", RR.maxTidOption.get());
	protected static final ThreadLocalCounter locksetPreventsRace = new ThreadLocalCounter("BR", "Lockset Filtered Races", RR.maxTidOption.get());
	
	protected static final ThreadLocalCounter missbranch = new ThreadLocalCounter("BR", "BranchMiss", RR.maxTidOption.get());
	protected static final ThreadLocalCounter missread = new ThreadLocalCounter("BR", "ReadAccessMiss", RR.maxTidOption.get());

	/** Sets the last writer for a read. Must be called while holding read's lock. */
	protected static void setLastWriter(ShadowThread td, BRGuardBase read) {
		RdWrNode currentEvent = (RdWrNode) ts_get_br_last_event(td);
		if (read.lastWriter == BRGuardBase.NO_LAST_WRITER) {
			currentEvent.setHasNoLastWriter();
		} else {
			currentEvent.setLastWriter((RdWrNode) read.lastWriteLocs[read.lastWriter].eventNode);
		}
	}

    protected static void setLastWriter(ShadowThread td, BRVolatileData read) {
        RdWrNode currentEvent = (RdWrNode) ts_get_br_last_event(td);
        if (read.lastWriter == null) {
            currentEvent.setHasNoLastWriter();
        } else {
			currentEvent.setLastWriter(read.lastWriter);
		}
    }

	protected static void updateLastWriter(ShadowThread td, BRGuardBase write) {
		write.lastWriter = td.getTid();
	}

    protected static void updateLastWriter(ShadowThread td, BRVolatileData write) {
        write.lastWriter = (VolatileRdWrNode) ts_get_br_last_event(td);
    }


	// We do the following trick, because we need these methods from subclasses in readFastPath/writeFastPath
	// which are static methods, and we can't have abstract static methods that are overwritten by child classes.
	/** A reference to the get() method of the subclass. */
	protected static Function<ShadowLock, BRLockBase> getBaseLock = null;
	/** What is the epoch for the given thread? */
	protected static Function<ShadowThread, Integer> getThreadEpoch = null;

	protected abstract String getToolName();

	public final ErrorMessage<FieldInfo> fieldErrors = ErrorMessages.makeFieldErrorMessage(getToolName());
	public final ErrorMessage<ArrayAccessInfo> arrayErrors = ErrorMessages.makeArrayErrorMessage(getToolName());

	protected static SDGI SDG;

	protected static boolean PRINT_EVENT;
	protected static boolean COUNT_EVENT = true;
	protected static final boolean VERBOSE = false;
	private static boolean NO_SDG = true;
	public static boolean BUILD_EVENT_GRAPH;

	// Can use the same data for class initialization synchronization as for volatiles
	protected static final Decoration<ClassInfo,BRVolatileData> classInitTime = MetaDataInfoMaps.getClasses().makeDecoration("WDC:InitTime", Type.MULTIPLE,
			(DefaultValue<ClassInfo, BRVolatileData>) t -> new BRVolatileData(null));

	// We only maintain the "last event" if BUILD_EVENT_GRAPH == true
	protected static EventNode ts_get_br_last_event(ShadowThread ts) { Assert.panic("Bad"); return null; }
	protected static void ts_set_br_last_event(ShadowThread ts, EventNode eventNode) { Assert.panic("Bad"); }

    // Event clocks of threads, only if BUILD_EVENT_GRAPH == true

    protected static EventClock ts_get_br_event_clock(ShadowThread ts) { Assert.panic("Bad"); return null; }
    protected static void ts_set_br_event_clock(ShadowThread ts, EventClock eventNode) { Assert.panic("Bad"); }

	// Maintain the a stack of current held critical sections per thread
	protected static Stack<AcqRelNode> ts_get_br_held_locks(ShadowThread ts) { Assert.panic("Bad"); return null; }
	protected static void ts_set_br_held_locks(ShadowThread ts, Stack<AcqRelNode> heldLocks) { Assert.panic("Bad"); }


	//Tid -> Stack of ARNode
	AcqRelNode topLock(ShadowThread td) {
		Stack<AcqRelNode> locksHeld = ts_get_br_held_locks(td);
		if (locksHeld == null) {
			locksHeld = new Stack<>();
			ts_set_br_held_locks(td, locksHeld);
		}
		return locksHeld.isEmpty() ? null : locksHeld.peek();
	}

	void pushLock(ShadowThread td, AcqRelNode acqNode) {
		ts_get_br_held_locks(td).push(acqNode);
	}

	AcqRelNode popLock(ShadowThread td) {
		return ts_get_br_held_locks(td).pop();
	}


	public BRToolBase(final String name, final Tool next, CommandLine commandLine) {
		super(name, next, commandLine);
		new BarrierMonitor<>(this, (DefaultValue<Object, BRBarrierState>) k -> new BRBarrierState(ShadowLock.get(k)));
	}


	// Maps variables to their last reads by thread `ts`
	protected static Map<ShadowVar, SourceLocWithNode> ts_get_reads(ShadowThread ts) { Assert.panic("Bad"); return null; }
	protected static void ts_set_reads(ShadowThread ts, Map<ShadowVar, SourceLocWithNode> reads) { Assert.panic("Bad");  }


	@Override
	public void create(NewThreadEvent e) {
		ShadowThread currentThread = e.getThread();
		synchronized(currentThread) {
		    if (BUILD_EVENT_GRAPH) {
                ts_set_br_event_clock(currentThread, new EventClock());
            }
			handleCreate(e, currentThread);
		}
		super.create(e);
	}

	protected abstract void handleCreate(NewThreadEvent e, ShadowThread currentThread);


	@Override
	public final void exit(MethodEvent me) {
		ShadowThread td = me.getThread();
		//TODO: Excessive exits satisfy this condition since the class associated with the original call can not be found -- still true?
		if (td.getParent() == null && td.getTid() != 0 /*not the main thread*/ && !td.getThread().getName().equals("Finalizer")) {
			String methodName = me.getInfo().getName();
			Object target = me.getTarget();
			if ((methodName.equals("call") && target instanceof Callable) ||
					(methodName.equals("run")  && target instanceof Runnable)) {
				//(me.getInfo().toSimpleName().contains(".call") || me.getInfo().toSimpleName().contains(".run"))) {
				synchronized(td) {
					if (COUNT_EVENT) total.inc(td.getTid());
					if (COUNT_EVENT) exit.inc(td.getTid());
					//Get the main thread
					//TODO: Main thread is being accessed by another thread
					ShadowThread main = ShadowThread.get(0);
					synchronized(main) { //Will this deadlock? Main should never be waiting on any other thread so I don't think so.
						if (BUILD_EVENT_GRAPH) {
							//Build this thread's event node
							AcqRelNode currentLock = topLock(td);
							EventNode currentEvent = new EventNode(-2, -1, td.getTid(), currentLock, "join [exit join]");
							handleEvent(me, currentEvent);
							if (EventNode.VERBOSE_GRAPH) Assert.assertTrue(currentEvent.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));
							//Build dummy eventNode
							if (COUNT_EVENT) total.inc(td.getTid());
							AcqRelNode mainLock = topLock(main);
							EventNode mainPrior = ts_get_br_last_event(main);
							EventNode exitEvent = new EventNode(mainPrior.eventNumber+1, -1, main.getTid(), mainLock, "exit [dummy event]");
							//PO last event node in main to dummy node
							EventNode.addEdge(mainPrior, exitEvent);
							ts_set_br_last_event(main, exitEvent);
							//Create a hard edge from thisEventNode to dummy node
							EventNode.addEdge(currentEvent, exitEvent);
						} else {
							handleEvent(me, null);
						}
					}

					if (PRINT_EVENT) Util.log("exit "+me.getInfo().toSimpleName()+" by T"+td.getTid());

					handleExit(main, td);
				}
			}
		}
		super.exit(me);
	}

	protected abstract void handleExit(ShadowThread main, ShadowThread joining);


	@Override
	public final void stop(ShadowThread td) {
		super.stop(td);
	}


	@Override
	public void init() {
		COUNT_EVENT = true;
		PRINT_EVENT = RR.printEventOption.get();
		String sdgClassName = RR.brSDGClassName.get();
		String projectPath = RR.brProjectPath.get();
		BUILD_EVENT_GRAPH = RR.wdcBuildEventGraph.get();

		NO_SDG = RR.brNoSDG.get();
		if (NO_SDG && sdgClassName != null) {
			Assert.fail("SDG usage was disabled, but an SDG file was provided. Check %s and %s options.", RR.brSDGClassName.getId(), RR.brNoSDG.getId());
		}
		if (!NO_SDG && sdgClassName == null) {
			Assert.fail("SDG usage was enabled, but an SDG file was NOT provided. Check %s and %s options.", RR.brSDGClassName.getId(), RR.brNoSDG.getId());
		}

		if (NO_SDG) {
			Util.println("NOT using SDG information");
			SDG = new SDGNoOp();
		} else {
			SDG = new SDG(sdgClassName, projectPath);
		}

		if (RR.brStrict.get()) {
			Util.println("BR Strict Configuration - release-to-access edges");
		} else if (RR.brConservative.get()) {
			Util.println("BR Conservative Configuration - edges drawn even w/o branches");
		} else {
			Util.println("BR Regular Configuration");
		}
		if (RR.brWrWrEdges.get()) {
			Util.println("BR Write-to-Write edges enabled");
		}
	}


	public static boolean running = true;

	//TODO: Only a single thread should be accessing the list of races and the event graph
	@Override
	public void fini() {
		running = false;
		//Report static and dynamic races counts
		StaticRace.reportRaces();
		
		if (BUILD_EVENT_GRAPH) {
			// Races (based on an identifying string) that we've verified have no cycle.
			// Other dynamic instances of the same static race might have a cycle, but we've already demonstrated that this static race is predictable, so who cares?
			HashSet<StaticRace> verifiedRaces = new HashSet<>();
			Vector<StaticRace> failedRaces = new Vector<>();
			LinkedList<StaticRace> staticOnlyCheck = new LinkedList<>();
			long start = System.currentTimeMillis();
			if (StaticRace.races.size() == 0) return;
			for (int i = 0; i < EventNode.STATIC_RACE_FAIL_THRESHOLD; i++) {
				for (Vector<StaticRace> race : StaticRace.staticRaceMap.get(RaceType.WBRRace).values()) {
					if (race.size() > i) {
						StaticRace instance = race.get(i);
						vindicateRace(instance, verifiedRaces, failedRaces, staticOnlyCheck, true);
					}
				}
			}
			Util.log("Static Race Check Time: " + (System.currentTimeMillis() - start));
			for (StaticRace singleStaticRace : staticOnlyCheck) {
				StaticRace.races.remove(singleStaticRace);
			}
			if (false) { // TODO: Enable when vindication performance issues are resolved
				start = System.currentTimeMillis();
				for (StaticRace wbrDynamicRace : StaticRace.races) {
					vindicateRace(wbrDynamicRace, verifiedRaces, failedRaces, staticOnlyCheck, false);
				}
				Util.log("Dynamic Race Check Time: " + (System.currentTimeMillis() - start));
			}
			Util.logf("Verified races: %d", verifiedRaces.size());
			Util.logf("Failed races: %d", failedRaces.size());
		}
	}
	
	public void vindicateRace(StaticRace race, HashSet<StaticRace> verifiedRaces, Collection<StaticRace> failedRaces, LinkedList<StaticRace> staticOnlyCheck, boolean staticDCRacesOnly) {
		RdWrNode startNode = race.firstNode;
		RdWrNode endNode = race.secondNode;
		String desc = race.raceType + " " + race.description();
		//TODO: race edge between conflicting events is not removed. Is this correct?
		if ((staticDCRacesOnly && !verifiedRaces.contains(race)) || !staticDCRacesOnly) {
			Util.println("Checking " + desc + " for event pair " + startNode + " -> " + endNode + " | distance: " + (endNode.eventNumber - startNode.eventNumber));
			boolean failed = EventNode.crazyNewEdges(startNode, endNode, null);

			if (failed) failedRaces.add(race);

			if (staticDCRacesOnly) {
				staticOnlyCheck.add(race);
				if (!failed) verifiedRaces.add(race);
			}
		}
	}

	private void handleEvent(Event e, EventNode currentEvent) {
		ShadowThread td = e.getThread();

		// Evaluating total.getCount() frequently will lead to lots of cache conflicts, so doing this local check instead.
		if (total.getLocal(td.getTid()) % 1000000 == 1) {
			// This is a rough estimate now since total.getCount() is not thread safe
			Util.println("Handling event " + total.getCount());
		}
        
		if (td.getParent() == null && td.getTid() != 0 /*not main thread*/ && !td.getThread().getName().equals("Finalizer")) {
			if (isFirstEvent(td)) {
				if (PRINT_EVENT) Util.log("parentless fork to T"+td.getTid());
				if (COUNT_EVENT) fake_fork.inc(td.getTid());

				ShadowThread main = ShadowThread.get(0);
				synchronized(main) { //Will this deadlock? Same as exit, I don't think main will lock on this thread.
					drawHardEdge(main, td);

					if (BUILD_EVENT_GRAPH) {
						EventNode forkedEvent = ts_get_br_last_event(main);
						if (EventNode.VERBOSE_GRAPH) {
							Assert.assertFalse(currentEvent == forkedEvent, "Parentless fork causes cycle on self");
							Assert.assertFalse(currentEvent.getThreadId() == forkedEvent.getThreadId(), "Parentless fork draws edge within the same thread");
						}
						EventNode.addEdge(forkedEvent, currentEvent);
					}
				}
			}
		}
		if (BUILD_EVENT_GRAPH) {
			EventNode sourceNode = ts_get_br_last_event(td);
			if (sourceNode != null) {
				EventNode.addEdge(sourceNode, currentEvent);
			}

            ts_set_br_last_event(td, currentEvent);
			EventClock eventClock = ts_get_br_event_clock(td);
			eventClock.set(td.getTid(), currentEvent);
        }
	}

	protected abstract boolean isFirstEvent(ShadowThread thread);

	protected abstract void drawHardEdge(ShadowThread from, ShadowThread to);


	/*acquire*/
	@Override
	public void acquire(final AcquireEvent ae) {
		final ShadowThread td = ae.getThread();
		synchronized(td) {
			final ShadowLock shadowLock = ae.getLock();
			
			if (COUNT_EVENT) {
				//Update has to be done here so that the event nodes have correct partial ordering. 
				//The idea is to use lamport timestamps to create a partial order.
				total.inc(td.getTid()); 
			}
			if (COUNT_EVENT) acquire.inc(td.getTid());
			if (COUNT_EVENT) example.inc(td.getTid());

			AcqRelNode currentEvent = null;
			if (BUILD_EVENT_GRAPH) {
				AcqRelNode inCS = topLock(td);
				if (inCS != null && VERBOSE) Assert.assertTrue(inCS.shadowLock != shadowLock);
				currentEvent = new AcqRelNode(-2, -1, shadowLock, td.getTid(), true, inCS);
				pushLock(td, currentEvent);
			}
			handleEvent(ae, currentEvent);
			handleAcquire(td, shadowLock, false);
		}

		super.acquire(ae);
	}

	protected abstract void handleAcquire(ShadowThread td, ShadowLock shadowLock, boolean isHardEdge);


	/* release*/
	@Override
	public void release(final ReleaseEvent re) {
		final ShadowThread td = re.getThread();
		synchronized(td) {
			final ShadowLock shadowLock = re.getLock();

			if (COUNT_EVENT) total.inc(td.getTid());
			if (COUNT_EVENT) release.inc(td.getTid());
			if (COUNT_EVENT) example.inc(td.getTid());

			AcqRelNode currentEvent = null;
			if (BUILD_EVENT_GRAPH) {
				AcqRelNode inCS = popLock(td);
				if (VERBOSE) Assert.assertTrue(inCS != null);
				currentEvent = new AcqRelNode(-2, -1, shadowLock, td.getTid(), false, inCS);
			}

			handleEvent(re, currentEvent);
			
			//Need to track otherCriticalSection (a releases corresponding acquire event) for Vindication
			//The eventNode must be known so the update needs to occur after handleEvent 
			if (BUILD_EVENT_GRAPH) {
				AcqRelNode myAcqNode = ts_get_br_last_event(td).inCS;
				if (VERBOSE) Assert.assertTrue(myAcqNode.isAcquire());
				if (VERBOSE) Assert.assertTrue(ts_get_br_last_event(td) instanceof AcqRelNode);
				((AcqRelNode)ts_get_br_last_event(td)).otherCriticalSectionNode = myAcqNode;
				myAcqNode.otherCriticalSectionNode = (AcqRelNode) ts_get_br_last_event(td);
			}
			
			handleRelease(td, shadowLock);
		}

		super.release(re);
	}

	protected abstract void handleRelease(ShadowThread td, ShadowLock shadowLock);


	protected static <K,V> WeakIdentityHashMap<K,V>getPotentiallyShrunkMap(WeakIdentityHashMap<K, V> map) {
		if (map.tableSize() > 16 &&
		    10 * map.size() < map.tableSize() * map.loadFactorSize()) {
			return new WeakIdentityHashMap<K,V>(2 * (int)(map.size() / map.loadFactorSize()), map);
		}
		return map;
	}


	/*access*/
	@Override
	public void access(final AccessEvent fae) {
		final ShadowVar orig = fae.getOriginalShadow();
		final ShadowThread td = fae.getThread();

		if (orig instanceof BRGuardBase) {
			BRGuardBase var = (BRGuardBase) orig;
			synchronized(td) { // This synchronization may be too heavy-handed
				if (COUNT_EVENT) {
					total.inc(td.getTid());
					if (fae.isWrite()) {
						write.inc(td.getTid());
					} else {
						read.inc(td.getTid());
					}
					example.inc(td.getTid());
				}

				String fieldName = "???";
				if (PRINT_EVENT || BUILD_EVENT_GRAPH) {
					if (EventNode.DEBUG_ACCESS_INFO) {
						if (fae instanceof FieldAccessEvent) {
							fieldName = ((FieldAccessEvent)fae).getInfo().getField().getName();
						} else if (fae instanceof ArrayAccessEvent) {
							fieldName = Util.objectToIdentityString(fae.getTarget()) + "[" + ((ArrayAccessEvent)fae).getIndex() + "]";
						}
					}
				}

				RdWrNode currentEvent = null;
				if (BUILD_EVENT_GRAPH) {
					AcqRelNode inCS = topLock(td);
					if (EventNode.DEBUG_ACCESS_INFO) {
						currentEvent = new RdWrDebugNode(-2, -1, fae.isWrite(), fieldName, var, td.getTid(), inCS);
					} else {
						currentEvent = new RdWrNode(-2, -1, fae.isWrite(), var, td.getTid(), inCS);
					}
				}

				handleEvent(fae, currentEvent);

				// Even though we capture clinit edges via classAccessed(), it doesn't seem to capture quite everything.
				// In any case, FT2 also does the following in access() in addition to classAccessed().
				//TODO: Need to lock on classInitTime?
				Object target = fae.getTarget();
				if (target == null) {
					synchronized(classInitTime) { //Not sure what we discussed for classInit, but FT synchronizes on it so I assume the program executing does not protect accesses to classInit.
						handleClassInit(td, ((FieldAccessEvent)fae).getInfo().getField().getOwner());
					}
				}

				//TODO: merging turned off since conflicting accesses are not ordered by WBR. Can this optimization be used?
				if (false && BUILD_EVENT_GRAPH) {
					// Can combine two consecutive write/read nodes that have the same VC.
					// We might later add an outgoing edge from the prior node, but that's okay.
					EventNode oldEvent = currentEvent;
					currentEvent = currentEvent.tryToMergeWithPrior();
					// If merged, undo prior increment
					if (currentEvent != oldEvent) {
						// TODO: Add hooks for reverting HB clocks? (Also, increment HB clocks before generating the event?)
					}
				}

				if (fae.isWrite()) {
					handleWrite(fae, (BRGuardBase)orig, td);
				} else {
					handleRead(fae, (BRGuardBase)orig, td);
					if (fae.getKind() == Kind.ARRAY) { // Array reads are treated as being followed by branches
						handleBranch(td, (ArrayAccessEvent)fae);
					}
				}

				if (PRINT_EVENT) {
					Util.log((fae.isWrite() ? "wr" : "rd")
							+ "("+ fieldName +") by T"+td.getTid());
				}
			}
		} else {
			if (VERBOSE) Assert.assertTrue(false); // Not expecting to reach here
			super.access(fae);
		}
	}

	protected abstract void handleClassInit(ShadowThread thread, ClassInfo classInfo);


	protected abstract void handleRead(final AccessEvent fae, BRGuardBase var, ShadowThread td);

	public static boolean readFastPath(final ShadowVar orig, final ShadowThread td) {
		// A branch could depend on one read but not the other, for example for reads r1, r2 and branch b;
		// b may depend on r2 but not r1. In that case, we can not skip r2 in fast path. Since we don't have
		// the event object, we don't know the source location and can't decide if something like this may occur.
		// TODO: Is this actually possible? We should check the SDG to see if this can actually happen.
		if (!RR.brNoSDG.get()) return false;

		if (!(orig instanceof BRGuardBase)) return false; // WDCTool expects this to never happen, but we can get rr.simple.LastTool$FinalGuardState here
		BRGuardBase var = (BRGuardBase) orig;
		ShadowLock shadowLock = td.getInnermostLock();
		if (shadowLock != null) {
			BRLockBase lock = getBaseLock.apply(shadowLock);
			if (!lock.readVars.contains(var) && !lock.writeVars.contains(var)) {
				return false;
			}
		}

		synchronized (td) {
			int po = getThreadEpoch.apply(td);
			if (var.lastReads.get(td.getTid()) == po) {
				if (COUNT_EVENT) readFP.inc(td.getTid());
				return true;
			}
		}
		return false;
	}


	protected static String getSourceLocation(AccessEvent event) {
		return getSourceLocation(event.getAccessInfo());
	}

	static String getSourceLocation(BranchEvent event) {
		return getSourceLocation(event.getInfo());
	}

	static String getSourceLocation(MetaDataInfo info) {
		if (NO_SDG) return "";
		return info.getLoc().getSourceLoc();
	}


	protected final boolean noCommonLock(BRGuardBase var, ShadowThread td, ShadowThread otherTD, boolean write) {
		return findCommonLock(var, td, otherTD, write) == null;
	}

	/* Returns outermost common lock */
	protected final ShadowLock findCommonLock(BRGuardBase var, ShadowThread td, ShadowThread otherTD, boolean write) {
		Set<ShadowLock> lastLocks = (write ? var.heldLocksWrite : var.heldLocksRead).get(otherTD);
		for (int i = td.getNumLocksHeld() - 1; i >= 0; i--) {
			ShadowLock lock = td.getHeldLock(i);
			if (lastLocks.contains(lock)) {
				return lock;
			}
		}
		return null;
	}


	protected abstract void handleWrite(final AccessEvent fae, BRGuardBase var, ShadowThread td);

	public static boolean writeFastPath(final ShadowVar orig, final ShadowThread td) {
		if (!(orig instanceof BRGuardBase)) return false; // WDCTool expects this to never happen, but we can get rr.simple.LastTool$FinalGuardState here
		BRGuardBase var = (BRGuardBase) orig;
		ShadowLock shadowLock = td.getInnermostLock();
		if (shadowLock != null) {
			BRLockBase lock = getBaseLock.apply(shadowLock);
				if (!lock.writeVars.contains(var)) {
					return false;
				}
		}

		synchronized(td) {
			int po = getThreadEpoch.apply(td);
			if (var.lastWrites.get(td.getTid()) == po) {
				if (COUNT_EVENT) writeFP.inc(td.getTid());
				return true;
			}
		}
		return false;
	}


	/*branch*/
	@Override
	public void branch(final BranchEvent be) {
		final ShadowThread td = be.getThread();
		if (branchFastPath(td)) {
			if (COUNT_EVENT) {
				branchFP.inc(td.getTid());
			}
			return;
		}

		synchronized(td) {
			if (COUNT_EVENT) {
				//Update has to be done here so that the event nodes have correct partial ordering.
				//The idea is to use lamport timestamps to create a partial order.
				total.inc(td.getTid());
				example.inc(td.getTid());
				branch.inc(td.getTid());
			}
			handleBranch(td, be);

			if (PRINT_EVENT) Util.log("br("+""/*TODO: Anything here?*/+") by T"+td.getTid());
		}

		super.branch(be);
	}

	final void handleBranch(ShadowThread td, ArrayAccessEvent ae) {
		// Array reads are treated as being followed by branches

		EventNode currentEvent = null;
		if (BUILD_EVENT_GRAPH) {
			AcqRelNode inCS = topLock(td);
			currentEvent = new BranchNode(-2, -1, ae.getThread().getTid(), inCS);
		}
		handleEvent(ae, currentEvent);

		handleBranch(td, getSourceLocation(ae));
	}

	final void handleBranch(ShadowThread td, BranchEvent be) {
		EventNode currentEvent = null;
		if (BUILD_EVENT_GRAPH) {
			AcqRelNode inCS = topLock(td);
			currentEvent = new BranchNode(-2, -1, be.getThread().getTid(), inCS);
		}
		handleEvent(be, currentEvent);

		handleBranch(td, getSourceLocation(be));
	}

	protected abstract void handleBranch(ShadowThread td, String sourceLocation);

	// TODO: Implement a real fast path for branches
	private static boolean branchFastPath(ShadowThread td) {
		// If there are no reads to process, handleBranch() will do nothing.
		return ts_get_reads(td).size() == 0;
	}

	@Override
	public void volatileAccess(VolatileAccessEvent fae) {
		if (!(fae.getShadow() instanceof BRVolatileData)) return;
		final ShadowThread td = fae.getThread();
		if (COUNT_EVENT) {
			total.inc(td.getTid());
			if (fae.isWrite()) {
				volatile_write.inc(td.getTid());
			} else {
				volatile_read.inc(td.getTid());
			}
		}

		EventNode currentEvent = null;
		if (BUILD_EVENT_GRAPH) { //Volatiles are treated the same as non-volatile rd/wr accesses since each release does not have a corresponding acquire
			AcqRelNode inCS = topLock(td);
			// TODO: Should we fae.getShadowVolatile() here and change other code to support volatiles instead?
			currentEvent = new VolatileRdWrNode(-2, -1, fae.isWrite(), (BRVolatileData)fae.getShadow(), td.getTid(), inCS);
		}
		handleEvent(fae, currentEvent);

		synchronized(fae.getShadowVolatile()) {
			handleVolatileAccess(fae);
		}

		super.volatileAccess(fae);
	}

	protected abstract void handleVolatileAccess(VolatileAccessEvent fae);
	
	//Record WBR-race for later vindication
	//Note: the eventNode corresponding to the last event of a thread is set before a read or write is handled. So this events eventNode is the executing threads last eventNode 
	protected final void recordRace(AccessEvent fae, DynamicSourceLocation lastVar) {
        StaticRace staticRace;
		if (BUILD_EVENT_GRAPH) {
			ShadowThread td = fae.getThread();
			final MethodEvent me = td.getBlockDepth() <= 0 ? null : td.getBlock(td.getBlockDepth()-1); //This is how RREventGenerator retrieves a method event
			if (VERBOSE) Assert.assertTrue(ts_get_br_last_event(td) instanceof RdWrNode);
			staticRace = new StaticRace(lastVar.getLoc(), fae.getAccessInfo().getLoc(), (RdWrNode)lastVar.eventNode, (RdWrNode)ts_get_br_last_event(td), RaceType.WBRRace, lastVar.eventMI, me.getInfo());
			StaticRace.races.add(staticRace);
		} else {
			staticRace = new StaticRace(lastVar.getLoc(), fae.getAccessInfo().getLoc());
		}
		
		//TODO: RaceType is set to WBR since the WBR tool does not find other race types. RaceType set to WBR when creating new StaticRace while building event graph as well
		StaticRace.addRace(staticRace, RaceType.WBRRace);
	}

	protected final void error(final AccessEvent ae, final String prevOp, final int prevTid, final DynamicSourceLocation prevDL, final String curOp, final int curTid) {
		try {		
			if (ae instanceof FieldAccessEvent) {
				FieldAccessEvent fae = (FieldAccessEvent)ae;
				final FieldInfo fd = fae.getInfo().getField();
				final ShadowThread currentThread = fae.getThread();
				final Object target = fae.getTarget();
				
				fieldErrors.error(currentThread,
						fd,
						"Guard State", 		fae.getOriginalShadow(),
						"Current Thread",				currentThread.getTid(),
						"Class",						target==null?fd.getOwner():target.getClass(),
						"Field",						Util.objectToIdentityString(target) + "." + fd, 
						"Prev Op",						prevOp + prevTid,
						"Prev Loc",						prevDL == null ? "?" : prevDL.loc.getSourceLoc() + " - line " + prevDL.loc.getLine(),
						"Cur Op",						curOp + curTid,
						"Cur Loc",                      fae.getInfo().getLoc().getSourceLoc() + " - line " + fae.getInfo().getLoc().getLine(),
						"Stack",						ShadowThread.stackDumpForErrorMessage(currentThread)
				);
				if (!fieldErrors.stillLooking(fd)) {
					advance(ae);
				}
			} else {
				ArrayAccessEvent aae = (ArrayAccessEvent)ae;
				final ShadowThread currentThread = aae.getThread();
				final Object target = aae.getTarget();

				arrayErrors.error(currentThread,
						aae.getInfo(),
						"Alloc Site", 		ArrayAllocSiteTracker.get(aae.getTarget()),
						"Guard State", 					aae.getOriginalShadow(),
						"Current Thread",				currentThread==null?"":currentThread.getTid(),
						"Array",						Util.objectToIdentityString(target) + "[" + aae.getIndex() + "]",
						"Prev Op",						ShadowThread.get(prevTid)==null?"":prevOp + prevTid + ("name = " + ShadowThread.get(prevTid).getThread().getName()),
						"Prev Loc",						prevDL == null ? "?" : prevDL.loc.getSourceLoc() + " - line " + prevDL.loc.getLine(),
						"Cur Op",						ShadowThread.get(curTid)==null?"":curOp + curTid + ("name = " + ShadowThread.get(curTid).getThread().getName()),
						"Cur Loc",                      aae.getInfo().getLoc().getSourceLoc() + " - line " + aae.getInfo().getLoc().getLine(),
						"Stack",						ShadowThread.stackDumpForErrorMessage(currentThread)
				);
				aae.getArrayState().specialize();
				if (!arrayErrors.stillLooking(aae.getInfo())) {
					advance(aae);
				}
			}
		} catch (Throwable e) {
			Assert.panic(e);
		}
	}

	@Override
	public void preStart(final StartEvent se) {
		final ShadowThread td = se.getThread();
		synchronized(td) {
			
			if (COUNT_EVENT) total.inc(td.getTid());
			if (COUNT_EVENT) start.inc(td.getTid());

			EventNode currentEvent = null;
			if (BUILD_EVENT_GRAPH) { //preStart is handled the same as rd/wr accesses
				AcqRelNode currentLock = topLock(td);
				currentEvent = new EventNode(-2, -1,  td.getTid(), currentLock, "start");
				ts_set_br_last_event(se.getNewThread(), currentEvent);
			}

			handleEvent(se, currentEvent);
			if (PRINT_EVENT) {
				Util.log("preStart by T"+td.getTid());
			}

			drawHardEdge(td, se.getNewThread());
		}

		super.preStart(se);
	}

	@Override
	public void postJoin(final JoinEvent je) {
		final ShadowThread td = je.getThread();
		synchronized(td) {
			
			if (COUNT_EVENT) total.inc(td.getTid());
			if (COUNT_EVENT) join.inc(td.getTid());

			EventNode currentEvent = null;
			if (BUILD_EVENT_GRAPH) { //postJoin is handled the same as rd/wr accesses
				AcqRelNode currentLock = topLock(td);
				currentEvent = new EventNode(-2, -1, td.getTid(), currentLock, "join");
			}
			handleEvent(je, currentEvent);

			//TODO: thread is already joined so there should be no need to lock
			final ShadowThread joining = je.getJoiningThread();

			// this test tells use whether the tid has been reused already or not.  Necessary
			// to still account for stopped thread, even if that thread's tid has been reused,
			// but good to know if this is happening alot...
			if (joining.getTid() == -1) {
				Yikes.yikes("Joined after tid got reused --- don't touch anything related to tid here!");
			}

			if (PRINT_EVENT) {
				Util.log("postJoin by T"+td.getTid());
			}

			drawHardEdge(joining, td);
			if (BUILD_EVENT_GRAPH) EventNode.addEdge(ts_get_br_last_event(joining), currentEvent);
		}

		super.postJoin(je);	
	}


	@Override
	public void preNotify(NotifyEvent we) {
		super.preNotify(we);
	}


	@Override
	public void preWait(WaitEvent we) {
		final ShadowThread td = we.getThread();
		synchronized (td) {
			
			if (COUNT_EVENT) total.inc(td.getTid());
			if (COUNT_EVENT) preWait.inc(td.getTid());

			AcqRelNode currentEvent = null;
			if (BUILD_EVENT_GRAPH) {
				AcqRelNode inCS = topLock(td);
				currentEvent = new AcqRelNode(-2, -1, we.getLock(), td.getTid(), false, inCS);
				popLock(td);
			}
			handleEvent(we, currentEvent);
			if (VERBOSE && BUILD_EVENT_GRAPH) Assert.assertTrue(currentEvent.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));

			if (PRINT_EVENT) {
				Util.log("preWait by T"+td.getTid());
			}
			
			//TODO: lock is already held
			handleRelease(td, we.getLock());
		}
		super.preWait(we);
	}


	@Override
	public void postWait(WaitEvent we) {
		final ShadowThread td = we.getThread();
		synchronized (td) {
			
			if (COUNT_EVENT) total.inc(td.getTid());
			if (COUNT_EVENT) postWait.inc(td.getTid());

			AcqRelNode currentEvent = null;
			if (BUILD_EVENT_GRAPH) {
				AcqRelNode inCS = topLock(td);
				currentEvent = new AcqRelNode(-2, -1, we.getLock(), td.getTid(), true, inCS);
				pushLock(td, currentEvent);
			}
			handleEvent(we, currentEvent);
			if (VERBOSE && BUILD_EVENT_GRAPH) Assert.assertTrue(currentEvent.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));

			if (PRINT_EVENT) {
				Util.log("postWait by T"+td.getTid());
			}
			
			//TODO: lock is already held
			// Considering wait--notify to be a hard WCP and WDC edge.
			// (If wait--notify is used properly, won't it already be a hard edge?)
			handleAcquire(td, we.getLock(), true);
		}

		super.postWait(we);
	}

	private final Decoration<ShadowThread, CV> cvForExit = 
		ShadowThread.makeDecoration("WDC:barrier", DecorationFactory.Type.MULTIPLE, new NullDefault<ShadowThread, CV>());


	public void preDoBarrier(BarrierEvent<BRBarrierState> be) {
		Assert.assertTrue(false); // Does this ever get triggered in our evaluated programs?
//		BRBarrierState wdcBE = be.getBarrier();
//		ShadowThread currentThread = be.getThread();
//		CV entering = wdcBE.getEntering();
//		entering.max(ts_get_br_hb(currentThread));
//		cvForExit.set(currentThread, entering);
	}


	public void postDoBarrier(BarrierEvent<BRBarrierState> be) {
		Assert.assertTrue(false); // Does this ever get triggered in our evaluated programs?
//		BRBarrierState wdcBE = be.getBarrier();
//		ShadowThread currentThread = be.getThread();
//		CV old = cvForExit.get(currentThread);
//		wdcBE.reset(old);
//		ts_get_br_hb(currentThread).max(old);
//		ts_get_br(currentThread).max(old); // Also update WCP since a barrier is basically an all-to-all hard WCP edge
//		//if (!HB_WCP_ONLY) ts_get_wdc(currentThread).max(old); // Updating WDC to HB seems fine at a barrier (?)
	}


	@Override
	public void classInitialized(ClassInitializedEvent e) {
		final ShadowThread td = e.getThread();
		synchronized (td) {
			
			if (COUNT_EVENT) total.inc(td.getTid());
			if (COUNT_EVENT) class_init.inc(td.getTid());

			EventNode currentEvent = null;
			if (BUILD_EVENT_GRAPH) {
				AcqRelNode currentLock = topLock(td);
				currentEvent = new EventNode(-2, -1, td.getTid(), currentLock, "initialize");
			}

			handleEvent(e, currentEvent);
			if (PRINT_EVENT) {
				Util.log("classInitialized by T"+td.getTid());
			}

			synchronized(classInitTime) { //Not sure what we discussed for classInit, but FT synchronizes on it so I assume the program executing does not protect accesses to classInit.
				handleClassInitialized(td, e);
			}
		}
		super.classInitialized(e);
	}


	protected abstract void handleClassInitialized(ShadowThread thread, ClassInitializedEvent e);


	@Override
	public void classAccessed(ClassAccessedEvent e) {
		final ShadowThread td = e.getThread();
		synchronized(td) {
			
			if (COUNT_EVENT) total.inc(td.getTid());
			if (COUNT_EVENT) class_accessed.inc(td.getTid());

			EventNode currentEvent = null;
			if (BUILD_EVENT_GRAPH) {
				AcqRelNode currentLock = topLock(td);
				currentEvent = new EventNode(-2, -1, td.getTid(), currentLock, "class_accessed");
			}

			handleEvent(e, currentEvent);
			if (PRINT_EVENT) {
				Util.log("classAccessed by T"+td.getTid());
			}

			synchronized(classInitTime) { //Not sure what we discussed for classInit, but FT synchronizes on it so I assume the program executing does not protect accesses to classInit.
				handleClassInit(td, e.getRRClass());
			}
		}
	}
}
