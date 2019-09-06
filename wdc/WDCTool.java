/******************************************************************************

Copyright (c) 2010, Cormac Flanagan (University of California, Santa Cruz)
                    and Stephen Freund (Williams College) 

All rights reserved.  

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

 * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.

 * Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      disclaimer in the documentation and/or other materials provided
      with the distribution.

 * Neither the names of the University of California, Santa Cruz
      and Williams College nor the names of its contributors may be
      used to endorse or promote products derived from this software
      without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

 ******************************************************************************/


package tools.wdc;


import java.io.File;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;

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
import acme.util.io.XMLWriter;
import acme.util.option.CommandLine;
import rr.annotations.Abbrev;
import rr.barrier.BarrierEvent;
import rr.barrier.BarrierListener;
import rr.barrier.BarrierMonitor;
import rr.error.ErrorMessage;
import rr.error.ErrorMessages;
import rr.event.*;
import rr.event.AccessEvent.Kind;
import rr.instrument.classes.ArrayAllocSiteTracker;
import rr.meta.ArrayAccessInfo;
import rr.meta.ClassInfo;
import rr.meta.FieldInfo;
import rr.meta.MetaDataInfoMaps;
import rr.org.objectweb.asm.Opcodes;
import rr.state.ShadowLock;
import rr.state.ShadowThread;
import rr.state.ShadowVar;
import rr.state.ShadowVolatile;
import rr.tool.RR;
import rr.tool.Tool;
import tools.util.VectorClock;
import tools.wdc.event.*;
import tools.wdc.event.AcqRelNode;
import tools.wdc.event.EventNode;
import tools.wdc.event.RdWrDebugNode;
import tools.wdc.event.RdWrNode;
import tools.wdc.sourceinfo.SDGI;
import tools.wdc.sourceinfo.SDG;
import tools.wdc.sourceinfo.SDGNoOp;

@Abbrev("WDC")
public class WDCTool extends Tool implements BarrierListener<WDCBarrierState>, Opcodes {

	public static final int INIT_CV_SIZE = 4;
	private static final ThreadLocalCounter total = new ThreadLocalCounter("DC", "Total Events", RR.maxTidOption.get());
	private static final ThreadLocalCounter exit = new ThreadLocalCounter("DC", "Exit", RR.maxTidOption.get());
	private static final ThreadLocalCounter fake_fork = new ThreadLocalCounter("DC", "Fake Fork", RR.maxTidOption.get());
	private static final ThreadLocalCounter acquire = new ThreadLocalCounter("DC", "Acquire", RR.maxTidOption.get());
	private static final ThreadLocalCounter release = new ThreadLocalCounter("DC", "Release", RR.maxTidOption.get());
	private static final ThreadLocalCounter write = new ThreadLocalCounter("DC", "Write", RR.maxTidOption.get());
	private static final ThreadLocalCounter read = new ThreadLocalCounter("DC", "Read", RR.maxTidOption.get());
	private static final ThreadLocalCounter branch = new ThreadLocalCounter("DC", "Branch", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeFP = new ThreadLocalCounter("DC", "WriteFastPath", RR.maxTidOption.get());
	private static final ThreadLocalCounter readFP = new ThreadLocalCounter("DC", "ReadFastPath", RR.maxTidOption.get());
	private static final ThreadLocalCounter branchFP = new ThreadLocalCounter("DC", "BranchFastPath", RR.maxTidOption.get());
	private static final ThreadLocalCounter volatile_write = new ThreadLocalCounter("DC", "Volatile Write", RR.maxTidOption.get());
	private static final ThreadLocalCounter volatile_read = new ThreadLocalCounter("DC", "Volatile Read", RR.maxTidOption.get());
	private static final ThreadLocalCounter start = new ThreadLocalCounter("DC", "Start", RR.maxTidOption.get());
	private static final ThreadLocalCounter join = new ThreadLocalCounter("DC", "Join", RR.maxTidOption.get());
	private static final ThreadLocalCounter preWait = new ThreadLocalCounter("DC", "Pre Wait", RR.maxTidOption.get());
	private static final ThreadLocalCounter postWait = new ThreadLocalCounter("DC", "Post Wait", RR.maxTidOption.get());
	private static final ThreadLocalCounter class_init = new ThreadLocalCounter("DC", "Class Initialized", RR.maxTidOption.get());
	private static final ThreadLocalCounter class_accessed = new ThreadLocalCounter("DC", "Class Accessed", RR.maxTidOption.get());
	private static final ThreadLocalCounter example = new ThreadLocalCounter("DC", "Total Example Events", RR.maxTidOption.get());
	private static final ThreadLocalCounter access_inside = new ThreadLocalCounter("DC", "Accesses Inside Critical Sections", RR.maxTidOption.get());
	private static final ThreadLocalCounter access_outside = new ThreadLocalCounter("DC", "Accesses Outisde Critical Sections", RR.maxTidOption.get());
	private static final ThreadLocalCounter write_inside = new ThreadLocalCounter("DC", "Write accesses Inside Critical Sections", RR.maxTidOption.get());
	private static final ThreadLocalCounter write_outside = new ThreadLocalCounter("DC", "Write accesses Outside Critical Sections", RR.maxTidOption.get());
	private static final ThreadLocalCounter read_inside = new ThreadLocalCounter("DC", "Read accesses Inside Critical Sections", RR.maxTidOption.get());
	private static final ThreadLocalCounter read_outside = new ThreadLocalCounter("DC", "Read accesses Outside Critical Sections", RR.maxTidOption.get());
	public static final ThreadLocalCounter branch_missing_sdg = new ThreadLocalCounter("DC", "Branch Missing from SDG", RR.maxTidOption.get());
	public static final ThreadLocalCounter read_missing_sdg = new ThreadLocalCounter("DC", "Read Missing from SDG", RR.maxTidOption.get());
	static final Object event_lock = new Object();
	public final ErrorMessage<FieldInfo> fieldErrors = ErrorMessages.makeFieldErrorMessage("WDC");
	public final ErrorMessage<ArrayAccessInfo> arrayErrors = ErrorMessages.makeArrayErrorMessage("WDC");
	private static final int INIT_VECTOR_CLOCK_SIZE = 4;
	private final VectorClock maxEpochPerTid = new VectorClock(INIT_VECTOR_CLOCK_SIZE);

	public static final boolean HB = RR.dcHBOption.get();
	public static final boolean WCP = RR.dcWCPOption.get();
	public static final boolean DC = RR.dcDCOption.get();
	public static final boolean HB_WCP_DC = RR.dcWCP_DCOption.get();
	public static final boolean WBR = RR.dcWBROption.get();
	public static final boolean WCP_WBR = RR.dcWCP_WBROption.get();
	public static final boolean WCP_DC_WBR = RR.dcWCP_DC_WBROption.get();
	public static final boolean WCP_DC_WBR_LSHE = RR.dcWCP_DC_WBR_LSHEOption.get();

	private static boolean running = true;
	public static boolean isRunning() {
		return running;
	}

	public static final boolean NO_SDG;
	private static final SDGI sdg;
	static {
		String sdgClassName = RR.brSDGClassName.get();
		String projectPath = RR.brProjectPath.get();

		NO_SDG = RR.brNoSDG.get();
		if (NO_SDG && sdgClassName != null) {
			Assert.fail("SDG usage was disabled, but an SDG file was provided. Check %s and %s options.", RR.brSDGClassName.getId(), RR.brNoSDG.getId());
		}
		if (!NO_SDG && sdgClassName == null) {
			Assert.fail("SDG usage was enabled, but an SDG file was NOT provided. Check %s and %s options.", RR.brSDGClassName.getId(), RR.brNoSDG.getId());
		}

		if (NO_SDG) {
			Util.println("NOT using SDG information");
			sdg = new SDGNoOp();
		} else {
			Util.println("using SDG information");
			sdg = new SDG(sdgClassName, projectPath);
		}
	}
	
	private static final boolean DISABLE_EVENT_GRAPH = !RR.wdcBuildEventGraph.get();
	private static final boolean PRINT_EVENT = RR.printEventOption.get();
	private static final boolean COUNT_EVENT = true;
	
	private static final boolean VERBOSE = RR.dcVerbose.get();
	
	// Can use the same data for class initialization synchronization as for volatiles
	public static final Decoration<ClassInfo,WDCVolatileData> classInitTime = MetaDataInfoMaps.getClasses().makeDecoration("WDC:InitTime", Type.MULTIPLE, 
			new DefaultValue<ClassInfo,WDCVolatileData>() {
		public WDCVolatileData get(ClassInfo t) {
			return new WDCVolatileData(null);
		}
	});

	public WDCTool(final String name, final Tool next, CommandLine commandLine) {
		super(name, next, commandLine);
		new BarrierMonitor<WDCBarrierState>(this, new DefaultValue<Object,WDCBarrierState>() {
			public WDCBarrierState get(Object k) {
				return new WDCBarrierState(ShadowLock.get(k));
			}
		});
	}

	static CV ts_get_hb(ShadowThread ts) { Assert.panic("Bad");	return null; }
	static void ts_set_hb(ShadowThread ts, CV cv) { Assert.panic("Bad");  }

	static CV ts_get_wcp(ShadowThread ts) { Assert.panic("Bad");	return null; }
	static void ts_set_wcp(ShadowThread ts, CV cv) { Assert.panic("Bad");  }

	static CV ts_get_wdc(ShadowThread ts) { Assert.panic("Bad");	return null; }
	static void ts_set_wdc(ShadowThread ts, CV cv) { Assert.panic("Bad");  }

	static CV ts_get_wbr(ShadowThread ts) { Assert.panic("Bad");	return null; }
	static void ts_set_wbr(ShadowThread ts, CV cv) { Assert.panic("Bad");  }

	static Map<ShadowVar,CVE> ts_get_wbr_delayed(ShadowThread ts) { Assert.panic("Bad"); return null; }
	static void ts_set_wbr_delayed(ShadowThread ts, Map<ShadowVar,CV> map) { Assert.panic("Bad"); }

	protected static boolean ts_get_hasread(ShadowThread ts) { Assert.panic("Bad"); return true; }
	protected static void ts_set_hasread(ShadowThread ts, boolean reads) { Assert.panic("Bad");  }
	
	static CV ts_get_lshe(ShadowThread ts) { Assert.panic("Bad");	return null; }
	static void ts_set_lshe(ShadowThread ts, CV cv) { Assert.panic("Bad"); }

	// We only maintain the "last event" if BUILD_EVENT_GRAPH == true
	static EventNode ts_get_lastEventNode(ShadowThread ts) { Assert.panic("Bad"); return null; }
	static void ts_set_lastEventNode(ShadowThread ts, EventNode eventNode) { Assert.panic("Bad"); }
	
	// Maintain the a stack of current held critical sections per thread
	static Stack<AcqRelNode> ts_get_holdingLocks(ShadowThread ts) { Assert.panic("Bad"); return null; }
	static void ts_set_holdingLocks(ShadowThread ts, Stack<AcqRelNode> heldLocks) { Assert.panic("Bad"); }
	
	// Handle FastPaths and race edges
	static int/*epoch*/ ts_get_eTd(ShadowThread ts) { Assert.panic("Bad");	return -1; }
	static void ts_set_eTd(ShadowThread ts, int/*epoch*/ e) { Assert.panic("Bad");  }

	static final Decoration<ShadowLock,WDCLockData> wdcLockData = ShadowLock.makeDecoration("WDC:ShadowLock", DecorationFactory.Type.MULTIPLE,
			new DefaultValue<ShadowLock,WDCLockData>() { public WDCLockData get(final ShadowLock ld) { return new WDCLockData(ld); }});

	static final WDCLockData get(final ShadowLock ld) {
		return wdcLockData.get(ld);
	}

	static final Decoration<ShadowVolatile,WDCVolatileData> wdcVolatileData = ShadowVolatile.makeDecoration("WDC:shadowVolatile", DecorationFactory.Type.MULTIPLE,
			new DefaultValue<ShadowVolatile,WDCVolatileData>() { public WDCVolatileData get(final ShadowVolatile ld) { return new WDCVolatileData(ld); }});

	static final WDCVolatileData get(final ShadowVolatile ld) {
		return wdcVolatileData.get(ld);
	}

	@Override
	final public ShadowVar makeShadowVar(final AccessEvent fae) {
		if (fae.getKind() == Kind.VOLATILE) {
			WDCVolatileData vd = get(((VolatileAccessEvent)fae).getShadowVolatile());
			// Instead of the commented-out stuff, I think they should just be initialized to bottom
			/*
			vd.hb.max(hb);
			vd.wcp.max(wcpUnionPO); // use wcpUnionPO
			vd.wdc.max(wdc);
			*/
			return super.makeShadowVar(fae);
		} else {
			return new WDCGuardState();
		}
	}

	@Override
	public void create(NewThreadEvent e) {
		ShadowThread currentThread = e.getThread();
		synchronized(currentThread) { //TODO: Is this over kill?
			if (HB) {
				CV hb = ts_get_hb(currentThread);
				if (hb == null) {
					hb = new CV(INIT_CV_SIZE);
					ts_set_hb(currentThread, hb);
					hb.inc(currentThread.getTid());
				}
			}
			if (WCP || HB_WCP_DC || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
				CV hb = ts_get_hb(currentThread);
				if (hb == null) {
					hb = new CV(INIT_CV_SIZE);
					ts_set_hb(currentThread, hb);
					hb.inc(currentThread.getTid());
					CV wcp = new CV(INIT_CV_SIZE);
					ts_set_wcp(currentThread, wcp);
				}
			}
			if (DC || HB_WCP_DC || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
				CV wdc = ts_get_wdc(currentThread);
				if (wdc == null) {
					wdc = new CV(INIT_CV_SIZE);
					ts_set_wdc(currentThread, wdc);
					wdc.inc(currentThread.getTid());
				}
			}
			if (WBR || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
				CV wbr = ts_get_wbr(currentThread);
				if (wbr == null) {
					wbr = new CV(INIT_CV_SIZE);
					ts_set_wbr(currentThread, wbr);
					wbr.inc(currentThread.getTid());
					ts_set_wbr_delayed(currentThread, new HashMap<>());
					ts_set_hasread(currentThread, false);
				}
			}
			if (WCP_DC_WBR_LSHE) {
				CV lshe = ts_get_lshe(currentThread);
				if (lshe == null) {
					lshe = new CV(INIT_CV_SIZE);
					ts_set_lshe(currentThread, lshe);
					lshe.inc(currentThread.getTid());
				}
			}
			// Handle race edges
			ts_set_eTd(currentThread, ts_get_hb(currentThread).get(currentThread.getTid()));
		}
		super.create(e);

	}
	
	@Override
	public void exit(MethodEvent me) {	
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
					if (VERBOSE) Assert.assertTrue(main != null);//, "The main thread can not be found.");

					EventNode dummyEventNode = null;
					EventNode thisEventNode = null;
					if (!DISABLE_EVENT_GRAPH) {
						//Build this thread's event node
						AcqRelNode currentCriticalSection = getCurrentCriticalSection(td);
						thisEventNode = new EventNode(-2, -1, td.getTid(), currentCriticalSection, "join [exit join]");
						handleEvent(me, thisEventNode);
						if (VERBOSE && !DISABLE_EVENT_GRAPH) Assert.assertTrue(thisEventNode.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));
						//Build dummy eventNode
						if (COUNT_EVENT) total.inc(td.getTid()); //TODO: should dummy event increment the event count?
						currentCriticalSection = getCurrentCriticalSection(main);
						dummyEventNode = new EventNode(ts_get_lastEventNode(main).eventNumber+1, -1, main.getTid(), currentCriticalSection, "exit [dummy event]");
						//PO last event node in main to dummy node
						EventNode priorMainNode = ts_get_lastEventNode(main);
						EventNode.addEdge(priorMainNode, dummyEventNode);
						ts_set_lastEventNode(main, dummyEventNode);
						//Create a hard edge from thisEventNode to dummy node
						EventNode.addEdge(thisEventNode, dummyEventNode);
					} else {
						//PO last event node in this thread to this thread's current event node
						//Set this event node to this thread's latest event node
						handleEvent(me, thisEventNode);
						if (VERBOSE && !DISABLE_EVENT_GRAPH) Assert.assertTrue(thisEventNode.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));
					}
					
					if (PRINT_EVENT) Util.log("exit "+me.getInfo().toSimpleName()+" by T"+td.getTid()+(!DISABLE_EVENT_GRAPH ? ", event count:"+thisEventNode.eventNumber : ""));

					//Update main's vector clocks with that of the joining thread
					if (HB) ts_get_hb(main).max(ts_get_hb(td));
					if (WCP || HB_WCP_DC || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
						ts_get_hb(main).max(ts_get_hb(td));
						ts_get_wcp(main).max(ts_get_hb(td));
					}
					if (DC || HB_WCP_DC || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
						ts_get_wdc(main).max(ts_get_wdc(td));
					}
					if (WBR || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
						ts_get_wbr(main).max(ts_get_wbr(td));
					}
					if (WCP_DC_WBR_LSHE) {
						ts_get_lshe(main).max(ts_get_lshe(td));
					}
					}
				}
			}
		}
		super.exit(me);
	}

	@Override
	public void stop(ShadowThread td) {
		super.stop(td);
	}
	
	@Override
	public void init() {		
		//Disable event graph generation for HB and WCP and LSHE configurations
		if (HB || WCP || WCP_DC_WBR_LSHE) {
			Assert.assertTrue(DISABLE_EVENT_GRAPH == true);
		}
		
		if (!DISABLE_EVENT_GRAPH) {
			Assert.assertTrue(COUNT_EVENT == true);
		}

        long nConfigEnabled = Stream.of(HB, WCP, DC, WBR, HB_WCP_DC, WCP_WBR, WCP_DC_WBR, WCP_DC_WBR_LSHE).filter(b -> b).count();
        if (nConfigEnabled == 0) {
		    Util.printf("You must enable a config when running the tool, see options %s, %s, %s, %s, %s, %s, %s, %s.",
                    RR.dcHBOption.getId(), RR.dcWCPOption.getId(), RR.dcDCOption.getId(), RR.dcWBROption.getId(), RR.dcWCP_DCOption.getId(), RR.dcWCP_WBROption.getId(), RR.dcWCP_DC_WBROption.getId(), RR.dcWCP_DC_WBR_LSHEOption.getId());
		    Util.exit(1);
        } else if (nConfigEnabled > 1) {
            Util.printf("You can only enable a single config when running the tool!");
            Util.exit(1);
        }
	}
	
	//TODO: Only a single thread should be accessing the list of races and the event graph
	@Override
	public void fini() {
		running = false;
		StaticRace.reportRaces();
		//With no constraint graph races can not be checked
		//If this is changed, HB_WCP_ONLY configuration should not check races since DC constraint graph is not tracked
		
		if (!DISABLE_EVENT_GRAPH) {
			// Store Reordered Traces
			File commandDir = storeReorderedTraces();
			
			// Races (based on an identifying string) that we've verified have no cycle.
			// Other dynamic instances of the same static race might have a cycle, but we've already demonstrated that this static race is predictable, so who cares?
			HashSet<StaticRace> verifiedRaces = new HashSet<StaticRace>();
			HashSet<StaticRace> failedRaces = new HashSet<>();
			LinkedList<StaticRace> staticOnlyCheck = new LinkedList<StaticRace>();
			long start = System.currentTimeMillis();
			// Only Vindicate WBR races
			// Note that we will attempt each static race only once, then give up
			for (StaticRace wbrRace : StaticRace.races) {
				if (isWBROnlyRace(wbrRace)) {
					vindicateRace(wbrRace, verifiedRaces, failedRaces, staticOnlyCheck, true, true, commandDir);
				}
			}
			Util.log("Latest instance Static WBR Race Check Time: " + (System.currentTimeMillis() - start));
			Util.log("Latest instance verified " + verifiedRaces.size() + " WBR-only races out of " + StaticRace.getStaticRaceCount(RaceType.WBRRace));

			// Randomly shuffle the list of races, so that the first dynamic instance we see for each race is random
			if (RR.brVindicateRandomInstances.get()) {
				HashSet<StaticRace> rndVerifiedRaces = new HashSet<>();
				HashSet<StaticRace> rndFailedRaces = new HashSet<>();
				start = System.currentTimeMillis();
				Util.log("Randomly picking dynamic instances for races.");
				Vector<StaticRace> v = new Vector<>(StaticRace.races);
				Collections.shuffle(v);
				StaticRace.races = new ConcurrentLinkedQueue<>(v);

				RR.wdcRandomReorderings.set(0); // Don't do random reorderings during random instance reorderings
				for (StaticRace wbrRace : StaticRace.races) {
					if (isWBROnlyRace(wbrRace)) {
						vindicateRace(wbrRace, rndVerifiedRaces, rndFailedRaces, staticOnlyCheck, true, true, commandDir);
					}
				}
				Util.log("Random instance Static WBR Race Check Time: " + (System.currentTimeMillis() - start));
				Util.log("Random instance verified " + rndVerifiedRaces.size() + " verified WBR-only races out of " + StaticRace.getStaticRaceCount(RaceType.WBRRace));

				Set<StaticRace> rndMinusLatest = new HashSet<>(rndVerifiedRaces);
				rndMinusLatest.removeAll(verifiedRaces);
				Util.log("Random instance selection verified "+ rndMinusLatest.size() + " races that latest instance failed.");
				Set <StaticRace> latestMinusRnd = new HashSet<>(verifiedRaces);
				latestMinusRnd.removeAll(rndVerifiedRaces);
				Util.log("Latest instance selection verified "+ latestMinusRnd.size() + " races that random instance failed.");
			}
			if (true) return; // Vindication takes a lot of time, so only do static WBR-only vindication.
			//Dynamic WBR-race only check
			start = System.currentTimeMillis();
			for (StaticRace wbrRace : StaticRace.races) {
				// Only do WDC-B processing on WDC-races (not HB-, WCP-, CAPO-, or PIP-races)
				if (wbrRace.raceType.isWBRRace() && !wbrRace.raceType.isWDCRace()) {
					vindicateRace(wbrRace, verifiedRaces, failedRaces, staticOnlyCheck, false, true, commandDir);
				}
			}
			Util.log("Dynamic WBR Race Check Time: " + (System.currentTimeMillis() - start));
			//Dynamic DC-race only check
			start = System.currentTimeMillis();
			for (StaticRace wdcRace : StaticRace.races) {
				// Only do WDC-B processing on WDC-races (not HB-, WCP-, CAPO-, or PIP-races)
				if (wdcRace.raceType.isWDCRace() && !wdcRace.raceType.isWCPRace()) {
					vindicateRace(wdcRace, verifiedRaces, failedRaces, staticOnlyCheck, false, true, commandDir);
				}
			}
			Util.log("Dynamic DC Race Check Time: " + (System.currentTimeMillis() - start));
			//Dynamic WCP-race only check
			start = System.currentTimeMillis();
			for (StaticRace wcpRace : StaticRace.races) {
				// Only VindicateRace on WCP-races (not HB-, WDC-, CAPO-, or PIP-races)
				if (wcpRace.raceType.isWCPRace() && !wcpRace.raceType.isHBRace()) {
					vindicateRace(wcpRace, verifiedRaces, failedRaces, staticOnlyCheck, false, true, commandDir);
				}
			}
			Util.log("Dynamic WCP Race Check Time: " + (System.currentTimeMillis() - start));
			//Dynamic HB-race only check
			start = System.currentTimeMillis();
			for (StaticRace hbRace : StaticRace.races) {
				// Only VindicateRace on HB-races (not WCP-, WDC-, CAPO-, or PIP-races)
				if (hbRace.raceType.isHBRace()) {
					vindicateRace(hbRace, verifiedRaces, failedRaces, staticOnlyCheck, false, true, commandDir);
				}
			}
			Util.log("Dynamic HB Race Check Time: " + (System.currentTimeMillis() - start));
//			Util.log("Printing HB/WCP Races.");
//			for (StaticRace wcpRace : StaticRace.wdcRaces) {
//				if (wcpRace.raceType.isWCPRace() && wcpRace.raceType != RaceType.WDCRace) {
//					vindicateRace(wcpRace, verifiedRaces, staticOnlyCheck, false, false, commandDir);
//				}
//			}
		}
	}

	private static boolean isWBROnlyRace(StaticRace wbrRace) {
		return wbrRace.raceType.isWBRRace() &&
				!(StaticRace.staticRaceMap.getOrDefault(RaceType.WDCRace, new HashMap<>()).containsKey(wbrRace)
						|| StaticRace.staticRaceMap.getOrDefault(RaceType.WCPRace, new HashMap<>()).containsKey(wbrRace)
						|| StaticRace.staticRaceMap.getOrDefault(RaceType.HBRace, new HashMap<>()).containsKey(wbrRace));
	}

	public void vindicateRace(StaticRace DCrace, HashSet<StaticRace> verifiedRaces, HashSet<StaticRace> failedRaces, LinkedList<StaticRace> staticOnlyCheck, boolean staticDCRacesOnly, boolean vindicateRace, File commandDir) {
		RdWrNode startNode = DCrace.firstNode;
		RdWrNode endNode = DCrace.secondNode;
		String desc = DCrace.raceType + " " + DCrace.description();
		if (vindicateRace) {
			if (!staticDCRacesOnly || !(verifiedRaces.contains(DCrace) || failedRaces.contains(DCrace))) {
				Util.println("Checking " + desc + " for event pair " + startNode + " -> " + endNode);
				// WBR does not draw race edges, it might be missing
				EventNode.addEdge(startNode, endNode);
				boolean detectedCycle = EventNode.crazyNewEdges(startNode, endNode, commandDir);
				EventNode.removeEdge(startNode, endNode);
				 
				if (staticDCRacesOnly) {
					if (!detectedCycle) {
						verifiedRaces.add(DCrace);
					} else {
						failedRaces.add(DCrace);
					}
					staticOnlyCheck.add(DCrace);
				}
			}
		} else {
			Util.println("Checking " + desc + " for event pair " + startNode + " -> " + endNode);
		}
	}
	
	//Tid -> Stack of ARNode
	AcqRelNode getCurrentCriticalSection(ShadowThread td) {
		Stack<AcqRelNode> locksHeld = ts_get_holdingLocks(td);
		if (locksHeld == null) {
			locksHeld = new Stack<AcqRelNode>();
			ts_set_holdingLocks(td, locksHeld);
		}
		AcqRelNode currentCriticalSection = locksHeld.isEmpty() ? null : locksHeld.peek();
		return currentCriticalSection;
	}
	
	void updateCurrentCriticalSectionAtAcquire(ShadowThread td, AcqRelNode acqNode) {
		Stack<AcqRelNode> locksHeld = ts_get_holdingLocks(td);
		locksHeld.push(acqNode);
	}
	
	AcqRelNode updateCurrentCriticalSectionAtRelease(ShadowThread td, AcqRelNode relNode) {
		Stack<AcqRelNode> locksHeld = ts_get_holdingLocks(td);
		AcqRelNode poppedNode = locksHeld.pop();
		return poppedNode;
	}
	
	void handleEvent(Event e, EventNode thisEventNode) {
		ShadowThread td = e.getThread();
		
		// Evaluating total.getCount() frequently will lead to lots of cache conflicts, so doing this local check instead.
		if (total.getLocal(td.getTid()) % 1000000 == 1) {
		//This is a rough estimate now since total.getCount() is not thread safe
		//if (total.getCount() % 1000000 == 1) {
			Util.println("Handling event " + total.getCount());
			/*
			if (event_count > 5000000) {
				fini();
				System.exit(0);
			}
			*/
		}
        
		if (!DISABLE_EVENT_GRAPH) {
			EventNode priorPOEventNode = ts_get_lastEventNode(td);
			if (priorPOEventNode == null) {
				// This is the first event of the thread
				if (VERBOSE && (WBR || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE)) Assert.assertTrue((td.getParent() != null) == (ts_get_wbr(td) instanceof CVE));
				if (td.getParent() != null) {
					if (!DISABLE_EVENT_GRAPH && (WBR || WCP_WBR || WCP_DC_WBR)) {
						EventNode forkEventNode = ((CVE) ts_get_wbr(td)).eventNode;
						EventNode.addEdge(forkEventNode, thisEventNode);
					}
				} else {
					if (td.getTid() != 0 //&& thisEventNode.eventNumber != 1 /*Every event after the first one on T0 will be PO ordered*/ 
							&& !td.getThread().getName().equals("Finalizer")) {
						if (PRINT_EVENT) Util.log("parentless fork to T"+td.getTid());
						if (COUNT_EVENT) fake_fork.inc(td.getTid());
						//Get the main thread
						//TODO: Same as exit, the main thread is being accessed by a different thread
						ShadowThread main = ShadowThread.get(0);
						synchronized(main) { //Will this deadlock? Same as exit, I don't think main will lock on this thread.
							if (VERBOSE) Assert.assertTrue(main != null);//, "The main thread can not be found.");

							// Accesses after the parentless fork may race with accesses in the new thread
							ts_set_eTd(main, ts_get_hb(main).get(main.getTid()));

							//Create a hard edge from the last event in the main thread to the parentless first event in this thread
							int mainTid = main.getTid();
							if (HB) {
								final CV mainHB = ts_get_hb(main);
								final CV hb = ts_get_hb(td);
								hb.max(mainHB);
								mainHB.inc(mainTid);
							}
							if (WCP || HB_WCP_DC || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
								final CV mainHB = ts_get_hb(main);
								final CV hb = ts_get_hb(td);
								final CV mainWCP = ts_get_wcp(main);
								final CV wcp = ts_get_wcp(td);
								// Compute WCP before modifying HB
								wcp.max(mainHB); // Use HB here because this is a hard WCP edge
								hb.max(mainHB);
								mainHB.inc(mainTid);
							}
							if (DC || HB_WCP_DC || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
								final CV mainWDC = ts_get_wdc(main);
								final CV wdc = ts_get_wdc(td);
								wdc.max(mainWDC);
								mainWDC.inc(mainTid);
							}
							if (WBR || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
								final CV mainWBR = ts_get_wbr(main);
								final CV wbr = ts_get_wbr(td);
								wbr.max(mainWBR);
								mainWBR.inc(mainTid);
							}
							if (WCP_DC_WBR_LSHE) {
								final CV mainLSHE = ts_get_lshe(main);
								final CV lshe = ts_get_lshe(td);
								lshe.max(mainLSHE);
								mainLSHE.inc(mainTid);
							}
							if (WBR || WCP_WBR || WCP_DC_WBR) {
								ts_set_wbr(td, new CVE(ts_get_wbr(td), ts_get_lastEventNode(main))); // for generating event node graph
							}
							
							Assert.assertTrue(td.getTid() != 0);
							Assert.assertTrue(ts_get_lastEventNode(main) != null);
							
							//Add edge from main to first event in the thread
							if (VERBOSE && (WBR || WCP_WBR || WCP_DC_WBR)) Assert.assertTrue(((CVE)ts_get_wbr(td)).eventNode == ts_get_lastEventNode(main));
							
							EventNode forkEventNode = null;
							if (WBR || WCP_WBR || WCP_DC_WBR) forkEventNode = ((CVE)ts_get_wbr(td)).eventNode;
							EventNode.addEdge(forkEventNode, thisEventNode);
							
							if (VERBOSE && EventNode.VERBOSE_GRAPH) {
								EventNode eventOne = EventNode.threadToFirstEventMap.get(0);
								eventOne = EventNode.threadToFirstEventMap.get(0);
								Assert.assertTrue(eventOne.eventNumber == 1);//, "eventOne.eventNumber: " + eventOne.eventNumber);
								Assert.assertTrue(EventNode.bfsTraversal(eventOne, forkEventNode, null, Long.MIN_VALUE, Long.MAX_VALUE));//, "main T" + main.getTid() + " does not reach event 1. What: " + eventOne.getNodeLabel());
								Assert.assertTrue(EventNode.bfsTraversal(eventOne, thisEventNode, null, Long.MIN_VALUE, Long.MAX_VALUE));//, "Thread T" + td.getTid() + " does not reach event 1.");
							}
						}
					} else {
						// If the current event is the first event, give it an eventNumber of 1
						if (td.getTid() == 0 && thisEventNode.eventNumber < 0) {
							if (VERBOSE) Assert.assertTrue(thisEventNode.threadID == 0);
							thisEventNode.eventNumber = 1;
							if (EventNode.VERBOSE_GRAPH) EventNode.addEventToThreadToItsFirstEventsMap(thisEventNode);
						}
						if (VERBOSE) Assert.assertTrue(thisEventNode.eventNumber == 1 || td.getThread().getName().equals("Finalizer"));//, "Event Number: " + thisEventNode.eventNumber + " | Thread Name: " + td.getThread().getName());
					}
				}
			} else {
				EventNode.addEdge(priorPOEventNode, thisEventNode);
			}
			ts_set_lastEventNode(td, thisEventNode);
		} else if (td.getParent() == null && td.getTid() != 0 /*not main thread*/ && !td.getThread().getName().equals("Finalizer")) { //event graph disabled here
			//If this is the first event in the parentless thread
			if (((HB || WCP || HB_WCP_DC || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) && ts_get_hb(td).get(0) == 0) || (DC && ts_get_wdc(td).get(0) == 0) || (WBR && ts_get_wbr(td).get(0) == 0)) {
				if (PRINT_EVENT) Util.log("parentless fork to T"+td.getTid());
				if (COUNT_EVENT) fake_fork.inc(td.getTid());
				//Get the main thread
				//TODO: Same as exit, the main thread is being accessed by a different thread
				ShadowThread main = ShadowThread.get(0);
				if (VERBOSE) Assert.assertTrue(main != null);//, "The main thread can not be found.");
				synchronized(main) { //Will this deadlock? Same as exit, I don't think main will lock on this thread.

					// Accesses after the parentless fork may race with accesses in the new thread
					ts_set_eTd(main, ts_get_hb(main).get(main.getTid()));

					//Create a hard edge from the last event in the main thread to the parentless first event in this thread
					int mainTid = main.getTid();
					if (HB) {
						final CV mainHB = ts_get_hb(main);
						final CV hb = ts_get_hb(td);
						hb.max(mainHB);
						mainHB.inc(mainTid);
					}
					if (WCP || HB_WCP_DC || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
						final CV mainHB = ts_get_hb(main);
						final CV hb = ts_get_hb(td);
						final CV mainWCP = ts_get_wcp(main);
						final CV wcp = ts_get_wcp(td);
						// Compute WCP before modifying HB
						wcp.max(mainHB); // Use HB here because this is a hard WCP edge
						hb.max(mainHB);
						mainHB.inc(mainTid);
					}
					if (DC || HB_WCP_DC || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
						final CV mainWDC = ts_get_wdc(main);
						final CV wdc = ts_get_wdc(td);
						wdc.max(mainWDC);
						mainWDC.inc(mainTid);
					}
					if (WBR || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
						final CV mainWBR = ts_get_wbr(main);
						final CV wbr = ts_get_wbr(td);
						wbr.max(mainWBR);
						mainWBR.inc(mainTid);
					}
					if (WCP_DC_WBR_LSHE) {
						final CV mainLSHE = ts_get_lshe(main);
						final CV lshe = ts_get_lshe(td);
						lshe.max(mainLSHE);
						mainLSHE.inc(mainTid);
					}
				}
			}
		}
	}
	
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
	
			//TODO: Anything build event graph 
			AcqRelNode thisEventNode = null;
			if (!DISABLE_EVENT_GRAPH) {
				AcqRelNode currentCriticalSection = getCurrentCriticalSection(td);
				if (currentCriticalSection != null && VERBOSE) Assert.assertTrue(currentCriticalSection.shadowLock != shadowLock);
				thisEventNode = new AcqRelNode(-2/*ts_get_lastEventNode(td).eventNumber+1*/, -1, shadowLock, td.getTid(), true, currentCriticalSection);
				updateCurrentCriticalSectionAtAcquire(td, thisEventNode);
			}
			handleEvent(ae, thisEventNode);
			if (VERBOSE && !DISABLE_EVENT_GRAPH) Assert.assertTrue(thisEventNode.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));
			
			handleAcquire(td, shadowLock, thisEventNode, false);
			
			// Accesses inside critical sections may form relations different from ones outside
			ts_set_eTd(td, ts_get_hb(td).get(td.getTid()));
			
			if (PRINT_EVENT) Util.log("acq("+Util.objectToIdentityString(shadowLock)+") by T"+td.getTid()+(!DISABLE_EVENT_GRAPH ? ", event count:"+thisEventNode.eventNumber : ""));
		}

		super.acquire(ae);
	}

	void handleAcquire(ShadowThread td, ShadowLock shadowLock, AcqRelNode thisEventNode, boolean isHardEdge) {
		// lockData is protected by the lock corresponding to the shadowLock being currently held
		final WDCLockData lockData = get(shadowLock);
		int tid = td.getTid();
		
		if (!DISABLE_EVENT_GRAPH) {
			thisEventNode.eventNumber = lockData.latestRelNode == null ? thisEventNode.eventNumber : Math.max(thisEventNode.eventNumber, lockData.latestRelNode.eventNumber+1);
		}
		
		if (HB) {
			final CV hb = ts_get_hb(td);
			hb.max(lockData.hb);
		}
		
		// WCP, DC, WBR
		if (VERBOSE) Assert.assertTrue(lockData.readVars.isEmpty() && lockData.writeVars.isEmpty());
		
		if (WCP || HB_WCP_DC || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
			final CV hb = ts_get_hb(td);
			final CV wcp = ts_get_wcp(td);
			hb.max(lockData.hb);
			if (isHardEdge) {
				wcp.max(lockData.hb); // If a hard edge, union WCP with HB
			} else {
				wcp.max(lockData.wcp);
			}
			
			final CV wcpUnionPO = new CV(wcp);
			wcpUnionPO.set(tid, hb.get(tid));
			// acqQueueMap is protected by the lock corresponding to the shadowLock being currently held
			for (ShadowThread otherTD : ShadowThread.getThreads()) {
				if (otherTD != td) {
					ArrayDeque<CV> queue = lockData.wcpAcqQueueMap.get(otherTD);
					if (queue == null) {
						queue = lockData.wcpAcqQueueGlobal.clone(); // Include any stuff that didn't get added because otherTD hadn't been created yet
						lockData.wcpAcqQueueMap.put(otherTD, queue);
					}
					queue.addLast(wcpUnionPO);
				}
			}
			
			// Also add to the queue that we'll use for any threads that haven't been created yet.
			// But before doing that, be sure to initialize *this thread's* queues for the lock using the global queues.
			ArrayDeque<CV> acqQueue = lockData.wcpAcqQueueMap.get(td);
			if (acqQueue == null) {
				acqQueue = lockData.wcpAcqQueueGlobal.clone();
				lockData.wcpAcqQueueMap.put(td, acqQueue);
			}
			ArrayDeque<CV> relQueue = lockData.wcpRelQueueMap.get(td);
			if (relQueue == null) {
				relQueue = lockData.wcpRelQueueGlobal.clone();
				lockData.wcpRelQueueMap.put(td, relQueue);
			}
			lockData.wcpAcqQueueGlobal.addLast(wcpUnionPO);
		}
		if (DC || HB_WCP_DC || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
			final CV wdc = ts_get_wdc(td);
			if (isHardEdge) {
				wdc.max(lockData.wdc);
			} // Don't max otherwise for DC, since it's not transitive with HB
			
			CV wdcCV = new CV(wdc);
			// acqQueueMap is protected by the lock corresponding to the shadowLock being currently held
			for (ShadowThread otherTD : ShadowThread.getThreads()) {
				if (otherTD != td) {
					PerThreadQueue<CV> ptQueue = lockData.wdcAcqQueueMap.get(otherTD);
					if (ptQueue == null) {
						ptQueue = lockData.wdcAcqQueueGlobal.clone();
						lockData.wdcAcqQueueMap.put(otherTD, ptQueue);
					}
					ptQueue.addLast(td, wdcCV);
				}
			}
			
			// Also add to the queue that we'll use for any threads that haven't been created yet.
			// But before doing that, be sure to initialize *this thread's* queues for the lock using the global queues.
			PerThreadQueue<CV> acqPTQueue = lockData.wdcAcqQueueMap.get(td);
			if (acqPTQueue == null) {
				acqPTQueue = lockData.wdcAcqQueueGlobal.clone();
				lockData.wdcAcqQueueMap.put(td, acqPTQueue);
			}
			PerThreadQueue<CV> relPTQueue = lockData.wdcRelQueueMap.get(td);
			if (relPTQueue == null) {
				relPTQueue = lockData.wdcRelQueueGlobal.clone();
				lockData.wdcRelQueueMap.put(td, relPTQueue);
			}
			lockData.wdcAcqQueueGlobal.addLast(td, wdcCV);
		}
		if (WBR || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
			final CV wbr = ts_get_wbr(td);
			if (isHardEdge) {
				wbr.max(lockData.wbr);
			} // Don't max otherwise for WBR, since it's not transitive with HB

			CVE wbrCVE = new CVE(wbr, thisEventNode);
			// acqQueueMap is protected by the lock corresponding to the shadowLock being currently held
			for (ShadowThread otherTD : ShadowThread.getThreads()) {
				if (otherTD != td) {
					PerThreadQueue<CVE> ptQueue = lockData.wbrAcqQueueMap.get(otherTD);
					if (ptQueue == null) {
						ptQueue = lockData.wbrAcqQueueGlobal.clone();
						lockData.wbrAcqQueueMap.put(otherTD, ptQueue);
					}
					ptQueue.addLast(td, wbrCVE);
				}
			}

			// Also add to the queue that we'll use for any threads that haven't been created yet.
			// But before doing that, be sure to initialize *this thread's* queues for the lock using the global queues.
			PerThreadQueue<CVE> acqPTQueue = lockData.wbrAcqQueueMap.get(td);
			if (acqPTQueue == null) {
				acqPTQueue = lockData.wbrAcqQueueGlobal.clone();
				lockData.wbrAcqQueueMap.put(td, acqPTQueue);
			}
			PerThreadQueue<CVE> relPTQueue = lockData.wbrRelQueueMap.get(td);
			if (relPTQueue == null) {
				relPTQueue = lockData.wbrRelQueueGlobal.clone();
				lockData.wbrRelQueueMap.put(td, relPTQueue);
			}
			lockData.wbrAcqQueueGlobal.addLast(td, wbrCVE);
		}
		if (WCP_DC_WBR_LSHE) {
			final CV lshe = ts_get_lshe(td);
			if (isHardEdge) {
				lshe.max(lockData.lshe);
			}
		}
	}

	@Override
	public void release(final ReleaseEvent re) {
		final ShadowThread td = re.getThread();
		synchronized(td) {
			final ShadowLock shadowLock = re.getLock();
			
			if (COUNT_EVENT) total.inc(td.getTid());
			if (COUNT_EVENT) release.inc(td.getTid());
			if (COUNT_EVENT) example.inc(td.getTid());

			AcqRelNode thisEventNode = null;
			AcqRelNode matchingAcqNode = null;
			if (!DISABLE_EVENT_GRAPH) {
				AcqRelNode currentCriticalSection = getCurrentCriticalSection(td);
				if (VERBOSE) Assert.assertTrue(currentCriticalSection != null);
				thisEventNode = new AcqRelNode(-2, -1, shadowLock, td.getTid(), false, currentCriticalSection);
				matchingAcqNode = updateCurrentCriticalSectionAtRelease(td, thisEventNode);
			}
			handleEvent(re, thisEventNode);
			if (VERBOSE && !DISABLE_EVENT_GRAPH) Assert.assertTrue(thisEventNode.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));
			
			if (PRINT_EVENT) Util.log("rel("+Util.objectToIdentityString(shadowLock.getLock())+") by T"+td.getTid()+(!DISABLE_EVENT_GRAPH ? ", event count:"+thisEventNode.eventNumber : ""));

			handleRelease(td, shadowLock, thisEventNode, false);
			
			if (VERBOSE && !DISABLE_EVENT_GRAPH) {
				Assert.assertTrue(matchingAcqNode == thisEventNode.otherCriticalSectionNode);
			}
		}

		super.release(re);

	}
	
	void handleRelease(ShadowThread td, ShadowLock shadowLock, AcqRelNode thisEventNode, boolean isHardEdge) {
		final WDCLockData lockData = get(shadowLock);
		int tid = td.getTid();
		
		// Accesses after the release may be involved in races differently from accesses inside the critical section
		ts_set_eTd(td, ts_get_hb(td).get(td.getTid()));

		if (!DISABLE_EVENT_GRAPH) {
			AcqRelNode myAcqNode = thisEventNode.inCS;
			if (VERBOSE) Assert.assertTrue(myAcqNode.isAcquire());
			// This release's corresponding acquire node should not be touched while the lock is currently held
			thisEventNode.otherCriticalSectionNode = myAcqNode;
			myAcqNode.otherCriticalSectionNode = thisEventNode;
		}
		
		if (HB) {
			final CV hb = ts_get_hb(td);
			
			// Assign to lock
			lockData.hb.assignWithResize(hb);
		}
		if (WCP || HB_WCP_DC || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
			final CV hb = ts_get_hb(td);
			final CV wcp = ts_get_wcp(td);
			final CV wcpUnionPO = new CV(wcp);
			wcpUnionPO.set(tid, hb.get(tid));
			
			// Process queue elements
			ArrayDeque<CV> acqQueue = lockData.wcpAcqQueueMap.get(td);
			ArrayDeque<CV> relQueue = lockData.wcpRelQueueMap.get(td);
			while (!acqQueue.isEmpty() && !acqQueue.peekFirst().anyGt(wcpUnionPO)) {
				acqQueue.removeFirst();
				wcp.max(relQueue.removeFirst());
			}
			
			// Rule (a)
			for (ShadowVar var : lockData.readVars) {
				CV cv = lockData.wcpReadMap.get(var);
				if (cv == null) {
					cv = new CV(WDCTool.INIT_CV_SIZE);
					lockData.wcpReadMap.put(var, cv);
				}
				cv.max(hb);
			}
			for (ShadowVar var : lockData.writeVars) {
				CV cv = lockData.wcpWriteMap.get(var);
				if (cv == null) {
					cv = new CV(WDCTool.INIT_CV_SIZE);
					lockData.wcpWriteMap.put(var, cv);
				}
				cv.max(hb);
			}
			
			// Assign to lock
			lockData.hb.assignWithResize(hb);
			lockData.wcp.assignWithResize(wcp);
			
			// Add to release queues
			CV hbCopy = new CV(hb);
			for (ShadowThread otherTD : ShadowThread.getThreads()) {
				if (otherTD != td) {
					ArrayDeque<CV> queue = lockData.wcpRelQueueMap.get(otherTD);
					if (queue == null) {
						queue = lockData.wcpRelQueueGlobal.clone(); // Include any stuff that didn't get added because otherTD hadn't been created yet
						lockData.wcpRelQueueMap.put(otherTD, queue);
					}
					queue.addLast(hbCopy);
				}
			}
			// Also add to the queue that we'll use for any threads that haven't been created yet
			lockData.wcpRelQueueGlobal.addLast(hbCopy);
			
			// Clear read/write maps
			lockData.wcpReadMap = getPotentiallyShrunkMap(lockData.wcpReadMap);
			lockData.wcpWriteMap = getPotentiallyShrunkMap(lockData.wcpWriteMap);
		}
		if (DC || HB_WCP_DC || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
			final CV wdc = ts_get_wdc(td);
			
			// Process queue elements
			PerThreadQueue<CV> acqPTQueue = lockData.wdcAcqQueueMap.get(td);
			if (VERBOSE) Assert.assertTrue(acqPTQueue.isEmpty(td));
			PerThreadQueue<CV> relPTQueue = lockData.wdcRelQueueMap.get(td);
			for (ShadowThread otherTD : ShadowThread.getThreads()) {
				if (otherTD != td) {
					while (!acqPTQueue.isEmpty(otherTD) && !acqPTQueue.peekFirst(otherTD).anyGt(wdc)) {
						acqPTQueue.removeFirst(otherTD);
						CV prevRel = relPTQueue.removeFirst(otherTD);
						
						wdc.max(prevRel);
					}
				}
			}
			
			// Rule (a)
			for (ShadowVar var : lockData.readVars) {
				CV cv = lockData.wdcReadMap.get(var);
				if (cv == null) {
					cv = new CV(WDCTool.INIT_CV_SIZE);
					lockData.wdcReadMap.put(var, cv);
				}
				cv.max(wdc);
			}
			for (ShadowVar var : lockData.writeVars) {
				CV cv = lockData.wdcWriteMap.get(var);
				if (cv == null) {
					cv = new CVE(new CV(WDCTool.INIT_CV_SIZE), thisEventNode);
					lockData.wdcWriteMap.put(var, cv);
				}
				cv.max(wdc);
			}
			
			// Assign to lock
			lockData.wdc.assignWithResize(wdc); // Used for hard notify -> wait edge
			
			// Add to release queues
			CV wdcCV = new CV(wdc);
			for (ShadowThread otherTD : ShadowThread.getThreads()) {
				if (otherTD != td) {
					PerThreadQueue<CV> queue = lockData.wdcRelQueueMap.get(otherTD);
					if (queue == null) {
						queue = lockData.wdcRelQueueGlobal.clone(); // Include any stuff that didn't get added because otherTD hadn't been created yet
						lockData.wdcRelQueueMap.put(otherTD, queue);
					}
					queue.addLast(td, wdcCV);
				}
			}
			// Also add to the queue that we'll use for any threads that haven't been created yet
			lockData.wdcRelQueueGlobal.addLast(td, wdcCV);

			// Clear read/write maps
			lockData.wdcReadMap = getPotentiallyShrunkMap(lockData.wdcReadMap);
			lockData.wdcWriteMap = getPotentiallyShrunkMap(lockData.wdcWriteMap);
		}

		if (WBR || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
			final CV wbr = ts_get_wbr(td);

			// Process queue elements
			PerThreadQueue<CVE> acqPTQueue = lockData.wbrAcqQueueMap.get(td);
			if (VERBOSE) Assert.assertTrue(acqPTQueue.isEmpty(td));
			PerThreadQueue<CVE> relPTQueue = lockData.wbrRelQueueMap.get(td);
			for (ShadowThread otherTD : ShadowThread.getThreads()) {
				if (otherTD != td) {
					while (!acqPTQueue.isEmpty(otherTD) && !acqPTQueue.peekFirst(otherTD).anyGt(wbr)) {
						acqPTQueue.removeFirst(otherTD);
						CVE prevRel = relPTQueue.removeFirst(otherTD);

						if (!DISABLE_EVENT_GRAPH && (WBR || WCP_WBR || WCP_DC_WBR)) {
							// If local VC is up-to-date w.r.t. prevRel, then no need to add a new edge
							// Protected by the fact that the lock is currently held
							if (prevRel.anyGt(wbr)) {
								EventNode.addEdge(prevRel.eventNode, thisEventNode);
							}
						}

						wbr.max(prevRel);
					}
				}
			}

			// Rule (a)
			for (ShadowVar var : lockData.writeVars) {
				lockData.wbrWriteMap.put(var, new CVE(wbr, thisEventNode));
			}

			// Assign to lock
			lockData.wbr.assignWithResize(wbr); // Used for hard notify -> wait edge

			// Add to release queues
			CVE wbrCVE = new CVE(wbr, thisEventNode);
			for (ShadowThread otherTD : ShadowThread.getThreads()) {
				if (otherTD != td) {
					PerThreadQueue<CVE> queue = lockData.wbrRelQueueMap.get(otherTD);
					if (queue == null) {
						queue = lockData.wbrRelQueueGlobal.clone(); // Include any stuff that didn't get added because otherTD hadn't been created yet
						lockData.wbrRelQueueMap.put(otherTD, queue);
					}
					queue.addLast(td, wbrCVE);
				}
			}
			// Also add to the queue that we'll use for any threads that haven't been created yet
			lockData.wbrRelQueueGlobal.addLast(td, wbrCVE);

			// Clear write map
			lockData.wbrWriteMap = getPotentiallyShrunkMap(lockData.wbrWriteMap);
		}

		// Clear read/write Vars. HB configuration does not keep track of read/write Vars
		if (!HB) {
			lockData.readVars = new HashSet<ShadowVar>();
			lockData.writeVars = new HashSet<ShadowVar>();
		}
		
		// Increment since release can have outgoing edges
		// Safe since accessed by only this thread
		if (HB || WCP || HB_WCP_DC || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
			ts_get_hb(td).inc(tid); // Don't increment WCP since it doesn't include PO
		}
		if (DC || HB_WCP_DC || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
			ts_get_wdc(td).inc(tid);
		}
		if (WBR || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
			ts_get_wbr(td).inc(tid);
		}
		if (WCP_DC_WBR_LSHE) {
			if (isHardEdge) ts_get_lshe(td).inc(tid);
		}
		
		//Set latest Release node for lockData
		lockData.latestRelNode = thisEventNode;
	}
	
	public static <K,V> WeakIdentityHashMap<K,V>getPotentiallyShrunkMap(WeakIdentityHashMap<K, V> map) {
		if (map.tableSize() > 16 &&
		    10 * map.size() < map.tableSize() * map.loadFactorSize()) {
			return new WeakIdentityHashMap<K,V>(2 * (int)(map.size() / map.loadFactorSize()), map);
		}
		return map;
	}
	
	public static boolean readFastPath(final ShadowVar orig, final ShadowThread td) {
		if (orig instanceof WDCGuardState) {
			WDCGuardState x = (WDCGuardState)orig;
//			ShadowLock lock = td.getInnermostLock();
//			if (lock != null) {
//				WDCLockData lockData = get(lock);
//				if (!lockData.readVars.contains(x) && !lockData.writeVars.contains(x) && !HB) {
//					return false;
//				}
//			}
			
			synchronized(td) {
				if (HB || WCP || HB_WCP_DC || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
					final CV hb = ts_get_hb(td);
					if (x.hbRead.get(td.getTid()) >= ts_get_eTd(td)) {
//					if (x.hbRead.get(td.getTid()) == hb.get(td.getTid()) - (!DISABLE_EVENT_GRAPH ? 1 : 0)) {
						if (COUNT_EVENT) readFP.inc(td.getTid());
						return true;
					}
				}
				if (DC) {
					final CV wdc = ts_get_wdc(td);
					if (x.wdcRead.get(td.getTid()) == wdc.get(td.getTid()) - (!DISABLE_EVENT_GRAPH ? 1 : 0)) {
						if (COUNT_EVENT) readFP.inc(td.getTid());
						return true;
					}
				}
				if (WBR) {
					final CV wbr = ts_get_wbr(td);
					if (x.wbrRead.get(td.getTid()) == wbr.get(td.getTid()) - (!DISABLE_EVENT_GRAPH ? 1 : 0)) {
						if (COUNT_EVENT) readFP.inc(td.getTid());
						return true;
					}
				}
			}
		} else {
			if (VERBOSE) Assert.assertTrue(false); // Not expecting to reach here
		}
		return false;
	}
	
	public static boolean writeFastPath(final ShadowVar orig, final ShadowThread td) {
		if (orig instanceof WDCGuardState) {
			WDCGuardState x = (WDCGuardState)orig;
//			ShadowLock lock = td.getInnermostLock();
//			if (lock != null) {
//				WDCLockData lockData = get(lock);
//				if (!lockData.writeVars.contains(x) && !HB) {
//					return false;
//				}
//			}
			
			synchronized(td) {
				if (HB || WCP || HB_WCP_DC || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
					final CV hb = ts_get_hb(td);
					if (x.hbWrite.get(td.getTid()) >= ts_get_eTd(td)) {
//					if (x.hbWrite.get(td.getTid()) == hb.get(td.getTid()) - (!DISABLE_EVENT_GRAPH ? 1 : 0)) {
						if (COUNT_EVENT) writeFP.inc(td.getTid());
						return true;
					}
				}
				// The remaining three would be incorrect since we do not increment at acquires.
				// TODO: Might have to do so in order to successfully detect fast paths for DC, CAPO, and PIP
				if (DC) {
					final CV wdc = ts_get_wdc(td);
					if (x.wdcWrite.get(td.getTid()) == wdc.get(td.getTid()) - (!DISABLE_EVENT_GRAPH ? 1 : 0)) {
						if (COUNT_EVENT) writeFP.inc(td.getTid());
						return true;
					}
				}
				if (WBR) {
					final CV wbr = ts_get_wbr(td);
					if (x.wbrWrite.get(td.getTid()) == wbr.get(td.getTid()) - (!DISABLE_EVENT_GRAPH ? 1 : 0)) {
						if (COUNT_EVENT) writeFP.inc(td.getTid());
						return true;
					}
				}
			}
		} else {
			if (VERBOSE) Assert.assertTrue(false); // Not expecting to reach here
		}
		return false;
	}


	/** Finds a common lock between two sets of locks.
	 *
	 * Expects the collections to be ordered in the order the locks were acquired in (oldest lock first).
	 * Gives the outermost common lock in prior if there are multiple common locks.
	 * If there are no common locks, returns null.
	 *
	 * Must be called while var is being held as a lock.
	 */
	ShadowLock findCommonLock(Collection<ShadowLock> prior, Collection<ShadowLock> current) {
		for (ShadowLock lock : prior) {
			if (current.contains(lock)) {
				return lock;
			}
		}
		return null;
	}

	@Override
	public void access(final AccessEvent fae) {
		final ShadowVar orig = fae.getOriginalShadow();
		final ShadowThread td = fae.getThread();

		if (orig instanceof WDCGuardState) {

			synchronized(td) {
				if (COUNT_EVENT) total.inc(td.getTid());
				if (COUNT_EVENT) {
					if (fae.isWrite()) {
						if (COUNT_EVENT) write.inc(td.getTid());
						if (COUNT_EVENT) {
							if (getCurrentCriticalSection(td) != null) {
								write_inside.inc(td.getTid());
							} else {
								write_outside.inc(td.getTid());
							}
						}
					} else {
						if (COUNT_EVENT) read.inc(td.getTid());
						if (COUNT_EVENT) {
							if (getCurrentCriticalSection(td) != null) {
								read_inside.inc(td.getTid());
							} else {
								read_outside.inc(td.getTid());
							}
						}
					}
				}
				if (COUNT_EVENT) example.inc(td.getTid());
				if (COUNT_EVENT) {
					if (getCurrentCriticalSection(td) != null) {
						access_inside.inc(td.getTid());
					} else {
						access_outside.inc(td.getTid());
					}
				}

				WDCGuardState x = (WDCGuardState)orig;
				int tid = td.getTid();
				
				String fieldName = "";
				if (PRINT_EVENT || !DISABLE_EVENT_GRAPH) {
					if (EventNode.DEBUG_ACCESS_INFO) {
						if (fae instanceof FieldAccessEvent) {
							fieldName = ((FieldAccessEvent)fae).getInfo().getField().getName();						
						} else if (fae instanceof ArrayAccessEvent) {
							fieldName = Util.objectToIdentityString(fae.getTarget()) + "[" + ((ArrayAccessEvent)fae).getIndex() + "]";						
						}
					}
				}
				
				RdWrNode thisEventNode = null;
				if (!DISABLE_EVENT_GRAPH) {
					AcqRelNode currentCriticalSection = getCurrentCriticalSection(td);
					if (EventNode.DEBUG_ACCESS_INFO) {
						thisEventNode = new RdWrDebugNode(-2, -1, fae.isWrite(), fieldName, x, td.getTid(), currentCriticalSection);
					} else {
						thisEventNode = new RdWrNode(-2, -1, fae.isWrite(), x, td.getTid(), currentCriticalSection);
					}
				}
				
				handleEvent(fae, thisEventNode);
				if (VERBOSE && !DISABLE_EVENT_GRAPH) Assert.assertTrue(thisEventNode.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));

				// Even though we capture clinit edges via classAccessed(), it doesn't seem to capture quite everything.
				// In any case, FT2 also does the following in access() in addition to classAccessed().
				Object target = fae.getTarget();
				if (target == null) {
					synchronized(classInitTime) { //Not sure what we discussed for classInit, but FT synchronizes on it so I assume the program executing does not protect accesses to classInit.
						WDCVolatileData initTime = classInitTime.get(((FieldAccessEvent)fae).getInfo().getField().getOwner());
						if (HB) ts_get_hb(td).max(initTime.hbWrite);
						if (WCP || HB_WCP_DC || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
							ts_get_hb(td).max(initTime.hbWrite);
							ts_get_wcp(td).max(initTime.hbWrite); // union with HB since this is effectively a hard WCP edge
						}
						if (DC || HB_WCP_DC || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
							ts_get_wdc(td).max(initTime.wdcWrite);
						}
						if (WBR || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
							ts_get_wbr(td).max(initTime.wbr);
						}
						if (WCP_DC_WBR_LSHE) {
							ts_get_lshe(td).max(initTime.lshe);
						}
						if (!DISABLE_EVENT_GRAPH && (WBR || WCP_WBR || WCP_DC_WBR)) {
							if (initTime.wbr.anyGt(ts_get_wbr(td))) {
								EventNode.addEdge(initTime.wbr.eventNode, thisEventNode);
							}
						}
					}
				}
				

				// Update variables accessed in critical sections for rule (a)
				for (int i = td.getNumLocksHeld() - 1; i >= 0; i--) {
					ShadowLock lock = td.getHeldLock(i);
					WDCLockData lockData = get(lock);

					// Account for conflicts with prior critical section instances
					if (WCP || HB_WCP_DC || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
						final CV wcp = ts_get_wcp(td);
						final CV priorCriticalSectionAfterWrite = lockData.wcpWriteMap.get(x);
						if (priorCriticalSectionAfterWrite != null) {
							wcp.max(priorCriticalSectionAfterWrite);
						}
						if (fae.isWrite()) {
							CV priorCriticalSectionAfterRead = lockData.wcpReadMap.get(x);
							if (priorCriticalSectionAfterRead != null) {
								wcp.max(priorCriticalSectionAfterRead);
							}
						}
					}
					if (DC || HB_WCP_DC || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
						final CV wdc = ts_get_wdc(td);
						final CV priorCriticalSectionAfterWrite = lockData.wdcWriteMap.get(x);
						if (priorCriticalSectionAfterWrite != null) {
							wdc.max(priorCriticalSectionAfterWrite);
						}
						if (fae.isWrite()) {
							CV priorCriticalSectionAfterRead = lockData.wdcReadMap.get(x);
							if (priorCriticalSectionAfterRead != null) {
	
								wdc.max(priorCriticalSectionAfterRead);
							}
						}
					}
					
					// Keep track of accesses within ongoing critical section
					if (!HB) { // HB analysis does not use read/write Vars
						if (fae.isWrite()) {
							lockData.writeVars.add(x);
						} else {
							lockData.readVars.add(x);
						}
					}
				}
				
				// Have to lock on variable x here until the end of the access event
				synchronized(x) {
					// Check for races: HB ⊆ WCP ⊆ DC ⊆ WBR
					if (HB) {
						final CV hb = ts_get_hb(td);
						boolean foundRace = checkForRacesHB(fae.isWrite(), x, fae, tid, hb);
						// Update thread VCs if race detected (to correspond with edge being added)
						// TODO: probably not needed for hb if it really is only going to affect constraint graph
						if (foundRace) {
							hb.max(x.hbWrite);
							if (fae.isWrite()) {
								hb.max(x.hbReadsJoined);
							}
						} else { // Check that we don't need to update CVs if there was no race)
							if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
							if (fae.isWrite()) {
								if (VERBOSE) Assert.assertTrue(!x.hbReadsJoined.anyGt(hb));
							}
						}
					}
					if (WCP) {
						final CV hb = ts_get_hb(td);
						final CV wcp = ts_get_wcp(td);
						boolean foundRace = checkForRacesWCP(fae.isWrite(), x, fae, tid, hb, wcp);
						// Update thread VCs if race detected (to correspond with edge being added)
						if (foundRace) {
							hb.max(x.hbWrite);
							wcp.max(x.hbWrite);
							if (fae.isWrite()) {
								hb.max(x.hbReadsJoined);
								wcp.max(x.hbReadsJoined);
							}
						} else { // Check that we don't need to update CVs if there was no race)
							if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
							final CV wcpUnionPO = new CV(wcp);
							wcpUnionPO.set(tid, hb.get(tid));
							if (VERBOSE) Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));
							if (fae.isWrite()) {
								if (VERBOSE) Assert.assertTrue(!x.hbReadsJoined.anyGt(hb));
								if (VERBOSE) Assert.assertTrue(!x.wcpReadsJoined.anyGt(wcpUnionPO));
							}
						}
					}
					if (DC) {
						final CV wdc = ts_get_wdc(td);
						boolean foundRace = checkForRacesDC(fae.isWrite(), x, fae, tid, wdc);
						// Update thread VCs if race detected (to correspond with edge being added)
						if (foundRace) {
							wdc.max(x.wdcWrite);
							if (fae.isWrite()) {
								wdc.max(x.wdcReadsJoined);
							}
						} else { // Check that we don't need to update CVs if there was no race)
							if (VERBOSE) Assert.assertTrue(!x.wdcWrite.anyGt(wdc));
							if (fae.isWrite()) {
								if (VERBOSE) Assert.assertTrue(!x.wdcReadsJoined.anyGt(wdc));
							}
						}
					}
					if (WBR) {
						final CV wbr = ts_get_wbr(td);

						// Rule a, wr-rd edge from the last writer of the read
						if (fae.isRead()) {
							ts_set_hasread(td, true); // Mark that we have seen a read, next branch can't be a fast path
							if (x.hasLastWriter() && x.lastWriteTid != td.getTid()) {
								ShadowLock shadowLock = findCommonLock(x.heldLocksWrite.get(x.lastWriteTid), td.getLocksHeld());
								if (shadowLock != null) { // Common lock exists, prepare for the edge
									WDCLockData lock = get(shadowLock);
									CVE writes = lock.wbrWriteMap.get(x);
									if (writes.anyGt(wbr)) { // Only prepare for the edge if it is not redundant
										ts_get_wbr_delayed(td).put(x, writes);
									}
								}
							}
						}

						checkForRacesWBR(fae.isWrite(), x, fae, td, wbr, thisEventNode);
						// WBR race edges are drawn in checkForRacesWBR
					}
					if (HB_WCP_DC) {
						final CV hb = ts_get_hb(td);
						final CV wcp = ts_get_wcp(td);
						final CV wdc = ts_get_wdc(td);
						boolean foundRace = checkForRacesDC(fae.isWrite(), x, fae, tid, hb, wcp, wdc);
						// Update thread VCs if race detected (to correspond with edge being added)
						if (foundRace) {
							hb.max(x.hbWrite);
							wcp.max(x.hbWrite);
							wdc.max(x.wdcWrite);
							if (fae.isWrite()) {
								hb.max(x.hbReadsJoined);
								wcp.max(x.hbReadsJoined);
								wdc.max(x.wdcReadsJoined);
							}
						} else { // Check that we don't need to update CVs if there was no race)
							if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
							final CV wcpUnionPO = new CV(wcp);
							wcpUnionPO.set(tid, hb.get(tid));
							if (VERBOSE) Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));
							if (VERBOSE) Assert.assertTrue(!x.wdcWrite.anyGt(wdc));
							if (fae.isWrite()) {
								if (VERBOSE) Assert.assertTrue(!x.hbReadsJoined.anyGt(hb));
								if (VERBOSE) Assert.assertTrue(!x.wcpReadsJoined.anyGt(wcpUnionPO));
								if (VERBOSE) Assert.assertTrue(!x.wdcReadsJoined.anyGt(wdc));
							}
						}
					}
					if (WCP_WBR) {
						final CV hb = ts_get_hb(td);
						final CV wcp = ts_get_wcp(td);
						final CV wbr = ts_get_wbr(td);

						// WBR Rule a, wr-rd edge from the last writer of the read
						if (fae.isRead()) {
							ts_set_hasread(td, true); // Mark that we have seen a read, next branch can't be a fast path
							if (x.hasLastWriter() && x.lastWriteTid != td.getTid()) {
								ShadowLock shadowLock = findCommonLock(x.heldLocksWrite.get(x.lastWriteTid), td.getLocksHeld());
								if (shadowLock != null) { // Common lock exists, prepare for the edge
									WDCLockData lock = get(shadowLock);
									CVE writes = lock.wbrWriteMap.get(x);
									if (writes.anyGt(wbr)) { // Only prepare for the edge if it is not redundant
										ts_get_wbr_delayed(td).put(x, writes);
									}
								}
							}
						}

						boolean foundRace = checkForRacesWBR(fae.isWrite(), x, fae, td, hb, wcp, wbr, thisEventNode);
						// Update thread VCs if race detected (to correspond with edge being added)
						if (foundRace) {
							hb.max(x.hbWrite);
							wcp.max(x.hbWrite);
							if (fae.isWrite()) {
								hb.max(x.hbReadsJoined);
								wcp.max(x.hbReadsJoined);
							}
						} else { // Check that we don't need to update CVs if there was no race)
							if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
							final CV wcpUnionPO = new CV(wcp);
							wcpUnionPO.set(tid, hb.get(tid));
							if (VERBOSE) Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));
//							if (VERBOSE) Assert.assertTrue(!x.wbrWrite.anyGt(wbr)); // Doesn't hold for WBR due to locksets
							if (fae.isWrite()) {
								if (VERBOSE) Assert.assertTrue(!x.hbReadsJoined.anyGt(hb));
								if (VERBOSE) Assert.assertTrue(!x.wcpReadsJoined.anyGt(wcpUnionPO));
//								if (VERBOSE) Assert.assertTrue(!x.wbrReadsJoined.anyGt(wbr)); // Doesn't hold for WBR due to locksets
							}
						}
					}
					if (WCP_DC_WBR) {
						final CV hb = ts_get_hb(td);
						final CV wcp = ts_get_wcp(td);
						final CV wdc = ts_get_wdc(td);
						final CV wbr = ts_get_wbr(td);

						// WBR Rule a, wr-rd edge from the last writer of the read
						if (fae.isRead()) {
							ts_set_hasread(td, true); // Mark that we have seen a read, next branch can't be a fast path
							if (x.hasLastWriter() && x.lastWriteTid != td.getTid()) {
								ShadowLock shadowLock = findCommonLock(x.heldLocksWrite.get(x.lastWriteTid), td.getLocksHeld());
								if (shadowLock != null) { // Common lock exists, prepare for the edge
									WDCLockData lock = get(shadowLock);
									CVE writes = lock.wbrWriteMap.get(x);
									if (writes.anyGt(wbr)) { // Only prepare for the edge if it is not redundant
										ts_get_wbr_delayed(td).put(x, writes);
									}
								}
							}
						}

						boolean foundRace = checkForRacesWBR(fae.isWrite(), x, fae, td, hb, wcp, wdc, wbr, thisEventNode);
						// Update thread VCs if race detected (to correspond with edge being added)
						if (foundRace) {
							// Prior relations order all conflicting accesses, WBR race edges are drawn in checkForRacesWBR
							hb.max(x.hbWrite);
							wcp.max(x.hbWrite);
							wdc.max(x.wdcWrite);
							if (fae.isWrite()) {
								hb.max(x.hbReadsJoined);
								wcp.max(x.hbReadsJoined);
								wdc.max(x.wdcReadsJoined);
							}
						} else { // Check that we don't need to update CVs if there was no race)
							if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
							final CV wcpUnionPO = new CV(wcp);
							wcpUnionPO.set(tid, hb.get(tid));
							if (VERBOSE) Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));
							if (VERBOSE) Assert.assertTrue(!x.wdcWrite.anyGt(wdc));
//							if (VERBOSE) Assert.assertTrue(!x.wbrWrite.anyGt(wbr)); // Doesn't hold for WBR due to locksets
							if (fae.isWrite()) {
								if (VERBOSE) Assert.assertTrue(!x.hbReadsJoined.anyGt(hb));
								if (VERBOSE) Assert.assertTrue(!x.wcpReadsJoined.anyGt(wcpUnionPO));
								if (VERBOSE) Assert.assertTrue(!x.wdcReadsJoined.anyGt(wdc));
//								if (VERBOSE) Assert.assertTrue(!x.wbrReadsJoined.anyGt(wbr)); // Doesn't hold for WBR due to locksets
							}
						}
					}
					if (WCP_DC_WBR_LSHE) {
						final CV hb = ts_get_hb(td);
						final CV wcp = ts_get_wcp(td);
						final CV wdc = ts_get_wdc(td);
						final CV wbr = ts_get_wbr(td);
						final CV lshe = ts_get_lshe(td);

						// WBR Rule a, wr-rd edge from the last writer of the read
						if (fae.isRead()) {
							ts_set_hasread(td, true); // Mark that we have seen a read, next branch can't be a fast path
							if (x.hasLastWriter() && x.lastWriteTid != td.getTid()) {
								ShadowLock shadowLock = findCommonLock(x.heldLocksWrite.get(x.lastWriteTid), td.getLocksHeld());
								if (shadowLock != null) { // Common lock exists, prepare for the edge
									WDCLockData lock = get(shadowLock);
									CVE writes = lock.wbrWriteMap.get(x);
									if (writes.anyGt(wbr)) { // Only prepare for the edge if it is not redundant
										ts_get_wbr_delayed(td).put(x, writes);
									}
								}
							}
						}

						boolean foundRace = checkForRacesLSHE(fae.isWrite(), x, fae, td, hb, wcp, wdc, wbr, lshe, thisEventNode);
						// Update thread VCs if race detected (to correspond with edge being added)
						if (foundRace) {
							// Prior relations order all conflicting accesses, WBR race edges are drawn in checkForRacesWBR
							hb.max(x.hbWrite);
							wcp.max(x.hbWrite);
							wdc.max(x.wdcWrite);
							if (fae.isWrite()) {
								hb.max(x.hbReadsJoined);
								wcp.max(x.hbReadsJoined);
								wdc.max(x.wdcReadsJoined);
							}
						} else { // Check that we don't need to update CVs if there was no race)
							if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
							final CV wcpUnionPO = new CV(wcp);
							wcpUnionPO.set(tid, hb.get(tid));
							if (VERBOSE) Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));
							if (VERBOSE) Assert.assertTrue(!x.wdcWrite.anyGt(wdc));
//							if (VERBOSE) Assert.assertTrue(!x.wbrWrite.anyGt(wbr)); // Doesn't hold for WBR due to locksets
							// Doesn't hold for LSHE due to locksets?
							if (fae.isWrite()) {
								if (VERBOSE) Assert.assertTrue(!x.hbReadsJoined.anyGt(hb));
								if (VERBOSE) Assert.assertTrue(!x.wcpReadsJoined.anyGt(wcpUnionPO));
								if (VERBOSE) Assert.assertTrue(!x.wdcReadsJoined.anyGt(wdc));
//								if (VERBOSE) Assert.assertTrue(!x.wbrReadsJoined.anyGt(wbr)); // Doesn't hold for WBR due to locksets
								// Doesn't hold for LSHE due to locksets?
							}
						}
					}
	
					if (!DISABLE_EVENT_GRAPH) {
						// Can combine two consecutive write/read nodes that have the same VC.
						// We might later add an outgoing edge from the prior node, but that's okay.
						EventNode oldThisEventNode = thisEventNode;
						thisEventNode = thisEventNode.tryToMergeWithPrior();
						// If merged, undo prior increment
						if (thisEventNode != oldThisEventNode) {
							if (HB_WCP_DC || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
								ts_get_hb(td).inc(tid, -1);
							}
							if (DC || HB_WCP_DC || WCP_DC_WBR  || WCP_DC_WBR_LSHE) {
								ts_get_wdc(td).inc(tid, -1);
							}
							if (WBR || WCP_WBR || WCP_DC_WBR  || WCP_DC_WBR_LSHE) {
								ts_get_wbr(td).inc(tid, -1);
							}
							if (WCP_DC_WBR_LSHE) {
								ts_get_lshe(td).inc(tid, -1);
							}
							ts_set_lastEventNode(td, thisEventNode);
						}
					}
					
					// Update vector clocks
					if (HB) {
						final CV hb = ts_get_hb(td);
						if (fae.isWrite()) {
							x.hbWrite.assignWithResize(hb);
						} else {
							x.hbRead.set(tid, hb.get(tid));
							x.hbReadsJoined.max(hb);
						}
					}
					if (WCP || HB_WCP_DC || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
						final CV hb = ts_get_hb(td);
						final CV wcp = ts_get_wcp(td);
						final CV wcpUnionPO = new CV(wcp);
						wcpUnionPO.set(tid, hb.get(tid));
						
						if (fae.isWrite()) {
							x.hbWrite.assignWithResize(hb);
							x.wcpWrite.assignWithResize(wcpUnionPO);
						} else {
							x.hbRead.set(tid, hb.get(tid));
							x.hbReadsJoined.max(hb);
							x.wcpRead.set(tid, hb.get(tid));
							x.wcpReadsJoined.max(wcpUnionPO);
						}
					}
					if (DC || HB_WCP_DC || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
						final CV wdc = ts_get_wdc(td);
						if (fae.isWrite()) {
							x.wdcWrite.assignWithResize(wdc);
						} else {
							x.wdcRead.set(tid, wdc.get(tid));
							x.wdcReadsJoined.max(wdc);
						}
					}
					if (WBR || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
						final CV wbr = ts_get_wbr(td);
						if (fae.isWrite()) {
							// TODO: Is this correct? I'm seeing races with threads that never accessed this variable
							// x.wbrWrite.assignWithResize(wbr);
							x.wbrWrite.set(tid, wbr.get(tid));
							x.heldLocksWrite.put(td.getTid(), td.getLocksHeld());
						} else {
							x.wbrRead.set(tid, wbr.get(tid));
							x.wbrReadsJoined.max(wbr);
							x.heldLocksRead.put(td.getTid(), td.getLocksHeld());
							if (!DISABLE_EVENT_GRAPH) {
								if (x.hasLastWriter()) {
									RdWrNode lastWriter = x.lastWriteEvents[x.lastWriteTid].eventNode;
									thisEventNode.setLastWriter(lastWriter);
									thisEventNode.eventNumber = Math.max(thisEventNode.eventNumber, lastWriter.eventNumber + 1);
								} else {
									thisEventNode.setHasNoLastWriter();
								}
							}
						}
					}
					if (WCP_DC_WBR_LSHE) {
						final CV lshe = ts_get_lshe(td);
						if (fae.isWrite()) {
							x.lsheWrite.assignWithResize(lshe);
							x.heldLocksWrite.put(td.getTid(), td.getLocksHeld());
						} else {
							x.lsheRead.set(tid, lshe.get(tid));
							x.lsheReadsJoined.max(lshe);
							x.heldLocksRead.put(td.getTid(), td.getLocksHeld());
						}
					}
					
					// Update last event
					final MethodEvent me = td.getBlockDepth() <= 0 ? null : td.getBlock(td.getBlockDepth()-1); //This is how RREventGenerator retrieves a method event
					DynamicSourceLocation dl = new DynamicSourceLocation(fae, thisEventNode, (me == null ? null : me.getInfo()));
					
					if (fae.isWrite()) {
						x.lastWriteTid = tid;
						x.lastWriteEvents[tid] = dl;
					} else {
						x.lastReadEvents[tid] = dl;
					}
					
					// These increments are needed because we might end up creating an outgoing WDC edge from this access event
					// (if it turns out to be involved in a WDC-race).
					if (HB_WCP_DC || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
						ts_get_hb(td).inc(tid); // Don't increment WCP since it doesn't include PO
					}
					if (DC || HB_WCP_DC || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
						ts_get_wdc(td).inc(tid);
					}
					if (WBR || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
						ts_get_wbr(td).inc(tid);
					}
					if (WCP_DC_WBR_LSHE) {
						ts_get_lshe(td).inc(tid);
					}
				}
				
				if (PRINT_EVENT) {		
					if (fae.isWrite()) {
						Util.log("wr("+ fieldName +") by T"+td.getTid()+(!DISABLE_EVENT_GRAPH ? ", event count:"+thisEventNode.eventNumber : ""));
					} else {
						Util.log("rd("+ fieldName +") by T"+td.getTid()+(!DISABLE_EVENT_GRAPH ? ", event count:"+thisEventNode.eventNumber : ""));
					}
				}

				if ((WBR || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) && fae instanceof ArrayAccessEvent) { // Array reads are treated as being followed by branches
					branch((ArrayAccessEvent)fae);
				}
			}
		} else {
			if (VERBOSE) Assert.assertTrue(false); // Not expecting to reach here
			super.access(fae);
		}
	}

	public boolean recordRace(WDCGuardState x, AccessEvent ae, int tid, int shortestRaceTid, boolean shortestRaceIsWrite, RaceType shortestRaceType, RdWrNode thisEventNode) {
		if (shortestRaceTid >= 0) {
			DynamicSourceLocation priorDL = (shortestRaceIsWrite ? x.lastWriteEvents[shortestRaceTid] : x.lastReadEvents[shortestRaceTid]);
			// Report race
			error(ae, shortestRaceType.relation(), shortestRaceIsWrite ? "write by T" : "read by T", shortestRaceTid, priorDL,
			      ae.isWrite() ? "write by T" : "read by T", tid);

			StaticRace staticRace;
			// Record the WDC-race for later processing
			if (!DISABLE_EVENT_GRAPH) {
				// Check assertions:
				//This assertion is checked in crazyNewEdges() when adding back/forward edges. It fails here, but not when checked in crazyNewEdges().
				//Both traversals below are forward traversals, so sinkOrSinks is accessed during the bfsTraversal. The bfsTraversal is not executed atomically which causes the assertion to fail at different event counts.
				Assert.assertTrue(thisEventNode != null);
				if (false) {
					EventNode.removeEdge(priorDL.eventNode, thisEventNode);
					Assert.assertTrue(!EventNode.bfsTraversal(priorDL.eventNode, thisEventNode, null, Long.MIN_VALUE, Long.MAX_VALUE), "prior Tid: " + priorDL.eventNode.threadID + " | this Tid: " + thisEventNode.threadID);
					Assert.assertTrue(!EventNode.bfsTraversal(thisEventNode, priorDL.eventNode, null, Long.MIN_VALUE, Long.MAX_VALUE));
					EventNode.addEdge(priorDL.eventNode, thisEventNode);
				}

				ShadowThread td = ae.getThread();
				final MethodEvent me = td.getBlockDepth() <= 0 ? null : td.getBlock(td.getBlockDepth()-1); //This is how RREventGenerator retrieves a method event
				staticRace = new StaticRace(priorDL.loc, ae.getAccessInfo().getLoc(), priorDL.eventNode, thisEventNode, shortestRaceType, priorDL.eventMI, me.getInfo());
				StaticRace.races.add(staticRace);
			} else {
				staticRace = new StaticRace(priorDL.loc, ae.getAccessInfo().getLoc());
			}
			
			// Record the static race for statistics
			StaticRace.addRace(staticRace, shortestRaceType);
			
			return true;
		}
		return false;
	}
	
	// NOTE: This should be protected by the lock on variable x in the access event
	boolean checkForRacesHB(boolean isWrite, WDCGuardState x, AccessEvent ae, int tid, CV hb) {
		int shortestRaceTid = -1;
		boolean shortestRaceIsWrite = false; // only valid if shortestRaceTid != -1
		RaceType shortestRaceType = RaceType.WBROrdered; // only valid if shortestRaceTid != -1
		
		// First check for race with prior write
		if (x.hbWrite.anyGt(hb)) {
			RaceType type = RaceType.HBRace;
			shortestRaceTid = x.lastWriteTid;
			shortestRaceIsWrite = true;
			shortestRaceType = type;
		}
		// Next check for races with prior reads
		if (isWrite) {
			if (x.hbRead.anyGt(hb)) {
				int index = -1;
				while ((index = x.hbRead.nextGt(hb, index + 1)) != -1) {
					RaceType type = RaceType.HBRace;
					shortestRaceTid = index;
					shortestRaceIsWrite = false;
					shortestRaceType = type;
				}
			}
		}
		return recordRace(x, ae, tid, shortestRaceTid, shortestRaceIsWrite, shortestRaceType, null);
	}
	
	boolean checkForRacesWCP(boolean isWrite, WDCGuardState x, AccessEvent ae, int tid, CV hb, CV wcp) {
		final CV wcpUnionPO = new CV(wcp);
		wcpUnionPO.set(tid, hb.get(tid));
		int shortestRaceTid = -1;
		boolean shortestRaceIsWrite = false; // only valid if shortestRaceTid != -1
		RaceType shortestRaceType = RaceType.WBROrdered; // only valid if shortestRaceTid != -1
		
		// First check for race with prior write
		if (x.wcpWrite.anyGt(wcpUnionPO)) {
			RaceType type = RaceType.WCPRace;
			if (x.hbWrite.anyGt(hb)) {
				type = RaceType.HBRace;
			}
			shortestRaceTid = x.lastWriteTid;
			shortestRaceIsWrite = true;
			shortestRaceType = type;
		} else {
			if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
		}
		// Next check for races with prior reads
		if (isWrite) {
			if (x.wcpRead.anyGt(wcpUnionPO)) {
				int index = -1;
				while ((index = x.wcpRead.nextGt(wcpUnionPO, index + 1)) != -1) {
					RaceType type = RaceType.WCPRace;
					if (x.hbRead.get(index) > hb.get(index)) {
						type = RaceType.HBRace;
					}						
					// Update the latest race with the current race since we only want to report one race per access event
					shortestRaceTid = index;
					shortestRaceIsWrite = false;
					shortestRaceType = type;
				}
			} else {
				if (VERBOSE) Assert.assertTrue(!x.hbRead.anyGt(hb));
			}
		}
		return recordRace(x, ae, tid, shortestRaceTid, shortestRaceIsWrite, shortestRaceType, null);
	}

	boolean checkForRacesDC(boolean isWrite, WDCGuardState x, AccessEvent ae, int tid, CV wdc) {
		int shortestRaceTid = -1;
		boolean shortestRaceIsWrite = false; // only valid if shortestRaceTid != -1
		RaceType shortestRaceType = RaceType.WBROrdered; // only valid if shortestRaceTid != -1
		
		// First check for race with prior write
		if (x.wdcWrite.anyGt(wdc)) {
			RaceType type = RaceType.WDCRace;
			shortestRaceTid = x.lastWriteTid;
			shortestRaceIsWrite = true;
			shortestRaceType = type;
		}
		// Next check for races with prior reads
		if (isWrite) {
			if (x.wdcRead.anyGt(wdc)) {
				int index = -1;
				while ((index = x.wdcRead.nextGt(wdc, index + 1)) != -1) {
					RaceType type = RaceType.WDCRace;
					shortestRaceTid = index;
					shortestRaceIsWrite = false;
					shortestRaceType = type;
				}
			}
		}
		return recordRace(x, ae, tid, shortestRaceTid, shortestRaceIsWrite, shortestRaceType, null);
	}
	
	boolean checkForRacesDC(boolean isWrite, WDCGuardState x, AccessEvent ae, int tid, CV hb, CV wcp, CV wdc) {
		final CV wcpUnionPO = new CV(wcp);
		wcpUnionPO.set(tid, hb.get(tid));
		int shortestRaceTid = -1;
		boolean shortestRaceIsWrite = false; // only valid if shortestRaceTid != -1
		RaceType shortestRaceType = RaceType.WBROrdered; // only valid if shortestRaceTid != -1
		
		// First check for race with prior write
		if (x.wdcWrite.anyGt(wdc)) {
			RaceType type = RaceType.WDCRace;
			if (x.wcpWrite.anyGt(wcpUnionPO)) {
				type = RaceType.WCPRace;
				if (x.hbWrite.anyGt(hb)) {
					type = RaceType.HBRace;
				}
			} else {
				if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
			}
			shortestRaceTid = x.lastWriteTid;
			shortestRaceIsWrite = true;
			shortestRaceType = type;
		} else {
			if (VERBOSE) Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));//, "VC x: " + x.wcpWrite.toString() + "| VC wcpUnionPO: " + wcpUnionPO.toString());
			if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
		}
		// Next check for races with prior reads
		if (isWrite) {
			if (x.wdcRead.anyGt(wdc)) {
				int index = -1;
				while ((index = x.wdcRead.nextGt(wdc, index + 1)) != -1) {
					RaceType type = RaceType.WDCRace;
					if (x.wcpRead.get(index) > wcpUnionPO.get(index)) {
						type = RaceType.WCPRace;
						if (x.hbRead.get(index) > hb.get(index)) {
							type = RaceType.HBRace;
						}
					} else {
						if (VERBOSE) Assert.assertTrue(x.hbRead.get(index) <= hb.get(index));
					}
					//Update the latest race with the current race since we only want to report one race per access event
					DynamicSourceLocation dl = shortestRaceTid >= 0 ? (shortestRaceIsWrite ? x.lastWriteEvents[shortestRaceTid] : x.lastReadEvents[shortestRaceTid]) : null;
					if (DISABLE_EVENT_GRAPH || (dl == null || x.lastReadEvents[index].eventNode.eventNumber > dl.eventNode.eventNumber)) {
						shortestRaceTid = index;
						shortestRaceIsWrite = false;
						shortestRaceType = type;
					}
					if (VERBOSE) Assert.assertTrue(x.lastReadEvents[index] != null);//, "lastReadEvents x: " + x.lastReadEvents.toString() + ", index " + index + " is null");
				}
			} else {
				if (VERBOSE) Assert.assertTrue(!x.wcpRead.anyGt(wcpUnionPO));//, "VC x: " + x.wcpRead.toString() + "| VC wcpUnionPO: " + wcpUnionPO.toString());
				if (VERBOSE) Assert.assertTrue(!x.hbRead.anyGt(hb));
			}
		}
		return recordRace(x, ae, tid, shortestRaceTid, shortestRaceIsWrite, shortestRaceType, null);
	}

	boolean checkForRacesWBR(boolean isWrite, WDCGuardState x, AccessEvent ae, ShadowThread td, CV wbr, RdWrNode thisEventNode) {
		int shortestRaceTid = -1;
		boolean shortestRaceIsWrite = false; // only valid if shortestRaceTid != -1
		RaceType shortestRaceType = RaceType.WBROrdered; // only valid if shortestRaceTid != -1
		Collection<ShadowLock> heldLocks = td.getLocksHeld();

		// First check for race with prior write
		int index = -1;
		while ((index = x.wbrWrite.nextGt(wbr, index + 1)) != -1) {
			if (findCommonLock(x.heldLocksWrite.get(index), heldLocks) == null) {
				RaceType type = RaceType.WBRRace;
				shortestRaceTid = index;
				shortestRaceIsWrite = true;
				shortestRaceType = type;
				// Keep event numbers of racing evenst in observed order even if we don't draw an edge, to avoid accidentally reordering them when they are outside the race window
				thisEventNode.eventNumber = Math.max(thisEventNode.eventNumber, x.lastWriteEvents[shortestRaceTid].eventNode.eventNumber + 1);
				// Race edge, only for wr-rd races, and only when the racing write is the last writer of the read. Will be later drawn to a branch.
				if (!isWrite && x.lastWriteTid == shortestRaceTid) {
					Map<ShadowVar,CVE> delayed = ts_get_wbr_delayed(td);
					delayed.put(x, new CVE(x.wbrWrite, x.lastWriteEvents[shortestRaceTid].eventNode));
				}
			}
		}
		// Next check for races with prior reads
		if (isWrite) {
			index = -1;
			while ((index = x.wbrRead.nextGt(wbr, index + 1)) != -1) {
				if (findCommonLock(x.heldLocksRead.get(index), heldLocks) == null) {
					RaceType type = RaceType.WBRRace;
					shortestRaceTid = index;
					shortestRaceIsWrite = false;
					shortestRaceType = type;
					// Keep event numbers of racing evenst in observed order even if we don't draw an edge, to avoid accidentally reordering them when they are outside the race window
					thisEventNode.eventNumber = Math.max(thisEventNode.eventNumber, x.lastReadEvents[shortestRaceTid].eventNode.eventNumber + 1);
				}
			}
		}
		return recordRace(x, ae, td.getTid(), shortestRaceTid, shortestRaceIsWrite, shortestRaceType, thisEventNode);
	}

	boolean checkForRacesWBR(boolean isWrite, WDCGuardState x, AccessEvent ae, ShadowThread td, CV hb, CV wcp, CV wbr, RdWrNode thisEventNode) {
		final CV wcpUnionPO = new CV(wcp);
		int tid = td.getTid();
		wcpUnionPO.set(tid, hb.get(tid));
		int shortestRaceTid = -1;
		boolean shortestRaceIsWrite = false; // only valid if shortestRaceTid != -1
		RaceType shortestRaceType = RaceType.WBROrdered; // only valid if shortestRaceTid != -1
		Collection<ShadowLock> heldLocks = td.getLocksHeld();

		// First check for race with prior write
		if (x.wbrWrite.anyGt(wbr)) {
			int index = -1;
			while ((index = x.wbrWrite.nextGt(wbr, index + 1)) != -1) {
				if (findCommonLock(x.heldLocksWrite.get(index), heldLocks) == null) {
					RaceType type = RaceType.WBRRace;
					if (x.wcpWrite.anyGt(wcpUnionPO)) {
						type = RaceType.WCPRace;
						if (x.hbWrite.anyGt(hb)) {
							type = RaceType.HBRace;
						}
					} else {
						if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
					}
					shortestRaceTid = index;
					shortestRaceIsWrite = true;
					shortestRaceType = type;
					// Keep event numbers of racing evenst in observed order even if we don't draw an edge, to avoid accidentally reordering them when they are outside the race window
					if (!DISABLE_EVENT_GRAPH) thisEventNode.eventNumber = Math.max(thisEventNode.eventNumber, x.lastWriteEvents[index].eventNode.eventNumber + 1);
					// Race edge, only for wr-rd races, and only when the racing write is the last writer of the read. Will be later drawn to a branch.
					if (!isWrite && x.lastWriteTid == shortestRaceTid) {
						Map<ShadowVar,CVE> delayed = ts_get_wbr_delayed(td);
						delayed.put(x, new CVE(x.wbrWrite, x.lastWriteEvents[shortestRaceTid].eventNode));
					}
				} else {
					if (VERBOSE) Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));//, "VC x: " + x.wcpWrite.toString() + "| VC wcpUnionPO: " + wcpUnionPO.toString());
					if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
				}
			}
		} else {
			if (VERBOSE) Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));//, "VC x: " + x.wcpWrite.toString() + "| VC wcpUnionPO: " + wcpUnionPO.toString());
			if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
		}
		// Next check for races with prior reads
		if (isWrite) {
			if (x.wbrRead.anyGt(wbr)) {
				int index = -1;
				while ((index = x.wbrRead.nextGt(wbr, index + 1)) != -1) {
					if (findCommonLock(x.heldLocksRead.get(index), heldLocks) == null) {
						RaceType type = RaceType.WBRRace;
						if (x.wcpRead.get(index) > wcpUnionPO.get(index)) {
							type = RaceType.WCPRace;
							if (x.hbRead.get(index) > hb.get(index)) {
								type = RaceType.HBRace;
							}
						} else {
							if (VERBOSE) Assert.assertTrue(x.hbRead.get(index) <= hb.get(index));
						}
						// Keep event numbers of racing evenst in observed order even if we don't draw an edge, to avoid accidentally reordering them when they are outside the race window
						if (!DISABLE_EVENT_GRAPH) thisEventNode.eventNumber = Math.max(thisEventNode.eventNumber, x.lastReadEvents[index].eventNode.eventNumber + 1);
						//Update the latest race with the current race since we only want to report one race per access event
						DynamicSourceLocation dl = shortestRaceTid >= 0 ? (shortestRaceIsWrite ? x.lastWriteEvents[shortestRaceTid] : x.lastReadEvents[shortestRaceTid]) : null;
						if (DISABLE_EVENT_GRAPH || (dl == null || x.lastReadEvents[index].eventNode.eventNumber > dl.eventNode.eventNumber)) {
							shortestRaceTid = index;
							shortestRaceIsWrite = false;
							shortestRaceType = type;
						}
						if (VERBOSE)
							Assert.assertTrue(x.lastReadEvents[index] != null);//, "lastReadEvents x: " + x.lastReadEvents.toString() + ", index " + index + " is null");
						if (!DISABLE_EVENT_GRAPH && VERBOSE)
							Assert.assertTrue(x.lastReadEvents[index].eventNode != null);//, "lastReadEvents x: " + x.lastReadEvents.toString() + ", index " + index + " containing dl: " + x.lastReadEvents[index].toString() + " has null event node.");
						if (!DISABLE_EVENT_GRAPH) {
							// This thread's last reader node might be same as the last writer node, due to merging
							//TODO: Tomcat fails if x.lastWriteEvents not checked for null. This would happen if only reads came before the first write?
							if (x.lastWriteEvents[index] != null && x.lastReadEvents[index].eventNode == x.lastWriteEvents[index].eventNode) {
								if (VERBOSE)
									Assert.assertTrue(EventNode.edgeExists(x.lastWriteEvents[index].eventNode, thisEventNode));
							} else {
								// We don't draw race edges for rd-wr races
//								EventNode.addEdge(x.lastReadEvents[index].eventNode, thisEventNode);
							}
						}
					} else {
						if (VERBOSE) Assert.assertTrue(!x.wcpRead.anyGt(wcpUnionPO));//, "VC x: " + x.wcpRead.toString() + "| VC wcpUnionPO: " + wcpUnionPO.toString());
						if (VERBOSE) Assert.assertTrue(!x.hbRead.anyGt(hb));
					}
				}
			} else {
				if (VERBOSE) Assert.assertTrue(!x.wcpRead.anyGt(wcpUnionPO));//, "VC x: " + x.wcpRead.toString() + "| VC wcpUnionPO: " + wcpUnionPO.toString());
				if (VERBOSE) Assert.assertTrue(!x.hbRead.anyGt(hb));
			}
		}
		return recordRace(x, ae, tid, shortestRaceTid, shortestRaceIsWrite, shortestRaceType, thisEventNode);
	}

	boolean checkForRacesWBR(boolean isWrite, WDCGuardState x, AccessEvent ae, ShadowThread td, CV hb, CV wcp, CV wdc, CV wbr, RdWrNode thisEventNode) {
		final CV wcpUnionPO = new CV(wcp);
		int tid = td.getTid();
		wcpUnionPO.set(tid, hb.get(tid));
		int shortestRaceTid = -1;
		boolean shortestRaceIsWrite = false; // only valid if shortestRaceTid != -1
		RaceType shortestRaceType = RaceType.WBROrdered; // only valid if shortestRaceTid != -1
		Collection<ShadowLock> heldLocks = td.getLocksHeld();

		// First check for race with prior write
		if (x.wbrWrite.anyGt(wbr)) {
			int index = -1;
			while ((index = x.wbrWrite.nextGt(wbr, index + 1)) != -1) {
				if (findCommonLock(x.heldLocksWrite.get(index), heldLocks) == null) {
					RaceType type = RaceType.WBRRace;
					if (x.wdcWrite.anyGt(wdc)) {
						type = RaceType.WDCRace;
						if (x.wcpWrite.anyGt(wcpUnionPO)) {
							type = RaceType.WCPRace;
							if (x.hbWrite.anyGt(hb)) {
								type = RaceType.HBRace;
							}
						} else {
							if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
						}
					} else {
						if (VERBOSE)
							Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));//, "VC x: " + x.wcpWrite.toString() + "| VC wcpUnionPO: " + wcpUnionPO.toString());
						if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
					}
					shortestRaceTid = index;
					shortestRaceIsWrite = true;
					shortestRaceType = type;
					// Keep event numbers of racing evenst in observed order even if we don't draw an edge, to avoid accidentally reordering them when they are outside the race window
					if (!DISABLE_EVENT_GRAPH) thisEventNode.eventNumber = Math.max(thisEventNode.eventNumber, x.lastWriteEvents[index].eventNode.eventNumber + 1);
					// Race edge, only for wr-rd races, and only when the racing write is the last writer of the read. Will be later drawn to a branch.
					if (!isWrite && x.lastWriteTid == shortestRaceTid) {
						Map<ShadowVar,CVE> delayed = ts_get_wbr_delayed(td);
						delayed.put(x, new CVE(x.wbrWrite, x.lastWriteEvents[shortestRaceTid].eventNode));
					}
				} else {
					if (VERBOSE) Assert.assertTrue(!x.wdcWrite.anyGt(wdc));
					if (VERBOSE) Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));//, "VC x: " + x.wcpWrite.toString() + "| VC wcpUnionPO: " + wcpUnionPO.toString());
					if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
				}
			}
		} else {
			if (VERBOSE) Assert.assertTrue(!x.wdcWrite.anyGt(wdc));
			if (VERBOSE) Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));//, "VC x: " + x.wcpWrite.toString() + "| VC wcpUnionPO: " + wcpUnionPO.toString());
			if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
		}
		// Next check for races with prior reads
		if (isWrite) {
			if (x.wbrRead.anyGt(wbr)) {
				int index = -1;
				while ((index = x.wbrRead.nextGt(wbr, index + 1)) != -1) {
					if (findCommonLock(x.heldLocksRead.get(index), heldLocks) == null) {
						RaceType type = RaceType.WBRRace;
						if (x.wdcRead.get(index) > wdc.get(index)) {
							type = RaceType.WDCRace;
							if (x.wcpRead.get(index) > wcpUnionPO.get(index)) {
								type = RaceType.WCPRace;
								if (x.hbRead.get(index) > hb.get(index)) {
									type = RaceType.HBRace;
								}
							} else {
								if (VERBOSE) Assert.assertTrue(x.hbRead.get(index) <= hb.get(index));
							}
						} else {
							if (VERBOSE) Assert.assertTrue(x.wcpRead.get(index) <= wcpUnionPO.get(index));
						}
						// Keep event numbers of racing evenst in observed order even if we don't draw an edge, to avoid accidentally reordering them when they are outside the race window
						if (!DISABLE_EVENT_GRAPH) thisEventNode.eventNumber = Math.max(thisEventNode.eventNumber, x.lastReadEvents[index].eventNode.eventNumber + 1);
						//Update the latest race with the current race since we only want to report one race per access event
						DynamicSourceLocation dl = shortestRaceTid >= 0 ? (shortestRaceIsWrite ? x.lastWriteEvents[shortestRaceTid] : x.lastReadEvents[shortestRaceTid]) : null;
						if (DISABLE_EVENT_GRAPH || (dl == null || x.lastReadEvents[index].eventNode.eventNumber > dl.eventNode.eventNumber)) {
							shortestRaceTid = index;
							shortestRaceIsWrite = false;
							shortestRaceType = type;
						}
						if (VERBOSE)
							Assert.assertTrue(x.lastReadEvents[index] != null);//, "lastReadEvents x: " + x.lastReadEvents.toString() + ", index " + index + " is null");
						if (!DISABLE_EVENT_GRAPH && VERBOSE)
							Assert.assertTrue(x.lastReadEvents[index].eventNode != null);//, "lastReadEvents x: " + x.lastReadEvents.toString() + ", index " + index + " containing dl: " + x.lastReadEvents[index].toString() + " has null event node.");
						if (!DISABLE_EVENT_GRAPH) {
							// This thread's last reader node might be same as the last writer node, due to merging
							//TODO: Tomcat fails if x.lastWriteEvents not checked for null. This would happen if only reads came before the first write?
							if (x.lastWriteEvents[index] != null && x.lastReadEvents[index].eventNode == x.lastWriteEvents[index].eventNode) {
								if (VERBOSE)
									Assert.assertTrue(EventNode.edgeExists(x.lastWriteEvents[index].eventNode, thisEventNode));
							} else {
								// We don't draw race edges for rd-wr races
//								EventNode.addEdge(x.lastReadEvents[index].eventNode, thisEventNode);
							}
						}
					} else {
						if (VERBOSE) Assert.assertTrue(!x.wdcRead.anyGt(wdc));
						if (VERBOSE) Assert.assertTrue(!x.wcpRead.anyGt(wcpUnionPO));//, "VC x: " + x.wcpRead.toString() + "| VC wcpUnionPO: " + wcpUnionPO.toString());
						if (VERBOSE) Assert.assertTrue(!x.hbRead.anyGt(hb));
					}
				}
			} else {
				if (VERBOSE) Assert.assertTrue(!x.wdcRead.anyGt(wdc));
				if (VERBOSE) Assert.assertTrue(!x.wcpRead.anyGt(wcpUnionPO));//, "VC x: " + x.wcpRead.toString() + "| VC wcpUnionPO: " + wcpUnionPO.toString());
				if (VERBOSE) Assert.assertTrue(!x.hbRead.anyGt(hb));
			}
		}
		return recordRace(x, ae, tid, shortestRaceTid, shortestRaceIsWrite, shortestRaceType, thisEventNode);
	}
	
	boolean checkForRacesLSHE(boolean isWrite, WDCGuardState x, AccessEvent ae, ShadowThread td, CV hb, CV wcp, CV wdc, CV wbr, CV lshe, RdWrNode thisEventNode) {
		final CV wcpUnionPO = new CV(wcp);
		int tid = td.getTid();
		wcpUnionPO.set(tid, hb.get(tid));
		int shortestRaceTid = -1;
		boolean shortestRaceIsWrite = false; // only valid if shortestRaceTid != -1
		RaceType shortestRaceType = RaceType.WBROrdered; // only valid if shortestRaceTid != -1
		Collection<ShadowLock> heldLocks = td.getLocksHeld();

		// First check for race with prior write
		if (x.lsheWrite.anyGt(lshe)) {
			int index = -1;
			while ((index = x.lsheWrite.nextGt(lshe, index + 1)) != -1) {
				if (x.heldLocksWrite.get(index) == null) return false;
				if (findCommonLock(x.heldLocksWrite.get(index), heldLocks) == null) {
					RaceType type = RaceType.LSHERace;
					if (x.wbrWrite.anyGt(wbr)) {
						type = RaceType.WBRRace;
						if (x.wdcWrite.anyGt(wdc)) {
							type = RaceType.WDCRace;
							if (x.wcpWrite.anyGt(wcpUnionPO)) {
								type = RaceType.WCPRace;
								if (x.hbWrite.anyGt(hb)) {
									type = RaceType.HBRace;
								}
							} else {
								if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
							}
						} else {
							if (VERBOSE) Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));
							if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
						}
					} else {
						if (VERBOSE) Assert.assertTrue(!x.wdcWrite.anyGt(wdc));
						if (VERBOSE) Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));
						if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
					}
					shortestRaceTid = index;
					shortestRaceIsWrite = true;
					shortestRaceType = type;
				} else {
					if (VERBOSE) Assert.assertTrue(!x.wbrWrite.anyGt(wbr));
					if (VERBOSE) Assert.assertTrue(!x.wdcWrite.anyGt(wdc));
					if (VERBOSE) Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));
					if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
				}
			}
		} else {
			if (VERBOSE) Assert.assertTrue(!x.wbrWrite.anyGt(wbr));
			if (VERBOSE) Assert.assertTrue(!x.wdcWrite.anyGt(wdc));
			if (VERBOSE) Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));
			if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
		}
		// Next check for races with prior reads
		if (isWrite) {
			if (x.lsheRead.anyGt(lshe)) {
				int index = -1;
				while ((index = x.lsheRead.nextGt(lshe, index + 1)) != -1) {
					if (findCommonLock(x.heldLocksRead.get(index), heldLocks) == null) {
						RaceType type = RaceType.LSHERace;
						if (x.wbrRead.get(index) > wbr.get(index)) {
							type = RaceType.WBRRace;
							if (x.wdcRead.get(index) > wdc.get(index)) {
								type = RaceType.WDCRace;
								if (x.wcpRead.get(index) > wcpUnionPO.get(index)) {
									type = RaceType.WCPRace;
									if (x.hbRead.get(index) > hb.get(index)) {
										type = RaceType.HBRace;
									}
								} else {
									if (VERBOSE) Assert.assertTrue(x.hbRead.get(index) <= hb.get(index));
								}
							} else {
								if (VERBOSE) Assert.assertTrue(x.wcpRead.get(index) <= wcpUnionPO.get(index));
							}
						} else {
							if (VERBOSE) Assert.assertTrue(x.wdcRead.get(index) <= wdc.get(index));
						}
						//Update the latest race with the current race since we only want to report one race per access event
						DynamicSourceLocation dl = shortestRaceTid >= 0 ? (shortestRaceIsWrite ? x.lastWriteEvents[shortestRaceTid] : x.lastReadEvents[shortestRaceTid]) : null;
						if (DISABLE_EVENT_GRAPH || (dl == null || x.lastReadEvents[index].eventNode.eventNumber > dl.eventNode.eventNumber)) {
							shortestRaceTid = index;
							shortestRaceIsWrite = false;
							shortestRaceType = type;
						}
						if (VERBOSE)
							Assert.assertTrue(x.lastReadEvents[index] != null);//, "lastReadEvents x: " + x.lastReadEvents.toString() + ", index " + index + " is null");
						if (!DISABLE_EVENT_GRAPH && VERBOSE)
							Assert.assertTrue(x.lastReadEvents[index].eventNode != null);//, "lastReadEvents x: " + x.lastReadEvents.toString() + ", index " + index + " containing dl: " + x.lastReadEvents[index].toString() + " has null event node.");
						if (!DISABLE_EVENT_GRAPH) {
							// This thread's last reader node might be same as the last writer node, due to merging
							//TODO: Tomcat fails if x.lastWriteEvents not checked for null. This would happen if only reads came before the first write?
							if (x.lastWriteEvents[index] != null && x.lastReadEvents[index].eventNode == x.lastWriteEvents[index].eventNode) {
								if (VERBOSE)
									Assert.assertTrue(EventNode.edgeExists(x.lastWriteEvents[index].eventNode, thisEventNode));
							}
						}
					} else {
						if (VERBOSE) Assert.assertTrue(!x.wbrRead.anyGt(wbr));
						if (VERBOSE) Assert.assertTrue(!x.wdcRead.anyGt(wdc));
						if (VERBOSE) Assert.assertTrue(!x.wcpRead.anyGt(wcpUnionPO));//, "VC x: " + x.wcpRead.toString() + "| VC wcpUnionPO: " + wcpUnionPO.toString());
						if (VERBOSE) Assert.assertTrue(!x.hbRead.anyGt(hb));
					}
				}
			}  else {
				if (VERBOSE) Assert.assertTrue(!x.wbrRead.anyGt(wbr));
				if (VERBOSE) Assert.assertTrue(!x.wdcRead.anyGt(wdc));
				if (VERBOSE) Assert.assertTrue(!x.wcpRead.anyGt(wcpUnionPO));//, "VC x: " + x.wcpRead.toString() + "| VC wcpUnionPO: " + wcpUnionPO.toString());
				if (VERBOSE) Assert.assertTrue(!x.hbRead.anyGt(hb));
			}
		}
		return recordRace(x, ae, tid, shortestRaceTid, shortestRaceIsWrite, shortestRaceType, thisEventNode);
	}
	
	@Override
	public void volatileAccess(final VolatileAccessEvent fae) {
		final ShadowThread td = fae.getThread();
		synchronized(td) {
			//final ShadowVar orig = fae.getOriginalShadow();
			
			if (COUNT_EVENT) total.inc(td.getTid());
			if (COUNT_EVENT) {
				if (fae.isWrite()) {
					volatile_write.inc(td.getTid());
				} else {
					volatile_read.inc(td.getTid());
				}
			}

			WDCVolatileData vd = get(fae.getShadowVolatile());
			VolatileRdWrNode thisEventNode = null;
			if (!DISABLE_EVENT_GRAPH) { //Volatiles are treated the same as non-volatile rd/wr accesses since each release does not have a corresponding acquire
				AcqRelNode currentCriticalSection = getCurrentCriticalSection(td);
				thisEventNode = new VolatileRdWrNode(-2, -1, fae.isWrite(), vd, fae.getThread().getTid(), currentCriticalSection);
			}
			handleEvent(fae, thisEventNode);
			if (VERBOSE && !DISABLE_EVENT_GRAPH) Assert.assertTrue(thisEventNode.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));
			
			if (PRINT_EVENT) {
				String fieldName = fae.getInfo().getField().getName();
				if (fae.isWrite()) {
					Util.log("volatile wr("+ fieldName +") by T"+td.getTid()+(!DISABLE_EVENT_GRAPH ? ", event count:"+thisEventNode.eventNumber : ""));
				} else {
					Util.log("volatile rd("+ fieldName +") by T"+td.getTid()+(!DISABLE_EVENT_GRAPH ? ", event count:"+thisEventNode.eventNumber : ""));
				}
			}

			synchronized(vd) {
				int tid = td.getTid();
				
				if (HB) {
					final CV hb = ts_get_hb(td);
					if (fae.isWrite()) {
						hb.max(vd.hbWrite);
						hb.max(vd.hbRead);
						vd.hbWrite.max(hb);
					} else { // isRead
						hb.max(vd.hbWrite);
					}
				}
				if (WCP || HB_WCP_DC || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
					final CV hb = ts_get_hb(td);
					final CV wcp = ts_get_wcp(td);
					if (fae.isWrite()) {
						hb.max(vd.hbWrite);
						hb.max(vd.hbRead);
						vd.hbWrite.max(hb);

						wcp.max(vd.wcpWrite);
						wcp.max(vd.wcpRead);
						vd.wcpWrite.max(wcp);
					} else { // isRead
						hb.max(vd.hbWrite);
						vd.hbRead.max(hb);

						wcp.max(vd.wcpWrite);
						vd.wcpRead.max(wcp);
					}
				}
				if (DC || HB_WCP_DC || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
					final CV wdc = ts_get_wdc(td);
					if (fae.isWrite()) {
						wdc.max(vd.wdcWrite);
						wdc.max(vd.wdcRead);
						vd.wdcWrite.max(wdc);
					} else { // isRead
						wdc.max(vd.wdcWrite);
						vd.wdcRead.max(wdc);
					}
				}
				if (WBR || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
					final CV wbr = ts_get_wbr(td);
					if (fae.isWrite()) {
						vd.wbr.max(wbr);
						vd.wbr.setEventNode(thisEventNode);
						vd.wbrWrites.set(tid, wbr.get(tid));
						wbr.inc(tid);
					} else { /* is read */
						ts_set_hasread(td, true); // Mark that we have seen a read, next branch can't be a fast path
						if (vd.wbr.anyGt(wbr)) { // Only draw an edge if it is not redundant
							ts_get_wbr_delayed(td).put(fae.getShadow(), new CVE(vd.wbr, vd.wbr.eventNode));
						}
						if (!DISABLE_EVENT_GRAPH) {
							if (vd.hasLastWriter()) {
								VolatileRdWrNode lastWriter = vd.lastWriteEvents[vd.lastWriter];
								thisEventNode.setLastWriter(lastWriter);
							} else {
								thisEventNode.setHasNoLastWriter();
							}
						}
					}
				}

				// Volatile accesses can have outgoing edges, kill FP and increment to distinguish following events
				ts_set_eTd(td, ts_get_hb(td).get(td.getTid()));
				if (HB || WCP || HB_WCP_DC || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE)
					ts_get_hb(td).inc(td.getTid());
				if (WCP || HB_WCP_DC || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE)
					ts_get_wcp(td).inc(td.getTid());
				if (DC || HB_WCP_DC || WCP_DC_WBR || WCP_DC_WBR_LSHE)
					ts_get_wdc(td).inc(td.getTid());
				if (WBR || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE)
					ts_get_wbr(td).inc(td.getTid());
				
				if (fae.isWrite()) {
					vd.lastWriteEvents[tid] = thisEventNode;
					vd.lastWriter = tid;
					
					// Handle race edges
					//ts_set_eTd(td, ts_get_hb(td).get(td.getTid()));
				}

				if (!DISABLE_EVENT_GRAPH) {
					thisEventNode.eventNumber = Math.max(thisEventNode.eventNumber, vd.lastAccessEventNumber + 1);
					vd.lastAccessEventNumber = thisEventNode.eventNumber;
				}
			}
		}
			
		super.volatileAccess(fae);
	}

	@Override
	public void branch(BranchEvent be) {
		handleBranch(be, NO_SDG ? "" : be.getInfo().getLoc().getSourceLoc());
		super.branch(be);
	}

	public void branch(ArrayAccessEvent aae) {
		handleBranch(aae, NO_SDG ? "" : aae.getInfo().getLoc().getSourceLoc());
	}

	private void handleBranch(Event ev, String branchSourceLoc) {
		if (! (WBR || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE)) return; // Non-WBR configs don't use branch
		ShadowThread td = ev.getThread();
		boolean hasRead = ts_get_hasread(td);
		if (!hasRead) { // Branch fast path: no reads to process
			branchFP.inc(td.getTid());
			return;
		}

		EventNode thisEventNode = null;
		if (!DISABLE_EVENT_GRAPH) { //Volatiles are treated the same as non-volatile rd/wr accesses since each release does not have a corresponding acquire
			AcqRelNode currentCriticalSection = getCurrentCriticalSection(td);
			thisEventNode = new BranchNode(-2, -1, td.getTid(), currentCriticalSection);
		}
		handleEvent(ev, thisEventNode);
		if (VERBOSE && !DISABLE_EVENT_GRAPH) Assert.assertTrue(thisEventNode.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));
		if (PRINT_EVENT) {
			Util.log("branch by T"+td.getTid()+(!DISABLE_EVENT_GRAPH ? ", event count:"+thisEventNode.eventNumber : ""));
		}

		if (COUNT_EVENT) total.inc(td.getTid());
		if (COUNT_EVENT) branch.inc(td.getTid());
		if (COUNT_EVENT) example.inc(td.getTid());

		Map<ShadowVar,CVE> delayed = ts_get_wbr_delayed(td);
		CV wbr = ts_get_wbr(td);

		for (Map.Entry<ShadowVar, CVE> edge : delayed.entrySet()) {
			CVE delayedEdge = edge.getValue();
			if (delayedEdge != null && delayedEdge.anyGt(wbr) /* avoid redundant edges, important for event graph */) {
				wbr.max(delayedEdge);
				if (!DISABLE_EVENT_GRAPH) {
					EventNode.addEdge(delayedEdge.eventNode, thisEventNode); // release to branch
				}
			}
		}

		// After a branch, all delayed edges should have been processed
		ts_set_wbr_delayed(td, new HashMap<>());
		// Already processed all reads, the next branch can be a fast path unless we see another read
		ts_set_hasread(td, false);
	}



	private void error(final AccessEvent ae, final String relation, final String prevOp, final int prevTid, final DynamicSourceLocation prevDL, final String curOp, final int curTid) {


		try {		
			if (ae instanceof FieldAccessEvent) {
				FieldAccessEvent fae = (FieldAccessEvent)ae;
				final FieldInfo fd = fae.getInfo().getField();
				final ShadowThread currentThread = fae.getThread();
				final Object target = fae.getTarget();
				
				fieldErrors.error(currentThread,
						fd,
						"Relation",						relation,
						"Guard State", 					fae.getOriginalShadow(),
						"Current Thread",				toString(currentThread), 
						"Class",						target==null?fd.getOwner():target.getClass(),
						"Field",						Util.objectToIdentityString(target) + "." + fd, 
						"Prev Op",						prevOp + prevTid,
						"Prev Loc",						prevDL == null ? "?" : prevDL.toString(),
						"Prev Event #",					prevDL == null ? "?" : prevDL.eventNode,
						"Cur Op",						curOp + curTid,
						"Current Event #",				ts_get_lastEventNode(currentThread)==null?"null":ts_get_lastEventNode(currentThread).eventNumber,
						//"Case", 						"#" + errorCase,
						"Stack",						ShadowThread.stackDumpForErrorMessage(currentThread) 
				);
				if (!fieldErrors.stillLooking(fd)) {
					advance(ae);
					return;
				}
			} else {
				ArrayAccessEvent aae = (ArrayAccessEvent)ae;
				final ShadowThread currentThread = aae.getThread();
				final Object target = aae.getTarget();

				arrayErrors.error(currentThread,
						aae.getInfo(),
						"Relation",						relation,
						"Alloc Site", 					ArrayAllocSiteTracker.get(aae.getTarget()),
						"Guard State", 					aae.getOriginalShadow(),
						"Current Thread",				currentThread==null?"":toString(currentThread), 
						"Array",						Util.objectToIdentityString(target) + "[" + aae.getIndex() + "]",
						"Prev Op",						ShadowThread.get(prevTid)==null?"":prevOp + prevTid + ("name = " + ShadowThread.get(prevTid).getThread().getName()),
						"Prev Loc",						prevDL == null ? "?" : prevDL.toString(),
						"Prev Event #",					prevDL == null ? "?" : prevDL.eventNode,
						"Cur Op",						ShadowThread.get(curTid)==null?"":curOp + curTid + ("name = " + ShadowThread.get(curTid).getThread().getName()), 
						"Current Event #",				ts_get_lastEventNode(currentThread)==null?"null":ts_get_lastEventNode(currentThread).eventNumber,
						//"Case", 						"#" + errorCase,
						"Stack",						ShadowThread.stackDumpForErrorMessage(currentThread) 
				);
				//Raptor: added ShadowThread.get(prevTid[curTid])==null?"": to "Prev[Cur] Op" and "Current Thread" since null pointers where being thrown due to the fact 
				//that a shadow thread would appear empty for a prevOp.

				aae.getArrayState().specialize();

				if (!arrayErrors.stillLooking(aae.getInfo())) {
					advance(aae);
					return;
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
			
			

			EventNode thisEventNode = null;
			if (!DISABLE_EVENT_GRAPH) { //preStart is handled the same as rd/wr accesses
				AcqRelNode currentCriticalSection = getCurrentCriticalSection(td);
				thisEventNode = new EventNode(-2, -1,  td.getTid(), currentCriticalSection, "start");
			}
			handleEvent(se, thisEventNode);
			if (VERBOSE && !DISABLE_EVENT_GRAPH) Assert.assertTrue(thisEventNode.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));

			if (PRINT_EVENT) {
				Util.log("preStart by T"+td.getTid()+(!DISABLE_EVENT_GRAPH ? ", event count:"+thisEventNode.eventNumber : ""));
			}
			
			// The forked thread has not started yet, so there should be no need to lock
			final ShadowThread forked = se.getNewThread();
			int thisTid = td.getTid();
			int forkedTid = forked.getTid();

			// Handle race edges
			ts_set_eTd(td, ts_get_hb(td).get(td.getTid()));

			if (HB) {
				final CV hb = ts_get_hb(td);
				final CV forkedHB = ts_get_hb(forked);
				forkedHB.max(hb);
				hb.inc(thisTid);
			}
			if (WCP || HB_WCP_DC || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
				final CV hb = ts_get_hb(td);
				final CV forkedHB = ts_get_hb(forked);
				final CV wcp = ts_get_wcp(td);
				final CV forkedWCP = ts_get_wcp(forked);
				
				// Compute WCP before modifying HB
				forkedWCP.max(hb); // Use HB here because this is a hard WCP edge
				forkedHB.max(hb);
				hb.inc(thisTid);
			}
			if (DC || HB_WCP_DC || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
				final CV wdc = ts_get_wdc(td);
				final CV forkedWDC = ts_get_wdc(forked);
				forkedWDC.max(wdc);
				wdc.inc(thisTid);
			}
			if (WBR || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
				final CV wbr = ts_get_wbr(td);
				final CV forkedWBR = ts_get_wbr(forked);
				forkedWBR.max(wbr);
				wbr.inc(thisTid);
			}
			if (WCP_DC_WBR_LSHE) {
				final CV lshe = ts_get_lshe(td);
				final CV forkedLSHE = ts_get_lshe(forked);
				forkedLSHE.max(lshe);
				lshe.inc(thisTid);
			}
			
			if (!DISABLE_EVENT_GRAPH && (WBR || WCP_WBR || WCP_DC_WBR)) {
				ts_set_wbr(forked, new CVE(ts_get_wbr(forked), thisEventNode)); // for generating event node graph
			}
		}

		super.preStart(se);
	}

	@Override
	public void postJoin(final JoinEvent je) {
		final ShadowThread td = je.getThread();
		synchronized(td) {
			
			if (COUNT_EVENT) total.inc(td.getTid());
			if (COUNT_EVENT) join.inc(td.getTid());

			EventNode thisEventNode = null;
			if (!DISABLE_EVENT_GRAPH) { //postJoin is handled the same as rd/wr accesses
				AcqRelNode currentCriticalSection = getCurrentCriticalSection(td);
				thisEventNode = new EventNode(-2, -1, td.getTid(), currentCriticalSection, "join");
			}
			handleEvent(je, thisEventNode);
			if (VERBOSE && !DISABLE_EVENT_GRAPH) Assert.assertTrue(thisEventNode.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));

			//TODO: thread is already joined so there should be no need to lock
			final ShadowThread joining = je.getJoiningThread();

			// this test tells use whether the tid has been reused already or not.  Necessary
			// to still account for stopped thread, even if that thread's tid has been reused,
			// but good to know if this is happening alot...
			if (joining.getTid() == -1) {
				Yikes.yikes("Joined after tid got reused --- don't touch anything related to tid here!");
			}
			
			if (!DISABLE_EVENT_GRAPH) {
				EventNode priorNode = ts_get_lastEventNode(joining);
				EventNode.addEdge(priorNode, thisEventNode);
			}
			
			if (PRINT_EVENT) {
				Util.log("postJoin by T"+td.getTid()+(!DISABLE_EVENT_GRAPH ? ", event count:"+thisEventNode.eventNumber : ""));
			}
			
			//joiningHB.inc(joiningTid); // Mike says: I don't see why we'd every want to do this here; instead, the terminating (joining) thread should do it
			if (HB) {
				ts_get_hb(td).max(ts_get_hb(joining));
			}
			if (WCP || HB_WCP_DC || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
				ts_get_hb(td).max(ts_get_hb(joining));
				ts_get_wcp(td).max(ts_get_hb(joining)); // Use HB since this is a hard WCP edge
			}
			if (DC || HB_WCP_DC || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
				ts_get_wdc(td).max(ts_get_wdc(joining));
			}
			if (WBR || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
				ts_get_wbr(td).max(ts_get_wbr(joining));
			}
			if (WCP_DC_WBR_LSHE) {
				ts_get_lshe(td).max(ts_get_lshe(joining));
			}
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
			
			AcqRelNode thisEventNode = null;
			if (!DISABLE_EVENT_GRAPH) {
				AcqRelNode currentCriticalSection = getCurrentCriticalSection(td);
				thisEventNode = new AcqRelNode(-2, -1, we.getLock(), td.getTid(), false, currentCriticalSection);
				updateCurrentCriticalSectionAtRelease(td, thisEventNode);
			}
			handleEvent(we, thisEventNode);
			if (VERBOSE && !DISABLE_EVENT_GRAPH) Assert.assertTrue(thisEventNode.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));

			if (PRINT_EVENT) {
				Util.log("preWait by T"+td.getTid()+(!DISABLE_EVENT_GRAPH ? ", event count:"+thisEventNode.eventNumber : ""));
			}
			
			// lock is already held
			handleRelease(td, we.getLock(), thisEventNode, true);
		}

		// Mike says: isn't the following (original FastTrack code) doing an inc on the thread's clock before using it for max, which is wrong?
		// Or maybe it doesn't matter since this thread doesn't do any accesses between preWait and postWait.
		/*
		this.incEpochAndCV(we.getThread(), we);
		synchronized(lockData) {
			lockData.cv.max(ts_get_cv(we.getThread()));
		}
		*/
		
		super.preWait(we);
	}

	@Override
	public void postWait(WaitEvent we) {
		final ShadowThread td = we.getThread();
		synchronized (td) {
			
			if (COUNT_EVENT) total.inc(td.getTid());
			if (COUNT_EVENT) postWait.inc(td.getTid());
			
			AcqRelNode thisEventNode = null;
			if (!DISABLE_EVENT_GRAPH) {
				AcqRelNode currentCriticalSection = getCurrentCriticalSection(td);
				thisEventNode = new AcqRelNode(-2, -1, we.getLock(), td.getTid(), true, currentCriticalSection);
				updateCurrentCriticalSectionAtAcquire(td, thisEventNode);
			}
			handleEvent(we, thisEventNode);
			if (VERBOSE && !DISABLE_EVENT_GRAPH) Assert.assertTrue(thisEventNode.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));
			
			if (PRINT_EVENT) {
				Util.log("postWait by T"+td.getTid()+(!DISABLE_EVENT_GRAPH ? ", event count:"+thisEventNode.eventNumber : ""));
			}
			
			// lock is already held
			// Considering wait--notify to be a hard WCP and WDC edge.
			// (If wait--notify is used properly, won't it already be a hard edge?)
			handleAcquire(td, we.getLock(), thisEventNode, true);
		}

		super.postWait(we);
	}

	public static String toString(final ShadowThread td) {
		return String.format("[tid=%-2d   hb=%s   wcp=%s   wdc=%s]", td.getTid(), ((HB || WCP || HB_WCP_DC) ? ts_get_hb(td) : "N/A"), ((WCP || HB_WCP_DC) ? ts_get_wcp(td) : "N/A"), ((DC || HB_WCP_DC) ? ts_get_wdc(td) : "N/A"));
	}

	private final Decoration<ShadowThread, CV> cvForExit = 
		ShadowThread.makeDecoration("WDC:barrier", DecorationFactory.Type.MULTIPLE, new NullDefault<ShadowThread, CV>());

	public void preDoBarrier(BarrierEvent<WDCBarrierState> be) {
		Assert.assertTrue(false); // Does this ever get triggered in our evaluated programs?
		WDCBarrierState wdcBE = be.getBarrier();
		ShadowThread currentThread = be.getThread();
		CV entering = wdcBE.getEntering();
		entering.max(ts_get_hb(currentThread));
		cvForExit.set(currentThread, entering);
	}

	public void postDoBarrier(BarrierEvent<WDCBarrierState> be) {
		Assert.assertTrue(false); // Does this ever get triggered in our evaluated programs?
		WDCBarrierState wdcBE = be.getBarrier();
		ShadowThread currentThread = be.getThread();
		CV old = cvForExit.get(currentThread);
		wdcBE.reset(old);
		if (HB) {
			ts_get_hb(currentThread).max(old);
		}
		if (WCP || HB_WCP_DC || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
			ts_get_hb(currentThread).max(old);
			ts_get_wcp(currentThread).max(old); // Also update WCP since a barrier is basically an all-to-all hard WCP edge
		}
		if (DC || HB_WCP_DC || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
			ts_get_wdc(currentThread).max(old); // Updating WDC to HB seems fine at a barrier (?)
		}
		if (WBR || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
			ts_get_wbr(currentThread).max(old);
		}
		if (WCP_DC_WBR_LSHE) {
			ts_get_lshe(currentThread).max(old);
		}
	}

	@Override
	public void classInitialized(ClassInitializedEvent e) {
		final ShadowThread td = e.getThread();
		synchronized (td) {
			
			if (COUNT_EVENT) total.inc(td.getTid());
			if (COUNT_EVENT) class_init.inc(td.getTid());
			
			EventNode thisEventNode = null;
			if (!DISABLE_EVENT_GRAPH) { //classInitialized is handled the same as rd/wr accesses
				AcqRelNode currentCriticalSection = getCurrentCriticalSection(td);
				thisEventNode = new EventNode(-2, -1, td.getTid(), currentCriticalSection, "initialize");
			}
			handleEvent(e, thisEventNode);
			if (VERBOSE && !DISABLE_EVENT_GRAPH) Assert.assertTrue(thisEventNode.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));
			
			if (PRINT_EVENT) {
				Util.log("classInitialized by T"+td.getTid()+(!DISABLE_EVENT_GRAPH ? ", event count:"+thisEventNode.eventNumber : ""));
			}
			
			int tid = td.getTid();
			synchronized(classInitTime) { //Not sure what we discussed for classInit, but FT synchronizes on it so I assume the program executing does not protect accesses to classInit.
				ts_set_eTd(td, ts_get_hb(td).get(td.getTid()));
				if (HB) {
					final CV hb = ts_get_hb(td);
					classInitTime.get(e.getRRClass()).hbWrite.max(hb);
					hb.inc(tid);
				}
				if (WCP || HB_WCP_DC || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
					final CV hb = ts_get_hb(td);
					final CV wcp = ts_get_wcp(td);
					classInitTime.get(e.getRRClass()).hbWrite.max(hb);
					classInitTime.get(e.getRRClass()).wcpWrite.max(wcp);
					hb.inc(tid);
				}
				if (DC || HB_WCP_DC || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
					final CV wdc = ts_get_wdc(td);
					classInitTime.get(e.getRRClass()).wdcWrite.max(wdc);
					wdc.inc(tid);
				}
				if (WBR || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
					final CV wbr = ts_get_wbr(td);
					classInitTime.get(e.getRRClass()).wbr.max(wbr);
					wbr.inc(tid);
				}
				if (WCP_DC_WBR_LSHE) {
					final CV lshe = ts_get_lshe(td);
					classInitTime.get(e.getRRClass()).lshe.max(lshe);
					lshe.inc(tid);
				}
				if (!DISABLE_EVENT_GRAPH && (WBR || WCP_WBR || WCP_DC_WBR)) {
					classInitTime.get(e.getRRClass()).wbr.setEventNode(thisEventNode);
				}
			}
		}

		super.classInitialized(e);
	}
	
	@Override
	public void classAccessed(ClassAccessedEvent e) {
		final ShadowThread td = e.getThread();
		synchronized(td) {
			
			if (COUNT_EVENT) total.inc(td.getTid());
			if (COUNT_EVENT) class_accessed.inc(td.getTid());
			
			EventNode thisEventNode = null;
			if (!DISABLE_EVENT_GRAPH) { //classInitialized is handled the same as rd/wr accesses
				AcqRelNode currentCriticalSection = getCurrentCriticalSection(td);
				thisEventNode = new EventNode(-2, -1, td.getTid(), currentCriticalSection, "class_accessed");
			}
			handleEvent(e, thisEventNode);
			if (VERBOSE && !DISABLE_EVENT_GRAPH) Assert.assertTrue(thisEventNode.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));
			
			if (PRINT_EVENT) {
				Util.log("classAccessed by T"+td.getTid()+(!DISABLE_EVENT_GRAPH ? ", event count:"+thisEventNode.eventNumber : ""));
			}
			
			synchronized(classInitTime) { //Not sure what we discussed for classInit, but FT synchronizes on it so I assume the program executing does not protect accesses to classInit.
				WDCVolatileData initTime = classInitTime.get(e.getRRClass());
				if (HB) {
					final CV hb = ts_get_hb(td);
					hb.max(initTime.hbWrite);
				}
				if (WCP || HB_WCP_DC || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
					final CV hb = ts_get_hb(td);
					final CV wcp = ts_get_wcp(td);
					hb.max(initTime.hbWrite);
					wcp.max(initTime.hbWrite); // union with HB since this is effectively a hard WCP edge
				}
				if (DC || HB_WCP_DC || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
					final CV wdc = ts_get_wdc(td);
					wdc.max(initTime.wdcWrite);
				}
				if (WBR || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE) {
					final CV wbr = ts_get_wbr(td);
					wbr.max(initTime.wbr);
				}
				if (WCP_DC_WBR_LSHE) {
					final CV lshe = ts_get_lshe(td);
					lshe.max(initTime.lshe);
				}
					
				if (!DISABLE_EVENT_GRAPH) {
					if (WBR || WCP_WBR || WCP_DC_WBR) {
						if (initTime.wbr.anyGt(ts_get_wbr(td))) {
							EventNode.addEdge(initTime.wbr.eventNode, thisEventNode);
						}
					}
				}
			}
		}
	}

	@Override
	public void printXML(XMLWriter xml) {
		for (ShadowThread td : ShadowThread.getThreads()) {
			xml.print("thread", toString(td));
		}
	}

	public File storeReorderedTraces() {
		File commandDir = null;
		if (RR.wdcbPrintReordering.get()) {
			File traceDir = new File("WDC_Traces");
			if (!traceDir.exists()) {
				traceDir.mkdir();
			}
			int version = 1;
			String commandDirName = traceDir + "/" + CommandLine.javaArgs.get().trim();
			commandDir = new File(commandDirName + "_" + version);
			while (commandDir.exists()) {
				version++;
				commandDir = new File(commandDirName + "_" + version);
			}
			commandDir.mkdir();
		}
		return commandDir;
	}
}
