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
import acme.util.collections.Pair;
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
import rr.meta.*;
import rr.org.objectweb.asm.Opcodes;
import rr.state.ShadowLock;
import rr.state.ShadowThread;
import rr.state.ShadowVar;
import rr.state.ShadowVolatile;
import rr.tool.RR;
import rr.tool.Tool;
import tools.wdc.sourceinfo.SDG;
import tools.wdc.event.*;
import tools.wdc.event.AcqRelNode;
import tools.wdc.event.EventNode;
import tools.wdc.event.RdWrDebugNode;
import tools.wdc.event.RdWrNode;
import tools.wdc.sourceinfo.SDGI;

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
	public static final ThreadLocalCounter lookup_sdg = new ThreadLocalCounter("DC", "SDG Lookups Performed", RR.maxTidOption.get());
	public static final ThreadLocalCounter branch_missing_sdg = new ThreadLocalCounter("DC", "Branch Missing from SDG", RR.maxTidOption.get());
	public static final ThreadLocalCounter read_missing_sdg = new ThreadLocalCounter("DC", "Read Missing from SDG", RR.maxTidOption.get());
	public final ErrorMessage<FieldInfo> fieldErrors = ErrorMessages.makeFieldErrorMessage("WDC");
	public final ErrorMessage<ArrayAccessInfo> arrayErrors = ErrorMessages.makeArrayErrorMessage("WDC");

	public static final boolean HB = RR.dcHBOption.get();
	public static final boolean WCP = RR.dcWCPOption.get();
	public static final boolean NWC = RR.dcNWCOption.get();
	public static final boolean WCP_NWC = RR.dcWCP_NWCOption.get();
	public static final boolean DC = RR.dcDCOption.get();
	public static final boolean WCP_DC = RR.dcWCP_DCOption.get();
	public static final boolean NWC_DC = RR.dcNWC_DCOption.get();
	public static final boolean uDP = RR.dcuDPOption.get();
	public static final boolean WCP_uDP = RR.dcWCP_uDPOption.get();
	public static final boolean WBR = RR.dcWBROption.get();
	public static final boolean WCP_WBR = RR.dcWCP_WBROption.get();
	public static final boolean NWC_WBR = RR.dcNWC_WBROption.get();
	public static final boolean DC_WBR = false;
	public static final boolean WCP_DC_WBR = RR.dcWCP_DC_WBROption.get() || RR.dcDC_WBROption.get();
	public static final boolean WCP_NWC_DC_WBR = RR.dcWCP_NWC_DC_WBROption.get();
	public static final boolean WCP_NWC_WBR = RR.dcWCP_NWC_WBROption.get();
	public static final boolean WCP_DC_uDP_WBR = RR.dcWCP_DC_uDP_WBROption.get();
	public static final boolean WCP_DC_WBR_LSHE = RR.dcWCP_DC_WBR_LSHEOption.get();

	public static final boolean hasHB = HB || WCP || WCP_DC || WCP_WBR || WCP_uDP || WCP_DC_WBR || WCP_DC_uDP_WBR || WCP_DC_WBR_LSHE || NWC || WCP_NWC || NWC_WBR || NWC_DC || WCP_NWC_DC_WBR || WCP_NWC_WBR;
	public static final boolean hasWCP = WCP || WCP_DC || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE || WCP_uDP || WCP_DC_uDP_WBR || WCP_NWC || WCP_NWC_DC_WBR || WCP_NWC_WBR;
	public static final boolean hasNWC = NWC || WCP_NWC || NWC_WBR || NWC_DC || WCP_NWC_DC_WBR || WCP_NWC_WBR;
	public static final boolean hasDC = DC || WCP_DC || WCP_DC_WBR || WCP_DC_WBR_LSHE || WCP_DC_uDP_WBR || NWC_DC || WCP_NWC_DC_WBR || DC_WBR;
	public static final boolean hasUDP = uDP || WCP_uDP || WCP_DC_uDP_WBR;
	public static final boolean hasWBR = WBR || WCP_WBR || WCP_DC_WBR || WCP_DC_WBR_LSHE || WCP_DC_uDP_WBR || NWC_WBR || WCP_NWC_DC_WBR || DC_WBR || WCP_NWC_WBR;
	public static final boolean hasLSHE = WCP_DC_WBR_LSHE;

	public static final boolean graphHasBranches = hasWBR;
	public static final boolean CAPTURE_SHORTEST_RACE = !RR.dontCaptureShortestRace.get();

	private static boolean running = true;
	public static boolean isRunning() {
		return running;
	}

	public static final boolean NO_SDG;

	private static final SDGI sdg;
	static {
		String sdgClassName = RR.brSDGClassName.get();

		NO_SDG = sdgClassName == null;

		if (NO_SDG) {
			Util.println("NOT using SDG information");
			sdg = null;
		} else {
			Util.println("using SDG information");
			sdg = new SDG(sdgClassName, RR.brProjectPath.get());
		}
	}
	
	private static final boolean DISABLE_EVENT_GRAPH = !RR.wdcBuildEventGraph.get();
	private static final boolean PRINT_EVENT = RR.printEventOption.get();
	private static final boolean COUNT_EVENT = RR.countEventOption.get();
	
	private static final boolean VERBOSE = RR.dcVerbose.get();
	
	// Can use the same data for class initialization synchronization as for volatiles
	public static final Decoration<ClassInfo,WDCVolatileData> classInitTime = MetaDataInfoMaps.getClasses().makeDecoration("WDC:InitTime", Type.MULTIPLE,
			(DefaultValue<ClassInfo, WDCVolatileData>) t -> new WDCVolatileData(null));

	public WDCTool(final String name, final Tool next, CommandLine commandLine) {
		super(name, next, commandLine);
		new BarrierMonitor<>(this, (DefaultValue<Object, WDCBarrierState>) k -> new WDCBarrierState(ShadowLock.get(k)));
	}

	private static int ts_get_lamport(ShadowThread ts) { Assert.panic("Bad");	return -1; }
	private static void ts_set_lamport(ShadowThread ts, int c) { Assert.panic("Bad");  }

	private static CV ts_get_hb(ShadowThread ts) { Assert.panic("Bad");	return null; }
	private static void ts_set_hb(ShadowThread ts, CV cv) { Assert.panic("Bad");  }

	private static CV ts_get_wcp(ShadowThread ts) { Assert.panic("Bad");	return null; }
	private static void ts_set_wcp(ShadowThread ts, CV cv) { Assert.panic("Bad");  }

	private static CV ts_get_nwc(ShadowThread ts) { Assert.panic("Bad");	return null; }
	private static void ts_set_nwc(ShadowThread ts, CV cv) { Assert.panic("Bad");  }

	private static CV ts_get_wdc(ShadowThread ts) { Assert.panic("Bad");	return null; }
	private static void ts_set_wdc(ShadowThread ts, CV cv) { Assert.panic("Bad");  }

	private static CV ts_get_udp(ShadowThread ts) { Assert.panic("Bad");	return null; }
	private static void ts_set_udp(ShadowThread ts, CV cv) { Assert.panic("Bad");  }

	private static CV ts_get_wbr(ShadowThread ts) { Assert.panic("Bad");	return null; }
	private static void ts_set_wbr(ShadowThread ts, CV cv) { Assert.panic("Bad");  }

	private static Map<ShadowVar,CVE> ts_get_wbr_delayed(ShadowThread ts) { Assert.panic("Bad"); return null; }
	private static void ts_set_wbr_delayed(ShadowThread ts, Map<ShadowVar,CV> map) { Assert.panic("Bad"); }

	private static Map<ShadowVar, CV> ts_get_nwc_delayed(ShadowThread ts) { Assert.panic("Bad"); return null; }
	private static void ts_set_nwc_delayed(ShadowThread ts, Map<ShadowVar, CV> map) { Assert.panic("Bad"); }

	private static boolean ts_get_hasread(ShadowThread ts) { Assert.panic("Bad"); return true; }
	private static void ts_set_hasread(ShadowThread ts, boolean reads) { Assert.panic("Bad");  }
	
	private static CV ts_get_lshe(ShadowThread ts) { Assert.panic("Bad");	return null; }
	private static void ts_set_lshe(ShadowThread ts, CV cv) { Assert.panic("Bad"); }

	// We only maintain the "last event" if BUILD_EVENT_GRAPH == true
	private static EventNode ts_get_lastEventNode(ShadowThread ts) { Assert.panic("Bad"); return null; }
	private static void ts_set_lastEventNode(ShadowThread ts, EventNode eventNode) { Assert.panic("Bad"); }
	
	// Maintain the a stack of current held critical sections per thread
	private static Stack<AcqRelNode> ts_get_holdingLocks(ShadowThread ts) { Assert.panic("Bad"); return null; }
	private static void ts_set_holdingLocks(ShadowThread ts, Stack<AcqRelNode> heldLocks) { Assert.panic("Bad"); }
	
	// Handle FastPaths and race edges
	private static int/*epoch*/ ts_get_eTd(ShadowThread ts) { Assert.panic("Bad");	return -1; }
	private static void ts_set_eTd(ShadowThread ts, int/*epoch*/ e) { Assert.panic("Bad");  }

	private static final Decoration<ShadowLock,WDCLockData> wdcLockData = ShadowLock.makeDecoration("WDC:ShadowLock", DecorationFactory.Type.MULTIPLE,
			new DefaultValue<ShadowLock,WDCLockData>() { public WDCLockData get(final ShadowLock ld) { return new WDCLockData(ld); }});

	private static WDCLockData get(final ShadowLock ld) {
		return wdcLockData.get(ld);
	}

	private static final Decoration<ShadowVolatile,WDCVolatileData> wdcVolatileData = ShadowVolatile.makeDecoration("WDC:shadowVolatile", DecorationFactory.Type.MULTIPLE,
			new DefaultValue<ShadowVolatile,WDCVolatileData>() { public WDCVolatileData get(final ShadowVolatile ld) { return new WDCVolatileData(ld); }});

	private static WDCVolatileData get(final ShadowVolatile ld) {
		return wdcVolatileData.get(ld);
	}

	@Override
	final public ShadowVar makeShadowVar(final AccessEvent fae) {
		if (fae.getKind() == Kind.VOLATILE) {
			WDCVolatileData vd = get(((VolatileAccessEvent)fae).getShadowVolatile());
			return super.makeShadowVar(fae);
		} else {
			return new WDCGuardState();
		}
	}

	@Override
	public void create(NewThreadEvent e) {
		ShadowThread currentThread = e.getThread();
		synchronized(currentThread) { //TODO: Is this over kill?
			if (hasHB) {
				CV hb = ts_get_hb(currentThread);
				if (hb == null) {
					hb = new CV(INIT_CV_SIZE);
					ts_set_hb(currentThread, hb);
					hb.inc(currentThread.getTid());
				}
			}
			if (hasWCP) {
				CV wcp = ts_get_wcp(currentThread);
				if (wcp == null) {
					wcp = new CV(INIT_CV_SIZE);
					ts_set_wcp(currentThread, wcp);
				}
			}
			if (hasNWC) {
				CV nwc = ts_get_nwc(currentThread);
				if (nwc == null) {
					nwc = new CV(INIT_CV_SIZE);
					ts_set_nwc(currentThread, nwc);
					ts_set_nwc_delayed(currentThread, new HashMap<>());
				}
			}
			if (hasDC) {
				CV wdc = ts_get_wdc(currentThread);
				if (wdc == null) {
					wdc = new CV(INIT_CV_SIZE);
					ts_set_wdc(currentThread, wdc);
					wdc.inc(currentThread.getTid());
				}
			}
			if (hasWBR) {
				CV wbr = ts_get_wbr(currentThread);
				if (wbr == null) {
					wbr = new CV(INIT_CV_SIZE);
					ts_set_wbr(currentThread, wbr);
					wbr.inc(currentThread.getTid());
					ts_set_wbr_delayed(currentThread, new HashMap<>());
					ts_set_hasread(currentThread, false);
				}
			}
			if (hasLSHE) {
				CV lshe = ts_get_lshe(currentThread);
				if (lshe == null) {
					lshe = new CV(INIT_CV_SIZE);
					ts_set_lshe(currentThread, lshe);
					lshe.inc(currentThread.getTid());
				}
			}
			if (hasUDP) {
				CV udp = ts_get_udp(currentThread);
				if (udp == null) {
					udp = new CV(INIT_CV_SIZE);
					ts_set_udp(currentThread, udp);
					udp.inc(currentThread.getTid());
				}
			}
			if (CAPTURE_SHORTEST_RACE) ts_set_lamport(currentThread, 0);
			// Handle race edges
			killFastPath(currentThread);
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
						thisEventNode = new EventNode(-2, td.getTid(), currentCriticalSection, "", "join [exit join]");
						handleEvent(me, thisEventNode);
						if (VERBOSE && !DISABLE_EVENT_GRAPH) Assert.assertTrue(thisEventNode.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));
						//Build dummy eventNode
						if (COUNT_EVENT) total.inc(td.getTid()); //TODO: should dummy event increment the event count?
						currentCriticalSection = getCurrentCriticalSection(main);
						dummyEventNode = new EventNode(ts_get_lastEventNode(main).eventNumber+1, main.getTid(), currentCriticalSection, "", "exit [dummy event]");
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
					if (hasHB) ts_get_hb(main).max(ts_get_hb(td));
					if (hasWCP) ts_get_wcp(main).max(ts_get_hb(td));
					if (hasNWC) ts_get_nwc(main).max(ts_get_nwc(td));
					if (hasDC) ts_get_wdc(main).max(ts_get_wdc(td));
					if (hasWBR) ts_get_wbr(main).max(ts_get_wbr(td));
					if (hasLSHE) ts_get_lshe(main).max(ts_get_lshe(td));
					if (hasUDP) ts_get_udp(main).max(ts_get_udp(td));
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
		if (!hasWBR && !hasUDP) {
			Assert.assertTrue(DISABLE_EVENT_GRAPH == true);
		}


        long nConfigEnabled = Stream.of(HB, WCP, NWC, WCP_NWC, DC, uDP, WBR, WCP_DC, WCP_WBR, NWC_WBR, NWC_DC, DC_WBR, WCP_DC_WBR, WCP_uDP, WCP_DC_uDP_WBR, WCP_NWC_DC_WBR, WCP_NWC_WBR, WCP_DC_WBR_LSHE).filter(b -> b).count();
        if (nConfigEnabled == 0) {
		    Util.printf("You must enable a config when running the tool, see options %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s.",
                    RR.dcHBOption.getId(), RR.dcWCPOption.getId(), RR.dcNWCOption.getId(), RR.dcWCP_NWCOption.getId(), RR.dcNWC_DCOption.getId(), RR.dcDCOption.getId(), RR.dcuDPOption.getId(), RR.dcWCP_uDPOption.getId(), RR.dcWBROption.getId(), RR.dcWCP_DCOption.getId(), RR.dcWCP_WBROption.getId(), RR.dcNWC_WBROption.getId(), RR.dcWCP_DC_WBROption.getId(), RR.dcWCP_NWC_DC_WBROption.getId(), RR.dcWCP_DC_uDP_WBROption.getId(), RR.dcWCP_DC_WBR_LSHEOption.getId());
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

			if (RR.brVindicateRandomInstances.get()) {
				racesShuffleRandom();
			}
			if (RR.brVindicateShortestInstances.get()) {
				racesShortestFirst();
			}
			HashSet<StaticRace> totalStaticRaces = new HashSet<>(StaticRace.races);
			HashSet<StaticRace> verifiedRaces = new HashSet<>();
			HashSet<StaticRace> totalVerifiedRaces = new HashSet<>();
			HashSet<StaticRace> failedRaces = new HashSet<>();
			Set<Pair<RdWrNode,RdWrNode>> attempted = new HashSet<>();
			final Integer dynInstCount = RR.brVindicateRetries.get();
			for (int i = 1; totalStaticRaces.size() != verifiedRaces.size() && (dynInstCount < 1 || i <= dynInstCount) ; i++) {
				long start = System.currentTimeMillis();
				int attemptedStart = attempted.size();
				// Only vindicate Static WBR-only races
				StaticRace.races.stream().filter(WDCTool::isNonSoundRace).forEach((race) -> {
					vindicateRace(race, verifiedRaces, failedRaces, attempted, false, commandDir);
				});
				failedRaces.clear();
				totalVerifiedRaces.addAll(verifiedRaces);
				if (RR.brRetryVerifiedRaces.get()) verifiedRaces.clear();
				int newAttempt = attempted.size() - attemptedStart;
				if (newAttempt > 0) {
					Util.log("Round " + i + " finished in " + (System.currentTimeMillis() - start) + " ms, attempted " + newAttempt + " races, total verified races " + totalVerifiedRaces.size());
				}
				if (newAttempt == 0) {
					Util.log("Stopping because no new attempt");
					break;
				}
				if (i > RR.brVindicateEarlyThenRandom.get()) {
					racesShuffleRandom();
				}
			}
			Util.log("Stopping vindication, verified " + totalVerifiedRaces.size() + " races.");
			StaticRace.staticRaceMap.forEach(
					(type, races) -> races.forEach((race, count) -> {
						if (totalVerifiedRaces.contains(race)) Util.printf("Verified: %s: %s instances of %s\n", type, count, race);
					})
			);
		}
	}

	private void racesShortestFirst() {
		Util.log("Picking shortest instances first for races.");
		Vector<StaticRace> v = new Vector<>(StaticRace.races);
		v.sort(Comparator.comparingInt(StaticRace::raceDistance));
		StaticRace.races = new ConcurrentLinkedQueue<>(v);
	}

	private static boolean alreadyShuffled = false;
	private void racesShuffleRandom() {
		if (!alreadyShuffled) {
			Util.log("Randomly picking dynamic instances for races.");
			Vector<StaticRace> v = new Vector<>(StaticRace.races);
			Collections.shuffle(v);
			StaticRace.races = new ConcurrentLinkedQueue<>(v);
			alreadyShuffled = true;
		}
	}

	private static boolean isNonSoundRace(StaticRace race) {
		return race.isWBRRace() && !(
				RR.brVindicateDCRaces.get() && race.isMinRace(RaceType.WDCRace)
			||  race.isMinRace(RaceType.WCPRace)
			||  race.isMinRace(RaceType.HBRace)
			||  RR.brVindicateNWCRaces.get() && race.isMinRace(RaceType.NWCRace)
		);
	}

	public void vindicateRace(StaticRace race, HashSet<StaticRace> verifiedRaces, HashSet<StaticRace> failedRaces, Set<Pair<RdWrNode, RdWrNode>> attemptedRaces, boolean dryRun, File commandDir) {
		RdWrNode startNode = race.firstNode;
		RdWrNode endNode = race.secondNode;
		String desc = race.raceType + " " + race.description();

		Pair<RdWrNode, RdWrNode> nodePair = new Pair<>(race.firstNode, race.secondNode);
		if (attemptedRaces != null && attemptedRaces.contains(nodePair)) return;
		if (verifiedRaces != null && verifiedRaces.contains(race)) return;
		if (failedRaces != null && failedRaces.contains(race)) return;

		Util.println("Checking " + desc + " for event pair " + startNode + " -> " + endNode);
		if (dryRun) return;

		// WBR does not draw race edges, it might be missing
		EventNode.addEdge(startNode, endNode);
		boolean failed;
		try {
			 failed = EventNode.crazyNewEdges(startNode, endNode, commandDir);
		} catch (ReorderingTimeout t) {
			Util.log("Reordering timed out.");
			failed = true;
		}
		EventNode.removeEdge(startNode, endNode);

		if (attemptedRaces != null) attemptedRaces.add(nodePair);

		if (!failed) {
			if (verifiedRaces != null) verifiedRaces.add(race);
		} else {
			if (failedRaces != null) failedRaces.add(race);
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

	private static EventNode getEventNode(ShadowThread td) {
		if (hasWBR)
			return ((CVE)ts_get_wbr(td)).eventNode;
		if (hasUDP)
			return ((CVE)ts_get_udp(td)).eventNode;
		return null;
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
				if (VERBOSE) Assert.assertTrue((td.getParent() != null) == (ts_get_wbr(td) instanceof CVE));
				if (td.getParent() != null) {
					EventNode forkEventNode = getEventNode(td);
					EventNode.addEdge(forkEventNode, thisEventNode);
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
							killFastPath(main);

							//Create a hard edge from the last event in the main thread to the parentless first event in this thread
							int mainTid = main.getTid();
							if (hasWCP) { // Compute WCP before modifying HB
								ts_get_wcp(td).max(ts_get_hb(main)); // Use HB here because this is a hard WCP edge
							}
							if (hasNWC) { // Compute NWC before modifying HB
								ts_get_nwc(td).max(ts_get_hb(main)); // Use HB here because this is a hard NWC edge
							}
							if (hasHB) {
								final CV mainHB = ts_get_hb(main);
								final CV hb = ts_get_hb(td);
								hb.max(mainHB);
								mainHB.inc(mainTid);
							}
							if (hasDC) {
								final CV mainWDC = ts_get_wdc(main);
								final CV wdc = ts_get_wdc(td);
								wdc.max(mainWDC);
								mainWDC.inc(mainTid);
							}
							if (hasUDP) {
								final CV mainuDP = ts_get_udp(main);
								final CV uDP = ts_get_udp(td);
								uDP.max(mainuDP);
								mainuDP.inc(mainTid);
							}
							if (hasWBR) {
								final CV mainWBR = ts_get_wbr(main);
								final CV wbr = ts_get_wbr(td);
								wbr.max(mainWBR);
								mainWBR.inc(mainTid);
							}
							if (hasLSHE) {
								final CV mainLSHE = ts_get_lshe(main);
								final CV lshe = ts_get_lshe(td);
								lshe.max(mainLSHE);
								mainLSHE.inc(mainTid);
							}
							if (hasWBR)
								ts_set_wbr(td, new CVE(ts_get_wbr(td), ts_get_lastEventNode(main))); // for generating event node graph
							else if (hasUDP)
								ts_set_udp(td, new CVE(ts_get_udp(td), ts_get_lastEventNode(main)));
							
							Assert.assertTrue(td.getTid() != 0);
							Assert.assertTrue(ts_get_lastEventNode(main) != null);
							
							//Add edge from main to first event in the thread
							if (VERBOSE) Assert.assertTrue(((CVE)ts_get_wbr(td)).eventNode == ts_get_lastEventNode(main));
							
							EventNode forkEventNode = null;
							if (hasWBR) forkEventNode = ((CVE)ts_get_wbr(td)).eventNode;
							else if (hasUDP) forkEventNode = ((CVE)ts_get_udp(td)).eventNode;
							EventNode.addEdge(forkEventNode, thisEventNode);
							
							if (VERBOSE && EventNode.VERBOSE_GRAPH) {
								EventNode eventOne = EventNode.threadToFirstEventMap.get(0);
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
			if ((hasHB && ts_get_hb(td).get(0) == 0) || (DC && ts_get_wdc(td).get(0) == 0) || (WBR && ts_get_wbr(td).get(0) == 0) || (uDP && ts_get_udp(td).get(0) == 0)) {
				if (PRINT_EVENT) Util.log("parentless fork to T"+td.getTid());
				if (COUNT_EVENT) fake_fork.inc(td.getTid());
				//Get the main thread
				//TODO: Same as exit, the main thread is being accessed by a different thread
				ShadowThread main = ShadowThread.get(0);
				if (VERBOSE) Assert.assertTrue(main != null);//, "The main thread can not be found.");
				synchronized(main) { //Will this deadlock? Same as exit, I don't think main will lock on this thread.

					// Accesses after the parentless fork may race with accesses in the new thread
					killFastPath(main);

					//Create a hard edge from the last event in the main thread to the parentless first event in this thread
					int mainTid = main.getTid();
					if (hasWCP) { // Compute WCP before modifying HB
						ts_get_wcp(td).max(ts_get_hb(main)); // Use HB here because this is a hard WCP edge
					}
					if (hasNWC) { // Compute NWC before modifying HB
						ts_get_nwc(td).max(ts_get_hb(main)); // Use HB here because this is a hard NWC edge
					}
					if (hasHB) {
						final CV mainHB = ts_get_hb(main);
						final CV hb = ts_get_hb(td);
						hb.max(mainHB);
						mainHB.inc(mainTid);
					}
					if (hasDC) {
						final CV mainWDC = ts_get_wdc(main);
						final CV wdc = ts_get_wdc(td);
						wdc.max(mainWDC);
						mainWDC.inc(mainTid);
					}
					if (hasUDP) {
						final CV mainuDP = ts_get_udp(main);
						final CV uDP = ts_get_udp(td);
						uDP.max(mainuDP);
						mainuDP.inc(mainTid);
					}
					if (hasWBR) {
						final CV mainWBR = ts_get_wbr(main);
						final CV wbr = ts_get_wbr(td);
						wbr.max(mainWBR);
						mainWBR.inc(mainTid);
					}
					if (hasLSHE) {
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
				thisEventNode = new AcqRelNode(-2, shadowLock, td.getTid(), true, currentCriticalSection, ae.getInfo().getLoc().getFriendlySourceLoc());
				updateCurrentCriticalSectionAtAcquire(td, thisEventNode);
			}
			handleEvent(ae, thisEventNode);
			if (VERBOSE && !DISABLE_EVENT_GRAPH) Assert.assertTrue(thisEventNode.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));
			
			handleAcquire(td, shadowLock, thisEventNode, false);
			
			// Accesses inside critical sections may form relations different from ones outside
			killFastPath(td);
			
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
		if (CAPTURE_SHORTEST_RACE) ts_set_lamport(td, Math.max(ts_get_lamport(td), lockData.lamport+1));
		
		if (hasHB) {
			ts_get_hb(td).max(lockData.hb);
		}
		
		// WCP, DC, WBR
		if (VERBOSE) Assert.assertTrue(lockData.readVars.isEmpty() && lockData.writeVars.isEmpty());
		
		if (hasWCP) {
			final CV hb = ts_get_hb(td);
			final CV wcp = ts_get_wcp(td);
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
		if (hasNWC) {
			final CV hb = ts_get_hb(td);
			final CV nwc = ts_get_nwc(td);
			if (isHardEdge) {
				nwc.max(lockData.hb); // If a hard edge, union WCP with HB
			} else {
				nwc.max(lockData.nwc);
			}

			final CV nwcUnionPO = new CV(nwc);
			nwcUnionPO.set(tid, hb.get(tid));
			// acqQueueMap is protected by the lock corresponding to the shadowLock being currently held
			for (ShadowThread otherTD : ShadowThread.getThreads()) {
				if (otherTD != td) {
					ArrayDeque<CV> queue = lockData.nwcAcqQueueMap.get(otherTD);
					if (queue == null) {
						queue = lockData.nwcAcqQueueGlobal.clone(); // Include any stuff that didn't get added because otherTD hadn't been created yet
						lockData.nwcAcqQueueMap.put(otherTD, queue);
					}
					queue.addLast(nwcUnionPO);
				}
			}

			// Also add to the queue that we'll use for any threads that haven't been created yet.
			// But before doing that, be sure to initialize *this thread's* queues for the lock using the global queues.
			ArrayDeque<CV> acqQueue = lockData.nwcAcqQueueMap.get(td);
			if (acqQueue == null) {
				acqQueue = lockData.nwcAcqQueueGlobal.clone();
				lockData.nwcAcqQueueMap.put(td, acqQueue);
			}
			ArrayDeque<CV> relQueue = lockData.nwcRelQueueMap.get(td);
			if (relQueue == null) {
				relQueue = lockData.nwcRelQueueGlobal.clone();
				lockData.nwcRelQueueMap.put(td, relQueue);
			}
			lockData.nwcAcqQueueGlobal.addLast(nwcUnionPO);
		}
		if (hasDC) {
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
		if (hasUDP) {
			final CV udp = ts_get_udp(td);
			if (isHardEdge) {
				udp.max(lockData.udp);
			} // Don't max otherwise for udp, since it's not transitive with HB

			CVE udpCVE = new CVE(udp, thisEventNode);
			// acqQueueMap is protected by the lock corresponding to the shadowLock being currently held
			for (ShadowThread otherTD : ShadowThread.getThreads()) {
				if (otherTD != td) {
					PerThreadQueue<CVE> ptQueue = lockData.udpAcqQueueMap.get(otherTD);
					if (ptQueue == null) {
						ptQueue = lockData.udpAcqQueueGlobal.clone();
						lockData.udpAcqQueueMap.put(otherTD, ptQueue);
					}
					ptQueue.addLast(td, udpCVE);
				}
			}

			// Also add to the queue that we'll use for any threads that haven't been created yet.
			// But before doing that, be sure to initialize *this thread's* queues for the lock using the global queues.
			PerThreadQueue<CVE> acqPTQueue = lockData.udpAcqQueueMap.get(td);
			if (acqPTQueue == null) {
				acqPTQueue = lockData.udpAcqQueueGlobal.clone();
				lockData.udpAcqQueueMap.put(td, acqPTQueue);
			}
			PerThreadQueue<CVE> relPTQueue = lockData.udpRelQueueMap.get(td);
			if (relPTQueue == null) {
				relPTQueue = lockData.udpRelQueueGlobal.clone();
				lockData.udpRelQueueMap.put(td, relPTQueue);
			}
			lockData.udpAcqQueueGlobal.addLast(td, udpCVE);
		}
		if (hasWBR) {
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
				thisEventNode = new AcqRelNode(-2, shadowLock, td.getTid(), false, currentCriticalSection, re.getInfo().getLoc().getFriendlySourceLoc());
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
		killFastPath(td);

		if (!DISABLE_EVENT_GRAPH) {
			AcqRelNode myAcqNode = thisEventNode.inCS;
			if (VERBOSE) Assert.assertTrue(myAcqNode.isAcquire());
			// This release's corresponding acquire node should not be touched while the lock is currently held
			thisEventNode.otherCriticalSectionNode = myAcqNode;
			myAcqNode.otherCriticalSectionNode = thisEventNode;
		}
		
		if (hasHB) {
			final CV hb = ts_get_hb(td);
			
			// Assign to lock
			lockData.hb.assignWithResize(hb);
		}
		if (hasWCP) {
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
		if (hasNWC) {
			final CV hb = ts_get_hb(td);
			final CV nwc = ts_get_nwc(td);
			final CV nwcUnionPO = new CV(nwc);
			nwcUnionPO.set(tid, hb.get(tid));

			// Process queue elements
			ArrayDeque<CV> acqQueue = lockData.nwcAcqQueueMap.get(td);
			ArrayDeque<CV> relQueue = lockData.nwcRelQueueMap.get(td);
			while (!acqQueue.isEmpty() && !acqQueue.peekFirst().anyGt(nwcUnionPO)) {
				acqQueue.removeFirst();
				nwc.max(relQueue.removeFirst());
			}

			// Rule (a)
			for (ShadowVar var : lockData.readVars) {
				CV cv = lockData.nwcReadMap.get(var);
				if (cv == null) {
					cv = new CV(WDCTool.INIT_CV_SIZE);
					lockData.nwcReadMap.put(var, cv);
				}
				cv.max(hb);
			}
			for (ShadowVar var : lockData.writeVars) {
				CV cv = lockData.nwcWriteMap.get(var);
				if (cv == null) {
					cv = new CV(WDCTool.INIT_CV_SIZE);
					lockData.nwcWriteMap.put(var, cv);
				}
				cv.max(hb);
			}

			// Assign to lock
			lockData.hb.assignWithResize(hb);
			lockData.nwc.assignWithResize(nwc);

			// Add to release queues
			CV hbCopy = new CV(hb);
			for (ShadowThread otherTD : ShadowThread.getThreads()) {
				if (otherTD != td) {
					ArrayDeque<CV> queue = lockData.nwcRelQueueMap.get(otherTD);
					if (queue == null) {
						queue = lockData.nwcRelQueueGlobal.clone(); // Include any stuff that didn't get added because otherTD hadn't been created yet
						lockData.nwcRelQueueMap.put(otherTD, queue);
					}
					queue.addLast(hbCopy);
				}
			}
			// Also add to the queue that we'll use for any threads that haven't been created yet
			lockData.nwcRelQueueGlobal.addLast(hbCopy);

			// Clear read/write maps
			lockData.nwcReadMap = getPotentiallyShrunkMap(lockData.nwcReadMap);
			lockData.nwcWriteMap = getPotentiallyShrunkMap(lockData.nwcWriteMap);
		}
		if (hasDC) {
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

		if (hasUDP) {
			final CV udp = ts_get_udp(td);

			// Process queue elements
			PerThreadQueue<CVE> acqPTQueue = lockData.udpAcqQueueMap.get(td);
			if (VERBOSE) Assert.assertTrue(acqPTQueue.isEmpty(td));
			PerThreadQueue<CVE> relPTQueue = lockData.udpRelQueueMap.get(td);
			for (ShadowThread otherTD : ShadowThread.getThreads()) {
				if (otherTD != td) {
					while (!acqPTQueue.isEmpty(otherTD) && !acqPTQueue.peekFirst(otherTD).anyGt(udp)) {
						acqPTQueue.removeFirst(otherTD);
						CVE prevRel = relPTQueue.removeFirst(otherTD);

						if (!DISABLE_EVENT_GRAPH && (uDP || WCP_uDP)) {
							// If local VC is up-to-date w.r.t. prevRel, then no need to add a new edge
							// Protected by the fact that the lock is currently held
							if (prevRel.anyGt(udp)) {
								EventNode.addEdge(prevRel.eventNode, thisEventNode);
							}
						}

						udp.max(prevRel);
					}
				}
			}

			// Rule (a)
			for (ShadowVar var : lockData.writeVars) {
				lockData.udpWriteMap.put(var, new CVE(udp, thisEventNode));
			}

			// Assign to lock
			lockData.udp.assignWithResize(udp); // Used for hard notify -> wait edge

			// Add to release queues
			CVE udpCVE = new CVE(udp, thisEventNode);
			for (ShadowThread otherTD : ShadowThread.getThreads()) {
				if (otherTD != td) {
					PerThreadQueue<CVE> queue = lockData.udpRelQueueMap.get(otherTD);
					if (queue == null) {
						queue = lockData.udpRelQueueGlobal.clone(); // Include any stuff that didn't get added because otherTD hadn't been created yet
						lockData.udpRelQueueMap.put(otherTD, queue);
					}
					queue.addLast(td, udpCVE);
				}
			}
			// Also add to the queue that we'll use for any threads that haven't been created yet
			lockData.udpRelQueueGlobal.addLast(td, udpCVE);

			// Clear write map
			lockData.udpWriteMap = getPotentiallyShrunkMap(lockData.udpWriteMap);
		}
		
		if (hasWBR) {
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

						if (!DISABLE_EVENT_GRAPH) {
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
			lockData.readVars = new HashSet<>();
			lockData.writeVars = new HashSet<>();
		}
		
		// Increment since release can have outgoing edges
		// Safe since accessed by only this thread
		if (CAPTURE_SHORTEST_RACE) ts_set_lamport(td, ts_get_lamport(td) + 1);
		if (hasHB) {
			ts_get_hb(td).inc(tid); // Don't increment WCP or NWC since it doesn't include PO
		}
		if (hasDC) {
			ts_get_wdc(td).inc(tid);
		}
		if (hasWBR) {
			ts_get_wbr(td).inc(tid);
		}
		if (hasUDP) {
			ts_get_udp(td).inc(tid);
		}
		if (hasLSHE) {
			if (isHardEdge) ts_get_lshe(td).inc(tid);
		}
		
		//Set latest Release node for lockData
		lockData.latestRelNode = thisEventNode;

		lockData.lamport = ts_get_lamport(td);
	}
	
	static private void killFastPath(ShadowThread td) {
		if (hasHB) {
			// The local clock slots should be equal along all relations
			if (VERBOSE && hasDC) Assert.assertTrue(ts_get_hb(td).get(td.getTid()) == ts_get_wdc(td).get(td.getTid()));
			if (VERBOSE && hasWBR) Assert.assertTrue(ts_get_hb(td).get(td.getTid()) == ts_get_wbr(td).get(td.getTid()));
			ts_set_eTd(td, ts_get_hb(td).get(td.getTid()));
		} else if (DC) {
			ts_set_eTd(td, ts_get_wdc(td).get(td.getTid()));
		} else if (WBR) {
			ts_set_eTd(td, ts_get_wbr(td).get(td.getTid()));
		} else if (uDP) {
			ts_set_eTd(td, ts_get_udp(td).get(td.getTid()));
		}
	}

	public static <K,V> WeakIdentityHashMap<K,V>getPotentiallyShrunkMap(WeakIdentityHashMap<K, V> map) {
		if (map.tableSize() > 16 &&
		    10 * map.size() < map.tableSize() * map.loadFactorSize()) {
			return new WeakIdentityHashMap<K,V>(2 * (int)(map.size() / map.loadFactorSize()), map);
		}
		return map;
	}
	
	public static boolean readFastPath(final ShadowVar orig, final ShadowThread td) {
		WDCGuardState x = (WDCGuardState)orig;

		synchronized(td) {
			if (hasHB) {
				if (x.hbRead.get(td.getTid()) >= ts_get_eTd(td)) {
					if (COUNT_EVENT) readFP.inc(td.getTid());
					return true;
				}
			}
			if (uDP) {
				if (x.udpRead.get(td.getTid()) >= ts_get_eTd(td)) {
					if (COUNT_EVENT) readFP.inc(td.getTid());
					return true;
				}
			}
			if (DC) {
				if (x.wdcRead.get(td.getTid()) >= ts_get_eTd(td)) {
					if (COUNT_EVENT) readFP.inc(td.getTid());
					return true;
				}
			}
			if (WBR) {
				if (x.wbrRead.get(td.getTid()) >= ts_get_eTd(td)) {
					if (COUNT_EVENT) readFP.inc(td.getTid());
					return true;
				}
			}
		}
		return false;
	}
	
	public static boolean writeFastPath(final ShadowVar orig, final ShadowThread td) {
		WDCGuardState x = (WDCGuardState)orig;

		synchronized(td) {
			if (hasHB) {
				if (x.hbWrite.get(td.getTid()) >= ts_get_eTd(td)) {
					if (COUNT_EVENT) writeFP.inc(td.getTid());
					return true;
				}
			}
			if (uDP) {
				if (x.udpWrite.get(td.getTid()) >= ts_get_eTd(td)) {
					if (COUNT_EVENT) writeFP.inc(td.getTid());
					return true;
				}
			}
			if (DC) {
				if (x.wdcWrite.get(td.getTid()) >= ts_get_eTd(td)) {
					if (COUNT_EVENT) writeFP.inc(td.getTid());
					return true;
				}
			}
			if (WBR) {
				if (x.wbrWrite.get(td.getTid()) == ts_get_eTd(td)) {
					if (COUNT_EVENT) writeFP.inc(td.getTid());
					return true;
				}
			}
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
						thisEventNode = new RdWrDebugNode(-2, -1, fae.isWrite(), fieldName, x, td.getTid(), currentCriticalSection, fae.getAccessInfo().getLoc().getFriendlySourceLoc());
					} else {
						thisEventNode = new RdWrNode(-2, fae.isWrite(), x, td.getTid(), currentCriticalSection, fae.getAccessInfo().getLoc().getFriendlySourceLoc());
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
						if (hasHB) ts_get_hb(td).max(initTime.hbWrite);
						if (hasWCP) {
							ts_get_wcp(td).max(initTime.hbWrite); // union with HB since this is effectively a hard WCP edge
						}
						if (hasNWC) {
							ts_get_nwc(td).max(initTime.hbWrite); // union with HB since this is effectively a hard NWC edge
						}
						if (hasDC) {
							ts_get_wdc(td).max(initTime.wdcWrite);
						}
						if (hasUDP) {
							ts_get_udp(td).max(initTime.udp);
						}
						if (hasWBR) {
							ts_get_wbr(td).max(initTime.wbr);
						}
						if (hasLSHE) {
							ts_get_lshe(td).max(initTime.lshe);
						}
						if (!DISABLE_EVENT_GRAPH) {
							if (hasWBR) {
								if (initTime.wbr.anyGt(ts_get_wbr(td))) {
									EventNode.addEdge(initTime.wbr.eventNode, thisEventNode);
								}
							} else if (hasUDP) {
								if (initTime.udp.anyGt(ts_get_udp(td))) {
									EventNode.addEdge(initTime.udp.eventNode, thisEventNode);
								}
							}
						}
					}
				}

				if (hasNWC && fae.isRead()) {
					// Apply any delayed wr-wr conflict edges
					CV delayed = ts_get_nwc_delayed(td).remove(x);
					if (delayed != null) ts_get_nwc(td).max(delayed);
				}
				CV nwcSameLockWrites = null; // Only maintained if hasNWC && fae.isWrite()
				if (hasNWC && !hasWBR && fae.isWrite()) { // Skip if WBR is enabled because we already have locksets for WBR
					nwcSameLockWrites = new CV(INIT_CV_SIZE);
				}

				// Update variables accessed in critical sections for rule (a)
				for (int i = td.getNumLocksHeld() - 1; i >= 0; i--) {
					ShadowLock lock = td.getHeldLock(i);
					WDCLockData lockData = get(lock);

					// Account for conflicts with prior critical section instances
					if (hasWCP) {
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
					if (hasNWC) {
						final CV nwc = ts_get_nwc(td);
						final CV priorCriticalSectionAfterWrite = lockData.nwcWriteMap.get(x);
						if (fae.isRead()) { // No direct write-write conflict edges
							if (priorCriticalSectionAfterWrite != null) {
								nwc.max(priorCriticalSectionAfterWrite);
							}
						}
						if (fae.isWrite()) {
							CV priorCriticalSectionAfterRead = lockData.nwcReadMap.get(x);
							if (priorCriticalSectionAfterRead != null) {
								nwc.max(priorCriticalSectionAfterRead);
							}
							if (priorCriticalSectionAfterWrite != null) {
								// wr-wr edge, delayed until the next read
								ts_get_nwc_delayed(td)
										.computeIfAbsent(x, var -> new CV(INIT_CV_SIZE))
										.max(priorCriticalSectionAfterWrite);
								if (!hasWBR) nwcSameLockWrites.max(priorCriticalSectionAfterWrite);
							}
						}
					}
					if (hasDC) {
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
					if (NWC) {
						final CV hb = ts_get_hb(td);
						final CV nwc = ts_get_nwc(td);
						boolean foundRace = checkForRacesNWC(fae.isWrite(), x, fae, td, hb, nwc, nwcSameLockWrites);
						// Update thread VCs if race detected (to correspond with edge being added)
						if (foundRace) {
							hb.max(x.hbWrite);
							if (fae.isWrite()) {
								hb.max(x.hbReadsJoined);
								nwc.max(x.hbReadsJoined);
								ts_get_nwc_delayed(td)
										.computeIfAbsent(x, var -> new CV(INIT_CV_SIZE))
										.max(x.hbWrite);
							} else { // isRead
								nwc.max(x.hbWrite);
							}
						} else if (VERBOSE) { // Check that we don't need to update CVs if there was no race)
							Assert.assertTrue(!x.hbWrite.anyGt(hb));
							if (fae.isWrite()) {
								Assert.assertTrue(!x.hbReadsJoined.anyGt(hb));
								ts_get_nwc_delayed(td).put(x, new CV(x.hbWrite));
							} else { // isRead
								Assert.assertTrue(!x.hbWrite.anyGt(hb));
								final CV nwcUnionPO = new CV(nwc);
								nwcUnionPO.set(tid, hb.get(tid));
								Assert.assertTrue(!x.nwcWritesJoined.anyGt(nwcUnionPO));
							}
						}
					}
					if (WCP_NWC) {
						final CV hb = ts_get_hb(td);
						final CV wcp = ts_get_wcp(td);
						final CV nwc = ts_get_nwc(td);
						boolean foundRace = checkForRacesNWC(fae.isWrite(), x, fae, td, hb, wcp, nwc, nwcSameLockWrites);
						// Update thread VCs if race detected (to correspond with edge being added)
						if (foundRace) {
							hb.max(x.hbWrite);
							wcp.max(x.hbWrite);
							if (fae.isWrite()) {
								hb.max(x.hbReadsJoined);
								wcp.max(x.hbReadsJoined);
								nwc.max(x.hbReadsJoined);
								ts_get_nwc_delayed(td)
										.computeIfAbsent(x, var -> new CV(INIT_CV_SIZE))
										.max(x.hbWrite);
							} else { // isRead
								nwc.max(x.hbWrite);
							}
						} else if (VERBOSE) { // Check that we don't need to update CVs if there was no race)
							Assert.assertTrue(!x.hbWrite.anyGt(hb));
							final CV wcpUnionPO = new CV(wcp);
							wcpUnionPO.set(tid, hb.get(tid));
							Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));

							if (fae.isWrite()) {
								Assert.assertTrue(!x.hbReadsJoined.anyGt(hb));
								Assert.assertTrue(!x.wcpReadsJoined.anyGt(wcpUnionPO));
							} else { // isRead
								Assert.assertTrue(!x.hbWrite.anyGt(hb));
								Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));
								final CV nwcUnionPO = new CV(nwc);
								nwcUnionPO.set(tid, hb.get(tid));
								Assert.assertTrue(!x.nwcWritesJoined.anyGt(nwcUnionPO));
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
					if (uDP) {
						final CV udp = ts_get_udp(td);

						// Rule a, wr-rd edge from the last writer of the read
						if (fae.isRead()) {
							ts_set_hasread(td, true); // Mark that we have seen a read, next branch can't be a fast path
							if (x.hasLastWriter() && x.lastWriteTid != td.getTid()) {
								ShadowLock shadowLock = findCommonLock(x.heldLocksWrite.get(x.lastWriteTid), td.getLocksHeld());
								if (shadowLock != null) { // Common lock exists, prepare for the edge
									WDCLockData lock = get(shadowLock);
									CVE writes = lock.wbrWriteMap.get(x);
									if (!DISABLE_EVENT_GRAPH && writes.anyGt(udp))
										EventNode.addEdge(writes.eventNode, thisEventNode);
									udp.max(writes);
								}
							}
						}

						checkForRacesuDP(fae.isWrite(), x, fae, td, udp, thisEventNode);
						// uDP race edges are drawn in checkForRacesuDP
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
										if (!NO_SDG) writes = new CVED(writes, thisEventNode, fae.getAccessInfo().getLoc().getSourceLoc());
										ts_get_wbr_delayed(td).put(x, writes);
									}
								}
							}
						}

						checkForRacesWBR(fae.isWrite(), x, fae, td, wbr, thisEventNode);
						// WBR race edges are drawn in checkForRacesWBR
					}
					if (NWC_WBR) {
						final CV wbr = ts_get_wbr(td);
						final CV nwc = ts_get_nwc(td);
						final CV hb = ts_get_hb(td);

						// Rule a, wr-rd edge from the last writer of the read
						if (fae.isRead()) {
							ts_set_hasread(td, true); // Mark that we have seen a read, next branch can't be a fast path
							if (x.hasLastWriter() && x.lastWriteTid != td.getTid()) {
								ShadowLock shadowLock = findCommonLock(x.heldLocksWrite.get(x.lastWriteTid), td.getLocksHeld());
								if (shadowLock != null) { // Common lock exists, prepare for the edge
									WDCLockData lock = get(shadowLock);
									CVE writes = lock.wbrWriteMap.get(x);
									if (writes.anyGt(wbr)) { // Only prepare for the edge if it is not redundant
										if (!NO_SDG) writes = new CVED(writes, thisEventNode, fae.getAccessInfo().getLoc().getSourceLoc());
										ts_get_wbr_delayed(td).put(x, writes);
									}
								}
							}
						}

						boolean foundRace = checkForRacesNWC_WBR(fae.isWrite(), x, fae, td, hb, nwc, wbr, thisEventNode);
						// WBR race edges are drawn in checkForRacesWBR

						if (foundRace) {
							hb.max(x.hbWrite);
							if (fae.isWrite()) {
								hb.max(x.hbReadsJoined);
								nwc.max(x.hbReadsJoined);
								ts_get_nwc_delayed(td)
										.computeIfAbsent(x, var -> new CV(INIT_CV_SIZE))
										.max(x.hbWrite);
							} else { // isRead
								nwc.max(x.hbWrite);
							}
						} else if (VERBOSE) { // Check that we don't need to update CVs if there was no race)
							Assert.assertTrue(!x.hbWrite.anyGt(hb));
							if (fae.isWrite()) {
								Assert.assertTrue(!x.hbReadsJoined.anyGt(hb));
							} else { // isRead
								Assert.assertTrue(!x.hbWrite.anyGt(hb));
								final CV nwcUnionPO = new CV(nwc);
								nwcUnionPO.set(tid, hb.get(tid));
								Assert.assertTrue(!x.nwcWritesJoined.anyGt(nwcUnionPO));
							}
						}
					}
					if (NWC_DC) {
						final CV hb = ts_get_hb(td);
						final CV nwc = ts_get_nwc(td);
						final CV wdc = ts_get_wdc(td);
						boolean foundRace = checkForRacesNWCDC(fae.isWrite(), x, fae, td, hb, nwc, nwcSameLockWrites, wdc);
						// Update thread VCs if race detected (to correspond with edge being added)
						if (foundRace) {
							hb.max(x.hbWrite);
							wdc.max(x.wdcWrite);
							if (fae.isWrite()) {
								hb.max(x.hbReadsJoined);
								wdc.max(x.wdcReadsJoined);
								nwc.max(x.hbReadsJoined);
								ts_get_nwc_delayed(td)
										.computeIfAbsent(x, var -> new CV(INIT_CV_SIZE))
										.max(x.hbWrite);
							} else {
								nwc.max(x.hbWrite);
							}
						} else { // Check that we don't need to update CVs if there was no race)
							if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
							if (VERBOSE) Assert.assertTrue(!x.wdcWrite.anyGt(wdc));
							if (fae.isWrite()) {
								if (VERBOSE) Assert.assertTrue(!x.hbReadsJoined.anyGt(hb));
								if (VERBOSE) Assert.assertTrue(!x.wdcReadsJoined.anyGt(wdc));
							}
						}
					}
					if (WCP_DC) {
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
										if (!NO_SDG) writes = new CVED(writes, thisEventNode, fae.getAccessInfo().getLoc().getSourceLoc());
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
					if (WCP_uDP) {
						final CV hb = ts_get_hb(td);
						final CV wcp = ts_get_wcp(td);
						final CV udp = ts_get_udp(td);

						// WBR Rule a, wr-rd edge from the last writer of the read
						if (fae.isRead()) {
							if (x.hasLastWriter() && x.lastWriteTid != td.getTid()) {
								ShadowLock shadowLock = findCommonLock(x.heldLocksWrite.get(x.lastWriteTid), td.getLocksHeld());
								if (shadowLock != null) { // Common lock exists, prepare for the edge
									WDCLockData lock = get(shadowLock);
									CVE writes = lock.wbrWriteMap.get(x);
									if (!DISABLE_EVENT_GRAPH && writes.anyGt(udp))
										EventNode.addEdge(writes.eventNode, thisEventNode);
									udp.max(writes);
								}
							}
						}

						boolean foundRace = checkForRacesuDP(fae.isWrite(), x, fae, td, hb, wcp, udp, thisEventNode);
						// Update thread VCs if race detected (to correspond with edge being added)
						if (foundRace) {
							hb.max(x.hbWrite);
							wcp.max(x.hbWrite);
							if (fae.isWrite()) {
								hb.max(x.hbReadsJoined);
								wcp.max(x.hbReadsJoined);
							}
						} else if (VERBOSE) { // Check that we don't need to update CVs if there was no race)
							Assert.assertTrue(!x.hbWrite.anyGt(hb));
							final CV wcpUnionPO = new CV(wcp);
							wcpUnionPO.set(tid, hb.get(tid));
							Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));
							if (fae.isWrite()) {
								Assert.assertTrue(!x.hbReadsJoined.anyGt(hb));
								Assert.assertTrue(!x.wcpReadsJoined.anyGt(wcpUnionPO));
							}
						}
					}
					if (DC_WBR) {
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
										if (!NO_SDG) writes = new CVED(writes, thisEventNode, fae.getAccessInfo().getLoc().getSourceLoc());
										ts_get_wbr_delayed(td).put(x, writes);
									}
								}
							}
						}

						boolean foundRace = checkForRacesDC_WBR(fae.isWrite(), x, fae, td, wdc, wbr, thisEventNode);
						// Update thread VCs if race detected (to correspond with edge being added)
						if (foundRace) {
							// Prior relations order all conflicting accesses, WBR race edges are drawn in checkForRacesWBR
							wdc.max(x.wdcWrite);
							if (fae.isWrite()) {
								wdc.max(x.wdcReadsJoined);
							}
						} else { // Check that we don't need to update CVs if there was no race)
							if (VERBOSE) Assert.assertTrue(!x.wdcWrite.anyGt(wdc));
//							if (VERBOSE) Assert.assertTrue(!x.wbrWrite.anyGt(wbr)); // Doesn't hold for WBR due to locksets
							if (fae.isWrite()) {
								if (VERBOSE) Assert.assertTrue(!x.wdcReadsJoined.anyGt(wdc));
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
										if (!NO_SDG) writes = new CVED(writes, thisEventNode, fae.getAccessInfo().getLoc().getSourceLoc());
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
					if (WCP_NWC_DC_WBR) {
						final CV hb = ts_get_hb(td);
						final CV wcp = ts_get_wcp(td);
						final CV nwc = ts_get_nwc(td);
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
										if (!NO_SDG) writes = new CVED(writes, thisEventNode, fae.getAccessInfo().getLoc().getSourceLoc());
										ts_get_wbr_delayed(td).put(x, writes);
									}
								}
							}
						}

						boolean foundRace = checkForRacesWBRNWC(fae.isWrite(), x, fae, td, hb, wcp, nwc, wdc, wbr, thisEventNode);
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
								nwc.max(x.hbReadsJoined);
								ts_get_nwc_delayed(td)
										.computeIfAbsent(x, var -> new CV(INIT_CV_SIZE))
										.max(x.hbWrite);
							} else {
								nwc.max(x.hbWrite);
							}
						} else { // Check that we don't need to update CVs if there was no race)
							if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
							if (VERBOSE) Assert.assertTrue(!x.wdcWrite.anyGt(wdc));
							if (fae.isWrite()) {
								if (VERBOSE) Assert.assertTrue(!x.hbReadsJoined.anyGt(hb));
								if (VERBOSE) Assert.assertTrue(!x.wdcReadsJoined.anyGt(wdc));
							}
						}
					}

					if (WCP_NWC_WBR) {
						final CV hb = ts_get_hb(td);
						final CV wcp = ts_get_wcp(td);
						final CV nwc = ts_get_nwc(td);
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
										if (!NO_SDG) writes = new CVED(writes, thisEventNode, fae.getAccessInfo().getLoc().getSourceLoc());
										ts_get_wbr_delayed(td).put(x, writes);
									}
								}
							}
						}

						boolean foundRace = checkForRacesWBRNWC(fae.isWrite(), x, fae, td, hb, wcp, nwc, wbr, thisEventNode);
						// Update thread VCs if race detected (to correspond with edge being added)
						if (foundRace) {
							// Prior relations order all conflicting accesses, WBR race edges are drawn in checkForRacesWBR
							hb.max(x.hbWrite);
							wcp.max(x.hbWrite);
							if (fae.isWrite()) {
								hb.max(x.hbReadsJoined);
								wcp.max(x.hbReadsJoined);
								nwc.max(x.hbReadsJoined);
								ts_get_nwc_delayed(td)
										.computeIfAbsent(x, var -> new CV(INIT_CV_SIZE))
										.max(x.hbWrite);
							}// else {
								nwc.max(x.hbWrite);
							//}
						} else { // Check that we don't need to update CVs if there was no race)
							if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
							if (fae.isWrite()) {
								if (VERBOSE) Assert.assertTrue(!x.hbReadsJoined.anyGt(hb));
							}
						}
					}

					if (WCP_DC_uDP_WBR) {
						final CV hb = ts_get_hb(td);
						final CV wcp = ts_get_wcp(td);
						final CV wdc = ts_get_wdc(td);
						final CV udp = ts_get_udp(td);
						final CV wbr = ts_get_wbr(td);

						// WBR Rule a, wr-rd edge from the last writer of the read
						if (fae.isRead()) {
							ts_set_hasread(td, true); // Mark that we have seen a read, next branch can't be a fast path
							if (x.hasLastWriter() && x.lastWriteTid != td.getTid()) {
								ShadowLock shadowLock = findCommonLock(x.heldLocksWrite.get(x.lastWriteTid), td.getLocksHeld());
								if (shadowLock != null) { // Common lock exists, prepare for the edge
									WDCLockData lock = get(shadowLock);
									CVE wbrWrites = lock.wbrWriteMap.get(x);
									if (wbrWrites.anyGt(wbr)) { // Only prepare for the edge if it is not redundant
										if (!NO_SDG) wbrWrites = new CVED(wbrWrites, thisEventNode, fae.getAccessInfo().getLoc().getSourceLoc());
										ts_get_wbr_delayed(td).put(x, wbrWrites);

										CV uDPWrites = lock.udpWriteMap.get(x);
										if (uDPWrites.anyGt(udp)) {
											udp.max(uDPWrites);
										}
									}
								}
							}
						}

						boolean foundRace = checkForRacesWBR(fae.isWrite(), x, fae, td, hb, wcp, wdc, udp, wbr, thisEventNode);
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
						} else if (VERBOSE) { // Check that we don't need to update CVs if there was no race)
							Assert.assertTrue(!x.hbWrite.anyGt(hb));
							final CV wcpUnionPO = new CV(wcp);
							wcpUnionPO.set(tid, hb.get(tid));
							Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));
							Assert.assertTrue(!x.wdcWrite.anyGt(wdc));
							if (fae.isWrite()) {
								Assert.assertTrue(!x.hbReadsJoined.anyGt(hb));
								Assert.assertTrue(!x.wcpReadsJoined.anyGt(wcpUnionPO));
								Assert.assertTrue(!x.wdcReadsJoined.anyGt(wdc));
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
										if (!NO_SDG) writes = new CVED(writes, thisEventNode, fae.getAccessInfo().getLoc().getSourceLoc());
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
					
					// Update vector clocks
					if (hasHB) {
						final CV hb = ts_get_hb(td);
						if (fae.isWrite()) {
							x.hbWrite.assignWithResize(hb);
						} else {
							x.hbRead.set(tid, hb.get(tid));
							x.hbReadsJoined.max(hb);
						}
					}
					if (hasWCP) {
						final CV hb = ts_get_hb(td);
						final CV wcp = ts_get_wcp(td);
						final CV wcpUnionPO = new CV(wcp);
						wcpUnionPO.set(tid, hb.get(tid));
						
						if (fae.isWrite()) {
							x.wcpWrite.assignWithResize(wcpUnionPO);
						} else {
							x.wcpRead.set(tid, hb.get(tid));
							x.wcpReadsJoined.max(wcpUnionPO);
						}
					}
					if (hasNWC) {
						final CV hb = ts_get_hb(td);
						final CV nwc = ts_get_nwc(td);
						final CV nwcUnionPO = new CV(nwc);
						nwcUnionPO.set(tid, hb.get(tid));

						if (fae.isWrite()) {
							x.nwcWrite.set(tid, nwcUnionPO.get(tid));
							x.nwcWritesJoined.max(nwcUnionPO);
						} else {
							x.nwcRead.set(tid, hb.get(tid));
							x.nwcReadsJoined.max(nwcUnionPO);
						}
					}
					if (hasDC) {
						final CV wdc = ts_get_wdc(td);
						if (fae.isWrite()) {
							x.wdcWrite.assignWithResize(wdc);
						} else {
							x.wdcRead.set(tid, wdc.get(tid));
							x.wdcReadsJoined.max(wdc);
						}
					}
					if (hasUDP) {
						final CV udp = ts_get_udp(td);
						if (fae.isWrite()) {
							x.udpWrite.set(tid, udp.get(tid));
						} else {
							x.udpRead.set(tid, udp.get(tid));
							x.udpReadsJoined.max(udp);
						}
					}
					if (hasWBR) {
						final CV wbr = ts_get_wbr(td);
						if (fae.isWrite()) {
							// TODO: Is this correct? I'm seeing races with threads that never accessed this variable
							// x.wbrWrite.assignWithResize(wbr);
							x.wbrWrite.set(tid, wbr.get(tid));
						} else {
							x.wbrRead.set(tid, wbr.get(tid));
							x.wbrReadsJoined.max(wbr);
						}
					}
					if (hasWBR || hasUDP) {
						if (fae.isWrite()) {
							x.heldLocksWrite.put(td.getTid(), td.getLocksHeld());
						} else {
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
					if (hasLSHE) {
						final CV lshe = ts_get_lshe(td);
						if (fae.isWrite()) {
							x.lsheWrite.assignWithResize(lshe);
						} else {
							x.lsheRead.set(tid, lshe.get(tid));
							x.lsheReadsJoined.max(lshe);
						}
					}
					
					// Update last event
					final MethodEvent me = td.getBlockDepth() <= 0 ? null : td.getBlock(td.getBlockDepth()-1); //This is how RREventGenerator retrieves a method event
					DynamicSourceLocation dl = new DynamicSourceLocation(fae, thisEventNode, (me == null ? null : me.getInfo()));
					
					if (fae.isWrite()) {
						x.lastWriteTid = tid;
						x.lastWriteEvents[tid] = dl;
						if (CAPTURE_SHORTEST_RACE) x.lastWriteLamport[tid] = ts_get_lamport(td);
					} else {
						x.lastReadEvents[tid] = dl;
						if (CAPTURE_SHORTEST_RACE) x.lastReadLamport[tid] = ts_get_lamport(td);
					}

					if (!DISABLE_EVENT_GRAPH) {
						// Ensure that the event numbers for all accesses are totally ordered.
						// This is assumed for the reordering optimization.
						thisEventNode.eventNumber = Math.max(thisEventNode.eventNumber, x.lastAccessEventNumber + 1);
						x.lastAccessEventNumber = thisEventNode.eventNumber;
					}
					
					// These increments are needed because we might end up creating an outgoing WDC edge from this access event
					// (if it turns out to be involved in a WDC-race).
					if (CAPTURE_SHORTEST_RACE) ts_set_lamport(td, ts_get_lamport(td) + 1);
					if (hasHB) {
						ts_get_hb(td).inc(tid); // Don't increment WCP or NWC since it doesn't include PO
					}
					if (hasDC) {
						ts_get_wdc(td).inc(tid);
					}
					if (hasUDP) {
						ts_get_udp(td).inc(tid);
					}
					if (hasWBR) {
						ts_get_wbr(td).inc(tid);
					}
					if (hasLSHE) {
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

				if (hasWBR && fae instanceof ArrayAccessEvent) { // Array reads are treated as being followed by branches
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
				if (VERBOSE) Assert.assertTrue(thisEventNode != null);

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

	boolean checkForRacesNWC(boolean isWrite, WDCGuardState x, AccessEvent ae, ShadowThread td, CV hb, CV nwc, CV nwcSameLockWrites) {
		final int tid = td.getTid();
		final CV nwcUnionPO = new CV(nwc);
		nwcUnionPO.set(tid, hb.get(tid));
		CV nwcPOWr = null;
		if (isWrite) {
			nwcPOWr = new CV(nwcUnionPO);
			nwcPOWr.max(nwcSameLockWrites);
		}

		int shortestRaceTid = -1;
		boolean shortestRaceIsWrite = false; // only valid if shortestRaceTid != -1
		RaceType shortestRaceType = RaceType.WBROrdered; // only valid if shortestRaceTid != -1
		// First check for race with prior write
		if (x.nwcWrite.anyGt(isWrite ? nwcPOWr : nwcUnionPO)) {
			int index = -1;
			while ((index = x.nwcWrite.nextGt(isWrite ? nwcPOWr : nwcUnionPO, index + 1)) != -1) {
				RaceType type = RaceType.NWCRace;
				if (x.hbWrite.get(index) > hb.get(index)) {
					type = RaceType.HBRace;
				}
				// Update the latest race with the current race since we only want to report one race per access event
				shortestRaceTid = index;
				shortestRaceIsWrite = true;
				shortestRaceType = type;
			}
		} else {
			if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
		}
		// Next check for races with prior reads
		if (isWrite) {
			if (x.nwcRead.anyGt(nwcUnionPO)) {
				int index = -1;
				while ((index = x.nwcRead.nextGt(nwcUnionPO, index + 1)) != -1) {
					RaceType type = RaceType.NWCRace;
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

	boolean checkForRacesNWC(boolean isWrite, WDCGuardState x, AccessEvent ae, ShadowThread td, CV hb, CV wcp, CV nwc, CV nwcSameLockWrites) {
		final int tid = td.getTid();
		final CV nwcUnionPO = new CV(nwc);
		nwcUnionPO.set(tid, hb.get(tid));
		final CV wcpUnionPO = new CV(wcp);
		wcpUnionPO.set(tid, hb.get(tid));
		CV nwcPOWr = null;
		if (isWrite) {
			nwcPOWr = new CV(nwcUnionPO);
			nwcPOWr.max(nwcSameLockWrites);
		}

		int shortestRaceTid = -1;
		boolean shortestRaceIsWrite = false; // only valid if shortestRaceTid != -1
		RaceType shortestRaceType = RaceType.WBROrdered; // only valid if shortestRaceTid != -1

		// First check for race with prior write
		if (x.nwcWrite.anyGt(isWrite ? nwcPOWr : nwcUnionPO)) {
			int index = -1;
			while ((index = x.nwcWrite.nextGt(isWrite ? nwcPOWr : nwcUnionPO, index + 1)) != -1) {
				RaceType type = RaceType.NWCRace;
				if (x.wcpWrite.get(index) > wcpUnionPO.get(index)) {
					type = RaceType.WCPRace;
					if (x.hbWrite.get(index) > hb.get(index)) {
						type = RaceType.HBRace;
					}
				} else if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));

				// Update the latest race with the current race since we only want to report one race per access event
				shortestRaceTid = index;
				shortestRaceIsWrite = true;
				shortestRaceType = type;
			}
		} else {
			if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
			if (VERBOSE) Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));
		}
		// Next check for races with prior reads
		if (isWrite) {
			if (x.nwcRead.anyGt(nwcUnionPO)) {
				int index = -1;
				while ((index = x.nwcRead.nextGt(nwcUnionPO, index + 1)) != -1) {
					RaceType type = RaceType.NWCRace;
					if (x.wcpRead.get(index) > wcpUnionPO.get(index)) {
						type = RaceType.WCPRace;
						if (x.hbRead.get(index) > hb.get(index)) {
							type = RaceType.HBRace;
						}
					} else if (VERBOSE) Assert.assertTrue(!x.hbRead.anyGt(hb));

					// Update the latest race with the current race since we only want to report one race per access event
					shortestRaceTid = index;
					shortestRaceIsWrite = false;
					shortestRaceType = type;
				}
			} else {
				if (VERBOSE) Assert.assertTrue(!x.hbRead.anyGt(hb));
				if (VERBOSE) Assert.assertTrue(!x.wcpRead.anyGt(wcpUnionPO));
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
	

	boolean checkForRacesNWCDC(boolean isWrite, WDCGuardState x, AccessEvent ae, ShadowThread td, CV hb, CV nwc, CV nwcSameLockWrites, CV wdc) {
		int tid = td.getTid();
		final CV nwcUnionPO = new CV(nwc);
		nwcUnionPO.set(tid, hb.get(tid));
		int shortestRaceTid = -1;
		CV nwcPOWr = null;
		if (isWrite) {
			nwcPOWr = new CV(nwcUnionPO);
			nwcPOWr.max(nwcSameLockWrites);
		}

		boolean shortestRaceIsWrite = false; // only valid if shortestRaceTid != -1
		RaceType shortestRaceType = RaceType.WBROrdered; // only valid if shortestRaceTid != -1

		// First check for race with prior write
		if (x.wdcWrite.anyGt(wdc)) {
			int index = -1;
			while ((index = x.wdcWrite.nextGt(wdc, index + 1)) != -1) {
				RaceType type = RaceType.WDCRace;
				if (x.nwcWrite.get(index) > (isWrite ? nwcPOWr : nwcUnionPO).get(index)) {
					type = RaceType.NWCRace;
					if (x.hbWrite.get(index) > hb.get(index)) {
						type = RaceType.HBRace;
					}
				} else {
					if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
				}

				if (type == RaceType.NWCRace) {
					// NWC can find races with writes other than the last write. HB and WDC can only find races
					// with the last write. NWC may potentially find races WDC can't, but we'll ignore them.
					int shortestRaceLamport = shortestRaceTid >= 0 ? x.lastWriteLamport[shortestRaceTid] : -1;
					if (!CAPTURE_SHORTEST_RACE || shortestRaceLamport == -1 || x.lastWriteLamport[index] > shortestRaceLamport) {
						shortestRaceTid = index;
						shortestRaceIsWrite = true;
						shortestRaceType = type;
					}
				} else {
					shortestRaceTid = x.lastWriteTid;
					shortestRaceIsWrite = true;
					shortestRaceType = type;
				}
			}
		} else {
			if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
		}
		// Next check for races with prior reads
		if (isWrite) {
			if (x.wdcRead.anyGt(wdc)) {
				int index = -1;
				while ((index = x.wdcRead.nextGt(wdc, index + 1)) != -1) {
					RaceType type = RaceType.WDCRace;
					if (x.nwcRead.get(index) > nwcUnionPO.get(index)) {
						type = RaceType.NWCRace;
						if (x.hbRead.get(index) > hb.get(index)) {
							type = RaceType.HBRace;
						}
					} else {
						if (VERBOSE) Assert.assertTrue(x.hbRead.get(index) <= hb.get(index));
					}
					//Update the latest race with the current race since we only want to report one race per access event
					int shortestRaceLamport = shortestRaceTid >= 0 ? (shortestRaceIsWrite ? x.lastWriteLamport[shortestRaceTid] : x.lastReadLamport[shortestRaceTid]) : -1;
					if (!CAPTURE_SHORTEST_RACE || shortestRaceLamport == -1 || x.lastWriteLamport[index] > shortestRaceLamport) {
						shortestRaceTid = index;
						shortestRaceIsWrite = false;
						shortestRaceType = type;
					}
					if (VERBOSE) Assert.assertTrue(x.lastReadEvents[index] != null);
				}
			} else {
				if (VERBOSE) Assert.assertTrue(!x.hbRead.anyGt(hb));
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
					int shortestRaceLamport = shortestRaceTid >= 0 ? (shortestRaceIsWrite ? x.lastWriteLamport[shortestRaceTid] : x.lastReadLamport[shortestRaceTid]) : -1;
					if (!CAPTURE_SHORTEST_RACE || shortestRaceLamport == -1 || x.lastWriteLamport[index] > shortestRaceLamport) {
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
				int shortestRaceLamport = shortestRaceTid >= 0 ? x.lastWriteLamport[shortestRaceTid] : -1;
				if (!CAPTURE_SHORTEST_RACE || shortestRaceLamport == -1 || x.lastWriteLamport[index] > shortestRaceLamport) {
					shortestRaceTid = index;
					shortestRaceIsWrite = true;
					shortestRaceType = type;
				}
				// Keep event numbers of racing evenst in observed order even if we don't draw an edge, to avoid accidentally reordering them when they are outside the race window
				// TODO: Crashes with BranchRaceWithPrevWr
				thisEventNode.eventNumber = Math.max(thisEventNode.eventNumber, x.lastWriteEvents[shortestRaceTid].eventNode.eventNumber + 1);
				// Race edge, only for wr-rd races, and only when the racing write is the last writer of the read. Will be later drawn to a branch.
				if (!isWrite && x.lastWriteTid == index) {
					Map<ShadowVar,CVE> delayed = ts_get_wbr_delayed(td);
					CVE edge = new CVE(x.wbrWrite, x.lastWriteEvents[index].eventNode);
					if (!NO_SDG) edge = new CVED(edge, thisEventNode, ae.getAccessInfo().getLoc().getSourceLoc());
					delayed.put(x, edge);
				}
			}
		}
		// Next check for races with prior reads
		if (isWrite) {
			index = -1;
			while ((index = x.wbrRead.nextGt(wbr, index + 1)) != -1) {
				if (findCommonLock(x.heldLocksRead.get(index), heldLocks) == null) {
					RaceType type = RaceType.WBRRace;
					int shortestRaceLamport = shortestRaceTid >= 0 ? (shortestRaceIsWrite ? x.lastWriteLamport[shortestRaceTid] : x.lastReadLamport[shortestRaceTid]) : -1;
					if (!CAPTURE_SHORTEST_RACE || shortestRaceLamport == -1 || x.lastWriteLamport[index] > shortestRaceLamport) {
						shortestRaceTid = index;
						shortestRaceIsWrite = false;
						shortestRaceType = type;
					}
					// Keep event numbers of racing evenst in observed order even if we don't draw an edge, to avoid accidentally reordering them when they are outside the race window
					thisEventNode.eventNumber = Math.max(thisEventNode.eventNumber, x.lastReadEvents[shortestRaceTid].eventNode.eventNumber + 1);
				}
			}
		}
		return recordRace(x, ae, td.getTid(), shortestRaceTid, shortestRaceIsWrite, shortestRaceType, thisEventNode);
	}

	boolean checkForRacesuDP(boolean isWrite, WDCGuardState x, AccessEvent ae, ShadowThread td, CV udp, RdWrNode thisEventNode) {
		int shortestRaceTid = -1;
		boolean shortestRaceIsWrite = false; // only valid if shortestRaceTid != -1
		RaceType shortestRaceType = RaceType.WBROrdered; // only valid if shortestRaceTid != -1
		Collection<ShadowLock> heldLocks = td.getLocksHeld();
		boolean racesWithLastWr = false;

		// First check for race with prior write
		int index = -1;
		while ((index = x.udpWrite.nextGt(udp, index + 1)) != -1) {
			if (findCommonLock(x.heldLocksWrite.get(index), heldLocks) == null) {
				RaceType type = RaceType.uDPRace;
				int shortestRaceLamport = shortestRaceTid >= 0 ? x.lastWriteLamport[shortestRaceTid] : -1;
				if (!CAPTURE_SHORTEST_RACE || shortestRaceLamport == -1 || x.lastWriteLamport[index] > shortestRaceLamport) {
					shortestRaceTid = index;
					shortestRaceIsWrite = true;
					shortestRaceType = type;
				}
				// Keep event numbers of racing evenst in observed order even if we don't draw an edge, to avoid accidentally reordering them when they are outside the race window
				thisEventNode.eventNumber = Math.max(thisEventNode.eventNumber, x.lastWriteEvents[shortestRaceTid].eventNode.eventNumber + 1);
				// Race edge, only for wr-rd races, and only when the racing write is the last writer of the read. Will be later drawn to a branch.
				if (!isWrite && x.lastWriteTid == index) {
					racesWithLastWr = true;
				}
			}
		}
		// Next check for races with prior reads
		if (isWrite) {
			index = -1;
			while ((index = x.udpRead.nextGt(udp, index + 1)) != -1) {
				if (findCommonLock(x.heldLocksRead.get(index), heldLocks) == null) {
					RaceType type = RaceType.uDPRace;
					int shortestRaceLamport = shortestRaceTid >= 0 ? (shortestRaceIsWrite ? x.lastWriteLamport[shortestRaceTid] : x.lastReadLamport[shortestRaceTid]) : -1;
					if (!CAPTURE_SHORTEST_RACE || shortestRaceLamport == -1 || x.lastWriteLamport[index] > shortestRaceLamport) {
						shortestRaceTid = index;
						shortestRaceIsWrite = false;
						shortestRaceType = type;
					}
					// Keep event numbers of racing evenst in observed order even if we don't draw an edge, to avoid accidentally reordering them when they are outside the race window
					thisEventNode.eventNumber = Math.max(thisEventNode.eventNumber, x.lastReadEvents[shortestRaceTid].eventNode.eventNumber + 1);
				}
			}
		}
		if (racesWithLastWr) {
			udp.max(x.udpWrite);
			if (!DISABLE_EVENT_GRAPH) EventNode.addEdge(x.lastWriteEvents[x.lastWriteTid].eventNode, thisEventNode);

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
					int shortestRaceLamport = shortestRaceTid >= 0 ? x.lastWriteLamport[shortestRaceTid] : -1;
					if (!CAPTURE_SHORTEST_RACE || shortestRaceLamport == -1 || x.lastWriteLamport[index] > shortestRaceLamport) {
						shortestRaceTid = index;
						shortestRaceIsWrite = true;
						shortestRaceType = type;
					}
					// Keep event numbers of racing evenst in observed order even if we don't draw an edge, to avoid accidentally reordering them when they are outside the race window
					if (!DISABLE_EVENT_GRAPH) thisEventNode.eventNumber = Math.max(thisEventNode.eventNumber, x.lastWriteEvents[index].eventNode.eventNumber + 1);
					// Race edge, only for wr-rd races, and only when the racing write is the last writer of the read. Will be later drawn to a branch.
					if (!isWrite && x.lastWriteTid == index) {
						Map<ShadowVar,CVE> delayed = ts_get_wbr_delayed(td);
						CVE edge = new CVE(x.wbrWrite, x.lastWriteEvents[index].eventNode);
						if (!NO_SDG) edge = new CVED(edge, thisEventNode, ae.getAccessInfo().getLoc().getSourceLoc());
						delayed.put(x, edge);
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
						int shortestRaceLamport = shortestRaceTid >= 0 ? (shortestRaceIsWrite ? x.lastWriteLamport[shortestRaceTid] : x.lastReadLamport[shortestRaceTid]) : -1;
						if (!CAPTURE_SHORTEST_RACE || shortestRaceLamport == -1 || x.lastWriteLamport[index] > shortestRaceLamport) {
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


	boolean checkForRacesNWC_WBR(boolean isWrite, WDCGuardState x, AccessEvent ae, ShadowThread td, CV hb, CV nwc, CV wbr, RdWrNode thisEventNode) {
		final CV nwcUnionPO = new CV(nwc);
		int tid = td.getTid();
		nwcUnionPO.set(tid, hb.get(tid));
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
					if (x.nwcWrite.anyGt(nwcUnionPO)) {
						type = RaceType.NWCRace;
						if (x.hbWrite.anyGt(hb)) {
							type = RaceType.HBRace;
						}
					} else {
						if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
					}
					int shortestRaceLamport = shortestRaceTid >= 0 ? x.lastWriteLamport[shortestRaceTid] : -1;
					if (!CAPTURE_SHORTEST_RACE || shortestRaceLamport == -1 || x.lastWriteLamport[index] > shortestRaceLamport) {
						shortestRaceTid = index;
						shortestRaceIsWrite = true;
						shortestRaceType = type;
					}
					// Keep event numbers of racing evenst in observed order even if we don't draw an edge, to avoid accidentally reordering them when they are outside the race window
					if (!DISABLE_EVENT_GRAPH) thisEventNode.eventNumber = Math.max(thisEventNode.eventNumber, x.lastWriteEvents[index].eventNode.eventNumber + 1);
					// Race edge, only for wr-rd races, and only when the racing write is the last writer of the read. Will be later drawn to a branch.
					if (!isWrite && x.lastWriteTid == index) {
						Map<ShadowVar,CVE> delayed = ts_get_wbr_delayed(td);
						CVE edge = new CVE(x.wbrWrite, x.lastWriteEvents[index].eventNode);
						if (!NO_SDG) edge = new CVED(edge, thisEventNode, ae.getAccessInfo().getLoc().getSourceLoc());
						delayed.put(x, edge);
					}
				} else {
					if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
				}
			}
		} else {
			if (VERBOSE) Assert.assertTrue(!x.nwcWrite.anyGt(nwcUnionPO));//, "VC x: " + x.nwcWrite.toString() + "| VC nwcUnionPO: " + nwcUnionPO.toString());
			if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
		}
		// Next check for races with prior reads
		if (isWrite) {
			if (x.wbrRead.anyGt(wbr)) {
				int index = -1;
				while ((index = x.wbrRead.nextGt(wbr, index + 1)) != -1) {
					if (findCommonLock(x.heldLocksRead.get(index), heldLocks) == null) {
						RaceType type = RaceType.WBRRace;
						if (x.nwcRead.get(index) > nwcUnionPO.get(index)) {
							type = RaceType.NWCRace;
							if (x.hbRead.get(index) > hb.get(index)) {
								type = RaceType.HBRace;
							}
						} else {
							if (VERBOSE) Assert.assertTrue(x.hbRead.get(index) <= hb.get(index));
						}
						// Keep event numbers of racing evenst in observed order even if we don't draw an edge, to avoid accidentally reordering them when they are outside the race window
						if (!DISABLE_EVENT_GRAPH) thisEventNode.eventNumber = Math.max(thisEventNode.eventNumber, x.lastReadEvents[index].eventNode.eventNumber + 1);
						//Update the latest race with the current race since we only want to report one race per access event
						int shortestRaceLamport = shortestRaceTid >= 0 ? (shortestRaceIsWrite ? x.lastWriteLamport[shortestRaceTid] : x.lastReadLamport[shortestRaceTid]) : -1;
						if (!CAPTURE_SHORTEST_RACE || shortestRaceLamport == -1 || x.lastWriteLamport[index] > shortestRaceLamport) {
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
						if (VERBOSE) Assert.assertTrue(!x.nwcRead.anyGt(nwcUnionPO));//, "VC x: " + x.nwcRead.toString() + "| VC nwcUnionPO: " + nwcUnionPO.toString());
						if (VERBOSE) Assert.assertTrue(!x.hbRead.anyGt(hb));
					}
				}
			} else {
				if (VERBOSE) Assert.assertTrue(!x.nwcRead.anyGt(nwcUnionPO));//, "VC x: " + x.nwcRead.toString() + "| VC nwcUnionPO: " + nwcUnionPO.toString());
				if (VERBOSE) Assert.assertTrue(!x.hbRead.anyGt(hb));
			}
		}
		return recordRace(x, ae, tid, shortestRaceTid, shortestRaceIsWrite, shortestRaceType, thisEventNode);
	}

	boolean checkForRacesuDP(boolean isWrite, WDCGuardState x, AccessEvent ae, ShadowThread td, CV hb, CV wcp, CV udp, RdWrNode thisEventNode) {
		final CV wcpUnionPO = new CV(wcp);
		int tid = td.getTid();
		wcpUnionPO.set(tid, hb.get(tid));
		int shortestRaceTid = -1;
		boolean shortestRaceIsWrite = false; // only valid if shortestRaceTid != -1
		RaceType shortestRaceType = RaceType.WBROrdered; // only valid if shortestRaceTid != -1
		Collection<ShadowLock> heldLocks = td.getLocksHeld();
		boolean racesWithLastWr = false;

		// First check for race with prior write
		if (x.udpWrite.anyGt(udp)) {
			int index = -1;
			while ((index = x.udpWrite.nextGt(udp, index + 1)) != -1) {
				if (findCommonLock(x.heldLocksWrite.get(index), heldLocks) == null) {
					RaceType type = RaceType.uDPRace;
					if (x.wcpWrite.anyGt(wcpUnionPO)) {
						type = RaceType.WCPRace;
						if (x.hbWrite.anyGt(hb)) {
							type = RaceType.HBRace;
						}
					} else {
						if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
					}
					int shortestRaceLamport = shortestRaceTid >= 0 ? x.lastWriteLamport[shortestRaceTid] : -1;
					if (!CAPTURE_SHORTEST_RACE || shortestRaceLamport == -1 || x.lastWriteLamport[index] > shortestRaceLamport) {
						shortestRaceTid = index;
						shortestRaceIsWrite = true;
						shortestRaceType = type;
					}
					// Keep event numbers of racing evenst in observed order even if we don't draw an edge, to avoid accidentally reordering them when they are outside the race window
					if (!DISABLE_EVENT_GRAPH) thisEventNode.eventNumber = Math.max(thisEventNode.eventNumber, x.lastWriteEvents[index].eventNode.eventNumber + 1);
					// Race edge, only for wr-rd races, and only when the racing write is the last writer of the read. Will be later drawn to a branch.
					if (!isWrite && x.lastWriteTid == index) {
						racesWithLastWr = true;
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
			if (x.udpRead.anyGt(udp)) {
				int index = -1;
				while ((index = x.udpRead.nextGt(udp, index + 1)) != -1) {
					if (findCommonLock(x.heldLocksRead.get(index), heldLocks) == null) {
						RaceType type = RaceType.uDPRace;
						if (x.wcpRead.get(index) > wcpUnionPO.get(index)) {
							type = RaceType.WCPRace;
							if (x.hbRead.get(index) > hb.get(index)) {
								type = RaceType.HBRace;
							}
						} else {
							if (VERBOSE) Assert.assertTrue(x.hbRead.get(index) <= hb.get(index));
						}
						// Keep event numbers of racing events in observed order even if we don't draw an edge, to avoid accidentally reordering them when they are outside the race window
						if (!DISABLE_EVENT_GRAPH) thisEventNode.eventNumber = Math.max(thisEventNode.eventNumber, x.lastReadEvents[index].eventNode.eventNumber + 1);
						//Update the latest race with the current race since we only want to report one race per access event
						int shortestRaceLamport = shortestRaceTid >= 0 ? (shortestRaceIsWrite ? x.lastWriteLamport[shortestRaceTid] : x.lastReadLamport[shortestRaceTid]) : -1;
						if (!CAPTURE_SHORTEST_RACE || shortestRaceLamport == -1 || x.lastWriteLamport[index] > shortestRaceLamport) {
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
							if (VERBOSE && x.lastWriteEvents[index] != null && x.lastReadEvents[index].eventNode == x.lastWriteEvents[index].eventNode) {
								Assert.assertTrue(EventNode.edgeExists(x.lastWriteEvents[index].eventNode, thisEventNode));
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
		if (racesWithLastWr) {
			udp.max(x.udpWrite);
			if (!DISABLE_EVENT_GRAPH) EventNode.addEdge(x.lastWriteEvents[x.lastWriteTid].eventNode, thisEventNode);
		}
		return recordRace(x, ae, tid, shortestRaceTid, shortestRaceIsWrite, shortestRaceType, thisEventNode);
	}

	boolean checkForRacesDC_WBR(boolean isWrite, WDCGuardState x, AccessEvent ae, ShadowThread td, CV wdc, CV wbr, RdWrNode thisEventNode) {
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
					}
					int shortestRaceLamport = shortestRaceTid >= 0 ? x.lastWriteLamport[shortestRaceTid] : -1;
					if (!CAPTURE_SHORTEST_RACE || shortestRaceLamport == -1 || x.lastWriteLamport[index] > shortestRaceLamport) {
						shortestRaceTid = index;
						shortestRaceIsWrite = true;
						shortestRaceType = type;
					}
					// Keep event numbers of racing evenst in observed order even if we don't draw an edge, to avoid accidentally reordering them when they are outside the race window
					if (!DISABLE_EVENT_GRAPH) thisEventNode.eventNumber = Math.max(thisEventNode.eventNumber, x.lastWriteEvents[index].eventNode.eventNumber + 1);
					// Race edge, only for wr-rd races, and only when the racing write is the last writer of the read. Will be later drawn to a branch.
					if (!isWrite && x.lastWriteTid == index) {
						Map<ShadowVar,CVE> delayed = ts_get_wbr_delayed(td);
						CVE edge = new CVE(x.wbrWrite, x.lastWriteEvents[index].eventNode);
						if (!NO_SDG) edge = new CVED(edge, thisEventNode, ae.getAccessInfo().getLoc().getSourceLoc());
						delayed.put(x, edge);
					}
				} else {
					if (VERBOSE) Assert.assertTrue(!x.wdcWrite.anyGt(wdc));
				}
			}
		} else {
			if (VERBOSE) Assert.assertTrue(!x.wdcWrite.anyGt(wdc));
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
						}
						// Keep event numbers of racing evenst in observed order even if we don't draw an edge, to avoid accidentally reordering them when they are outside the race window
						if (!DISABLE_EVENT_GRAPH) thisEventNode.eventNumber = Math.max(thisEventNode.eventNumber, x.lastReadEvents[index].eventNode.eventNumber + 1);
						//Update the latest race with the current race since we only want to report one race per access event
						int shortestRaceLamport = shortestRaceTid >= 0 ? (shortestRaceIsWrite ? x.lastWriteLamport[shortestRaceTid] : x.lastReadLamport[shortestRaceTid]) : -1;
						if (!CAPTURE_SHORTEST_RACE || shortestRaceLamport == -1 || x.lastWriteLamport[index] > shortestRaceLamport) {
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
					}
				}
			} else {
				if (VERBOSE) Assert.assertTrue(!x.wdcRead.anyGt(wdc));
			}
		}
		return recordRace(x, ae, td.getTid(), shortestRaceTid, shortestRaceIsWrite, shortestRaceType, thisEventNode);
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
					int shortestRaceLamport = shortestRaceTid >= 0 ? x.lastWriteLamport[shortestRaceTid] : -1;
					if (!CAPTURE_SHORTEST_RACE || shortestRaceLamport == -1 || x.lastWriteLamport[index] > shortestRaceLamport) {
						shortestRaceTid = index;
						shortestRaceIsWrite = true;
						shortestRaceType = type;
					}
					// Keep event numbers of racing evenst in observed order even if we don't draw an edge, to avoid accidentally reordering them when they are outside the race window
					if (!DISABLE_EVENT_GRAPH) thisEventNode.eventNumber = Math.max(thisEventNode.eventNumber, x.lastWriteEvents[index].eventNode.eventNumber + 1);
					// Race edge, only for wr-rd races, and only when the racing write is the last writer of the read. Will be later drawn to a branch.
					if (!isWrite && x.lastWriteTid == index) {
						Map<ShadowVar,CVE> delayed = ts_get_wbr_delayed(td);
						CVE edge = new CVE(x.wbrWrite, x.lastWriteEvents[index].eventNode);
						if (!NO_SDG) edge = new CVED(edge, thisEventNode, ae.getAccessInfo().getLoc().getSourceLoc());
						delayed.put(x, edge);
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
						int shortestRaceLamport = shortestRaceTid >= 0 ? (shortestRaceIsWrite ? x.lastWriteLamport[shortestRaceTid] : x.lastReadLamport[shortestRaceTid]) : -1;
						if (!CAPTURE_SHORTEST_RACE || shortestRaceLamport == -1 || x.lastWriteLamport[index] > shortestRaceLamport) {
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

	boolean checkForRacesWBRNWC(boolean isWrite, WDCGuardState x, AccessEvent ae, ShadowThread td, CV hb, CV wcp, CV nwc, CV wdc, CV wbr, RdWrNode thisEventNode) {
		final CV nwcUnionPO = new CV(nwc);
		final CV wcpUnionPO = new CV(wcp);
		int tid = td.getTid();
		nwcUnionPO.set(tid, hb.get(tid));
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
						if (x.nwcWrite.anyGt(nwcUnionPO)) {
							type = RaceType.NWCRace;
							if (x.wcpWrite.anyGt(wcpUnionPO)) {
								type = RaceType.WCPRace;
								if (x.hbWrite.anyGt(hb)) {
									type = RaceType.HBRace;
								}
							}
						} else {
							if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
						}
					} else {
						if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
					}
					int shortestRaceLamport = shortestRaceTid >= 0 ? x.lastWriteLamport[shortestRaceTid] : -1;
					if (!CAPTURE_SHORTEST_RACE || shortestRaceLamport == -1 || x.lastWriteLamport[index] > shortestRaceLamport) {
						shortestRaceTid = index;
						shortestRaceIsWrite = true;
						shortestRaceType = type;
					}
					// Keep event numbers of racing evenst in observed order even if we don't draw an edge, to avoid accidentally reordering them when they are outside the race window
					if (!DISABLE_EVENT_GRAPH) thisEventNode.eventNumber = Math.max(thisEventNode.eventNumber, x.lastWriteEvents[index].eventNode.eventNumber + 1);
					// Race edge, only for wr-rd races, and only when the racing write is the last writer of the read. Will be later drawn to a branch.
					if (!isWrite && x.lastWriteTid == index) {
						Map<ShadowVar,CVE> delayed = ts_get_wbr_delayed(td);
						CVE edge = new CVE(x.wbrWrite, x.lastWriteEvents[index].eventNode);
						if (!NO_SDG) edge = new CVED(edge, thisEventNode, ae.getAccessInfo().getLoc().getSourceLoc());
						delayed.put(x, edge);
					}
				} else {
					if (VERBOSE) Assert.assertTrue(!x.wdcWrite.anyGt(wdc));
					if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
				}
			}
		} else {
			if (VERBOSE) Assert.assertTrue(!x.wdcWrite.anyGt(wdc));
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
							if (x.nwcRead.get(index) > nwcUnionPO.get(index)) {
								type = RaceType.NWCRace;
								if (x.wcpRead.get(index) > wcpUnionPO.get(index)) {
									type = RaceType.WCPRace;
									if (x.hbRead.get(index) > hb.get(index)) {
										type = RaceType.HBRace;
									}
								}
							} else {
								if (VERBOSE) Assert.assertTrue(x.hbRead.get(index) <= hb.get(index));
							}
						}
						// Keep event numbers of racing evenst in observed order even if we don't draw an edge, to avoid accidentally reordering them when they are outside the race window
						if (!DISABLE_EVENT_GRAPH) thisEventNode.eventNumber = Math.max(thisEventNode.eventNumber, x.lastReadEvents[index].eventNode.eventNumber + 1);
						//Update the latest race with the current race since we only want to report one race per access event
						int shortestRaceLamport = shortestRaceTid >= 0 ? (shortestRaceIsWrite ? x.lastWriteLamport[shortestRaceTid] : x.lastReadLamport[shortestRaceTid]) : -1;
						if (!CAPTURE_SHORTEST_RACE || shortestRaceLamport == -1 || x.lastWriteLamport[index] > shortestRaceLamport) {
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
						if (VERBOSE) Assert.assertTrue(!x.wdcRead.anyGt(wdc));
						if (VERBOSE) Assert.assertTrue(!x.hbRead.anyGt(hb));
					}
				}
			} else {
				if (VERBOSE) Assert.assertTrue(!x.wdcRead.anyGt(wdc));
				if (VERBOSE) Assert.assertTrue(!x.hbRead.anyGt(hb));
			}
		}
		return recordRace(x, ae, tid, shortestRaceTid, shortestRaceIsWrite, shortestRaceType, thisEventNode);
	}

	boolean checkForRacesWBRNWC(boolean isWrite, WDCGuardState x, AccessEvent ae, ShadowThread td, CV hb, CV wcp, CV nwc, CV wbr, RdWrNode thisEventNode) {
		final CV nwcUnionPO = new CV(nwc);
		final CV wcpUnionPO = new CV(wcp);
		int tid = td.getTid();
		nwcUnionPO.set(tid, hb.get(tid));
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
					if (x.nwcWrite.anyGt(nwcUnionPO)) {
						type = RaceType.NWCRace;
						if (x.wcpWrite.anyGt(wcpUnionPO)) {
							type = RaceType.WCPRace;
							if (x.hbWrite.anyGt(hb)) {
								type = RaceType.HBRace;
							}
						}
					} else {
						if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
					}
					int shortestRaceLamport = shortestRaceTid >= 0 ? x.lastWriteLamport[shortestRaceTid] : -1;
					if (!CAPTURE_SHORTEST_RACE || shortestRaceLamport == -1 || x.lastWriteLamport[index] > shortestRaceLamport) {
						shortestRaceTid = index;
						shortestRaceIsWrite = true;
						shortestRaceType = type;
					}
					// Keep event numbers of racing evenst in observed order even if we don't draw an edge, to avoid accidentally reordering them when they are outside the race window
					if (!DISABLE_EVENT_GRAPH) thisEventNode.eventNumber = Math.max(thisEventNode.eventNumber, x.lastWriteEvents[index].eventNode.eventNumber + 1);
					// Race edge, only for wr-rd races, and only when the racing write is the last writer of the read. Will be later drawn to a branch.
					if (!isWrite && x.lastWriteTid == index) {
						Map<ShadowVar,CVE> delayed = ts_get_wbr_delayed(td);
						CVE edge = new CVE(x.wbrWrite, x.lastWriteEvents[index].eventNode);
						if (!NO_SDG) edge = new CVED(edge, thisEventNode, ae.getAccessInfo().getLoc().getSourceLoc());
						delayed.put(x, edge);
					}
				} else {
					if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
				}
			}
		} else {
			if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
		}
		// Next check for races with prior reads
		if (isWrite) {
			if (x.wbrRead.anyGt(wbr)) {
				int index = -1;
				while ((index = x.wbrRead.nextGt(wbr, index + 1)) != -1) {
					if (findCommonLock(x.heldLocksRead.get(index), heldLocks) == null) {
						RaceType type = RaceType.WBRRace;
						if (x.nwcRead.get(index) > nwcUnionPO.get(index)) {
							type = RaceType.NWCRace;
							if (x.wcpRead.get(index) > wcpUnionPO.get(index)) {
								type = RaceType.WCPRace;
								if (x.hbRead.get(index) > hb.get(index)) {
									type = RaceType.HBRace;
								}
							}
						}
						// Keep event numbers of racing evenst in observed order even if we don't draw an edge, to avoid accidentally reordering them when they are outside the race window
						if (!DISABLE_EVENT_GRAPH) thisEventNode.eventNumber = Math.max(thisEventNode.eventNumber, x.lastReadEvents[index].eventNode.eventNumber + 1);
						//Update the latest race with the current race since we only want to report one race per access event
						int shortestRaceLamport = shortestRaceTid >= 0 ? (shortestRaceIsWrite ? x.lastWriteLamport[shortestRaceTid] : x.lastReadLamport[shortestRaceTid]) : -1;
						if (!CAPTURE_SHORTEST_RACE || shortestRaceLamport == -1 || x.lastWriteLamport[index] > shortestRaceLamport) {
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
						if (VERBOSE) Assert.assertTrue(!x.hbRead.anyGt(hb));
					}
				}
			} else {
				if (VERBOSE) Assert.assertTrue(!x.hbRead.anyGt(hb));
			}
		}
		return recordRace(x, ae, tid, shortestRaceTid, shortestRaceIsWrite, shortestRaceType, thisEventNode);
	}

	boolean checkForRacesWBR(boolean isWrite, WDCGuardState x, AccessEvent ae, ShadowThread td, CV hb, CV wcp, CV wdc, CV udp, CV wbr, RdWrNode thisEventNode) {
		final CV wcpUnionPO = new CV(wcp);
		int tid = td.getTid();
		wcpUnionPO.set(tid, hb.get(tid));
		int shortestRaceTid = -1;
		boolean shortestRaceIsWrite = false; // only valid if shortestRaceTid != -1
		RaceType shortestRaceType = RaceType.WBROrdered; // only valid if shortestRaceTid != -1
		Collection<ShadowLock> heldLocks = td.getLocksHeld();
		boolean uDPRacesWithLastWr = false;

		// First check for race with prior write
		if (x.wbrWrite.anyGt(wbr)) {
			int index = -1;
			while ((index = x.wbrWrite.nextGt(wbr, index + 1)) != -1) {
				if (findCommonLock(x.heldLocksWrite.get(index), heldLocks) == null) {
					RaceType type = RaceType.WBRRace;
					if (x.udpWrite.get(index) > udp.get(index)) {
						type = RaceType.uDPRace;
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
						if (!isWrite && x.lastWriteTid == index) {
							uDPRacesWithLastWr = true;
						}
					}
					int shortestRaceLamport = shortestRaceTid >= 0 ? x.lastWriteLamport[shortestRaceTid] : -1;
					if (!CAPTURE_SHORTEST_RACE || shortestRaceLamport == -1 || x.lastWriteLamport[index] > shortestRaceLamport) {
						shortestRaceTid = index;
						shortestRaceIsWrite = true;
						shortestRaceType = type;
					}
					// Keep event numbers of racing evenst in observed order even if we don't draw an edge, to avoid accidentally reordering them when they are outside the race window
					if (!DISABLE_EVENT_GRAPH)
						thisEventNode.eventNumber = Math.max(thisEventNode.eventNumber, x.lastWriteEvents[index].eventNode.eventNumber + 1);
					// Race edge, only for wr-rd races, and only when the racing write is the last writer of the read. Will be later drawn to a branch.
					if (!isWrite && x.lastWriteTid == index) {
						Map<ShadowVar, CVE> delayed = ts_get_wbr_delayed(td);
						CVE edge = new CVE(x.wbrWrite, x.lastWriteEvents[index].eventNode);
						if (!NO_SDG) edge = new CVED(edge, thisEventNode, ae.getAccessInfo().getLoc().getSourceLoc());
						delayed.put(x, edge);
					}
				} else {
					if (VERBOSE) Assert.assertTrue(!x.wdcWrite.anyGt(wdc));
					if (VERBOSE)
						Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));//, "VC x: " + x.wcpWrite.toString() + "| VC wcpUnionPO: " + wcpUnionPO.toString());
					if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
				}
			}
		} else {
			if (VERBOSE) Assert.assertTrue(!x.wdcWrite.anyGt(wdc));
			if (VERBOSE)
				Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));//, "VC x: " + x.wcpWrite.toString() + "| VC wcpUnionPO: " + wcpUnionPO.toString());
			if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
		}
		// Next check for races with prior reads
		if (isWrite) {
			if (x.wbrRead.anyGt(wbr)) {
				int index = -1;
				while ((index = x.wbrRead.nextGt(wbr, index + 1)) != -1) {
					if (findCommonLock(x.heldLocksRead.get(index), heldLocks) == null) {
						RaceType type = RaceType.WBRRace;
						if (x.udpRead.get(index) > udp.get(index)) {
							type = RaceType.uDPRace;
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
						}
						// Keep event numbers of racing evenst in observed order even if we don't draw an edge, to avoid accidentally reordering them when they are outside the race window
						if (!DISABLE_EVENT_GRAPH)
							thisEventNode.eventNumber = Math.max(thisEventNode.eventNumber, x.lastReadEvents[index].eventNode.eventNumber + 1);
						//Update the latest race with the current race since we only want to report one race per access event
						int shortestRaceLamport = shortestRaceTid >= 0 ? (shortestRaceIsWrite ? x.lastWriteLamport[shortestRaceTid] : x.lastReadLamport[shortestRaceTid]) : -1;
						if (!CAPTURE_SHORTEST_RACE || shortestRaceLamport == -1 || x.lastWriteLamport[index] > shortestRaceLamport) {
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
						if (VERBOSE)
							Assert.assertTrue(!x.wcpRead.anyGt(wcpUnionPO));//, "VC x: " + x.wcpRead.toString() + "| VC wcpUnionPO: " + wcpUnionPO.toString());
						if (VERBOSE) Assert.assertTrue(!x.hbRead.anyGt(hb));
					}
				}
			} else {
				if (VERBOSE) Assert.assertTrue(!x.wdcRead.anyGt(wdc));
				if (VERBOSE)
					Assert.assertTrue(!x.wcpRead.anyGt(wcpUnionPO));//, "VC x: " + x.wcpRead.toString() + "| VC wcpUnionPO: " + wcpUnionPO.toString());
				if (VERBOSE) Assert.assertTrue(!x.hbRead.anyGt(hb));
			}
		}
		if (uDPRacesWithLastWr) {
			udp.max(x.udpWrite);
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
						// Keep event numbers of racing events in observed order even if we don't draw an edge, to avoid accidentally reordering them when they are outside the race window
						if (!DISABLE_EVENT_GRAPH)
							thisEventNode.eventNumber = Math.max(thisEventNode.eventNumber, x.lastWriteEvents[index].eventNode.eventNumber + 1);
						// Race edge, only for wr-rd races, and only when the racing write is the last writer of the read. Will be later drawn to a branch.
						if (!isWrite && x.lastWriteTid == index) {
							Map<ShadowVar, CVE> delayed = ts_get_wbr_delayed(td);
							CVE edge = new CVE(x.wbrWrite, x.lastWriteEvents[index].eventNode);
							if (!NO_SDG) edge = new CVED(edge, thisEventNode, ae.getAccessInfo().getLoc().getSourceLoc());
							delayed.put(x, edge);
						}
					} else {
						if (VERBOSE) Assert.assertTrue(!x.wdcWrite.anyGt(wdc));
						if (VERBOSE) Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));
						if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
					}
					int shortestRaceLamport = shortestRaceTid >= 0 ? x.lastWriteLamport[shortestRaceTid] : -1;
					if (!CAPTURE_SHORTEST_RACE || shortestRaceLamport == -1 || x.lastWriteLamport[index] > shortestRaceLamport) {
						shortestRaceTid = index;
						shortestRaceIsWrite = true;
						shortestRaceType = type;
					}
				} else {
					if (VERBOSE) Assert.assertTrue(!x.wdcWrite.anyGt(wdc));
					if (VERBOSE) Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));
					if (VERBOSE) Assert.assertTrue(!x.hbWrite.anyGt(hb));
				}
			}
		} else {
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
						int shortestRaceLamport = shortestRaceTid >= 0 ? (shortestRaceIsWrite ? x.lastWriteLamport[shortestRaceTid] : x.lastReadLamport[shortestRaceTid]) : -1;
						if (!CAPTURE_SHORTEST_RACE || shortestRaceLamport == -1 || x.lastWriteLamport[index] > shortestRaceLamport) {
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
						if (VERBOSE) Assert.assertTrue(!x.wdcRead.anyGt(wdc));
						if (VERBOSE) Assert.assertTrue(!x.wcpRead.anyGt(wcpUnionPO));//, "VC x: " + x.wcpRead.toString() + "| VC wcpUnionPO: " + wcpUnionPO.toString());
						if (VERBOSE) Assert.assertTrue(!x.hbRead.anyGt(hb));
					}
				}
			}  else {
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
				thisEventNode = new VolatileRdWrNode(-2, fae.isWrite(), vd, fae.getThread().getTid(), currentCriticalSection, fae.getInfo().getLoc().getFriendlySourceLoc());
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
				
				if (hasHB) {
					final CV hb = ts_get_hb(td);
					if (fae.isWrite()) {
						hb.max(vd.hbWrite);
						hb.max(vd.hbRead);
						vd.hbWrite.max(hb);
					} else { // isRead
						hb.max(vd.hbWrite);
						vd.hbRead.max(hb);
					}
				}
				if (hasWCP) {
					final CV wcp = ts_get_wcp(td);
					if (fae.isWrite()) {
						wcp.max(vd.hbWrite);
						wcp.max(vd.hbRead);
						vd.wcpWrite.max(wcp);
					} else { // isRead
						wcp.max(vd.hbWrite);
						vd.wcpRead.max(wcp);
					}
				}
				if (hasNWC) {
					final CV nwc = ts_get_nwc(td);
					if (fae.isWrite()) {
						nwc.max(vd.hbRead);
						vd.nwcWrite.max(nwc);
						ts_get_nwc_delayed(td)
								.computeIfAbsent(fae.getShadow(), x -> new CV(INIT_CV_SIZE))
								.max(nwc);
					} else { // isRead
						nwc.max(vd.hbWrite);
						// Apply any delayed wr-wr conflict edges
						CV delayed = ts_get_nwc_delayed(td).remove(fae.getShadow());
						if (delayed != null) nwc.max(delayed);
						vd.nwcRead.max(nwc);
					}
				}
				if (hasDC) {
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
				if (hasUDP) {
					final CV udp = ts_get_udp(td);
					if (fae.isWrite()) {
						vd.udp.max(udp);
						vd.udp.setEventNode(thisEventNode);
						vd.udpWrites.set(tid, udp.get(tid));
					} else { /* is read */
						if (!DISABLE_EVENT_GRAPH && vd.udp.anyGt(udp) && (uDP || WCP_uDP)) {
							EventNode.addEdge(vd.udp.eventNode, thisEventNode);
						}
						vd.udp.max(udp);
					}
				}
				if (hasWBR) {
					final CV wbr = ts_get_wbr(td);
					if (fae.isWrite()) {
						vd.wbr.max(wbr);
						vd.wbr.setEventNode(thisEventNode);
						vd.wbrWrites.set(tid, wbr.get(tid));
					} else { /* is read */
						ts_set_hasread(td, true); // Mark that we have seen a read, next branch can't be a fast path
						if (vd.wbr.anyGt(wbr)) { // Only draw an edge if it is not redundant
							ts_get_wbr_delayed(td).put(
									fae.getShadow(),
									NO_SDG ? new CVE(vd.wbr) : new CVED(vd.wbr, thisEventNode, fae.getAccessInfo().getLoc().getSourceLoc())
							);
						}
					}
				}
				if (!DISABLE_EVENT_GRAPH) {
					if (fae.isRead()) {
						if (vd.hasLastWriter()) {
							VolatileRdWrNode lastWriter = vd.lastWriteEvents[vd.lastWriter];
							thisEventNode.setLastWriter(lastWriter);
						} else {
							thisEventNode.setHasNoLastWriter();
						}
					}
				}

				// Volatile accesses can have outgoing edges, kill FP and increment to distinguish following events
				killFastPath(td);
				ts_set_lamport(td, ts_get_lamport(td) + 1);
				if (hasHB)
					ts_get_hb(td).inc(td.getTid());
				if (hasDC)
					ts_get_wdc(td).inc(td.getTid());
				if (hasUDP)
					ts_get_udp(td).inc(td.getTid());
				if (hasWBR)
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
		handleBranch(be, NO_SDG ? null : be.getInfo().getLoc());
		super.branch(be);
	}

	public void branch(ArrayAccessEvent aae) {
		handleBranch(aae, NO_SDG ? null : aae.getInfo().getLoc());
	}

	private void handleBranch(Event ev, SourceLocation branchSource) {
		if (!hasWBR) return; // Non-WBR configs don't use branch
		final String friendlySourceLoc = branchSource == null ? "" : branchSource.getFriendlySourceLoc();
		final String sourceLoc = branchSource == null ? "" : branchSource.getSourceLoc();
		ShadowThread td = ev.getThread();
		boolean hasRead = ts_get_hasread(td);
		final int tid = td.getTid();
		if (!hasRead) { // Branch fast path: no reads to process
			branchFP.inc(tid);
			return;
		}

		BranchNode thisEventNode = null;
		if (!DISABLE_EVENT_GRAPH) { //Volatiles are treated the same as non-volatile rd/wr accesses since each release does not have a corresponding acquire
			AcqRelNode currentCriticalSection = getCurrentCriticalSection(td);
			thisEventNode = new BranchNode(-2, tid, currentCriticalSection, friendlySourceLoc);
		}
		handleEvent(ev, thisEventNode);
		if (VERBOSE && !DISABLE_EVENT_GRAPH) Assert.assertTrue(thisEventNode.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));
		if (PRINT_EVENT) {
			Util.log("branch by T"+ tid +(!DISABLE_EVENT_GRAPH ? ", event count:"+thisEventNode.eventNumber : ""));
		}

		if (COUNT_EVENT) total.inc(tid);
		if (COUNT_EVENT) branch.inc(tid);
		if (COUNT_EVENT) example.inc(tid);

		Map<ShadowVar,CVE> delayed = ts_get_wbr_delayed(td);
		CV wbr = ts_get_wbr(td);

		for (Iterator<Map.Entry<ShadowVar, CVE>> iterator = delayed.entrySet().iterator(); iterator.hasNext(); ) {
			Map.Entry<ShadowVar, CVE> edge = iterator.next();
			CVE delayedEdge = edge.getValue();
			if (!delayedEdge.anyGt(wbr)) {
				// The edge is redundant (and will be redundant in the future)
				if (!NO_SDG) iterator.remove(); // For NO_SDG, we'll just drop all later
				continue;
			}
			CVED delayedEdgeD = NO_SDG ? null : (CVED) delayedEdge;
			if (NO_SDG || sdg.dependsOn(delayedEdgeD.readSourceLoc, sourceLoc, tid)) {
				wbr.max(delayedEdge);
				if (!DISABLE_EVENT_GRAPH) {
					EventNode.addEdge(delayedEdge.eventNode, thisEventNode); // release to branch
					if (!NO_SDG) thisEventNode.addDep(delayedEdgeD.readEvent);
				}
				if (!NO_SDG) iterator.remove(); // For NO_SDG, we'll just drop all later
			}
		}

		// Without SDG information, after a branch all delayed edges should have been processed
		if (NO_SDG) ts_set_wbr_delayed(td, new HashMap<>());
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
				thisEventNode = new EventNode(-2, td.getTid(), currentCriticalSection, se.getInfo().getLoc().getFriendlySourceLoc(), "start");
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
			killFastPath(td);

			if (hasWCP) { // Compute WCP before modifying HB
				ts_get_wcp(forked).max(ts_get_hb(td)); // Use HB here because this is a hard WCP edge
			}
			if (hasNWC) { // Compute NWC before modifying HB
				ts_get_nwc(forked).max(ts_get_hb(td)); // Use HB here because this is a hard NWC edge
			}
			if (hasHB) {
				final CV hb = ts_get_hb(td);
				final CV forkedHB = ts_get_hb(forked);
				forkedHB.max(hb);
				hb.inc(thisTid);
			}
			if (hasDC) {
				final CV wdc = ts_get_wdc(td);
				final CV forkedWDC = ts_get_wdc(forked);
				forkedWDC.max(wdc);
				wdc.inc(thisTid);
			}
			if (hasUDP) {
				final CV uDP = ts_get_udp(td);
				final CV forkeduDP = ts_get_udp(forked);
				forkeduDP.max(uDP);
				uDP.inc(thisTid);
			}
			if (hasWBR) {
				final CV wbr = ts_get_wbr(td);
				final CV forkedWBR = ts_get_wbr(forked);
				forkedWBR.max(wbr);
				wbr.inc(thisTid);
			}
			if (hasLSHE) {
				final CV lshe = ts_get_lshe(td);
				final CV forkedLSHE = ts_get_lshe(forked);
				forkedLSHE.max(lshe);
				lshe.inc(thisTid);
			}
			
			if (!DISABLE_EVENT_GRAPH) {
				if (hasWBR) {
					ts_set_wbr(forked, new CVE(ts_get_wbr(forked), thisEventNode)); // for generating event node graph
				} else if (hasUDP) {
					ts_set_udp(forked, new CVE(ts_get_udp(forked), thisEventNode));
				}
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
				thisEventNode = new EventNode(-2, td.getTid(), currentCriticalSection, je.getInfo().getLoc().getFriendlySourceLoc(), "join");
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
			if (hasHB) {
				ts_get_hb(td).max(ts_get_hb(joining));
			}
			if (hasWCP) {
				ts_get_wcp(td).max(ts_get_hb(joining)); // Use HB since this is a hard WCP edge
			}
			if (hasNWC) {
				ts_get_nwc(td).max(ts_get_hb(joining)); // Use HB since this is a hard NWC edge
			}
			if (hasDC) {
				ts_get_wdc(td).max(ts_get_wdc(joining));
			}
			if (hasUDP) {
				ts_get_udp(td).max(ts_get_udp(joining));
			}
			if (hasWBR) {
				ts_get_wbr(td).max(ts_get_wbr(joining));
			}
			if (hasLSHE) {
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
				thisEventNode = new AcqRelNode(-2, we.getLock(), td.getTid(), false, currentCriticalSection, we.getInfo().getLoc().getFriendlySourceLoc());
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
				thisEventNode = new AcqRelNode(-2, we.getLock(), td.getTid(), true, currentCriticalSection, we.getInfo().getLoc().getFriendlySourceLoc());
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
		return String.format("[tid=%-2d   hb=%s   wcp=%s   wdc=%s]", td.getTid(), ((HB || WCP || WCP_DC) ? ts_get_hb(td) : "N/A"), ((WCP || WCP_DC) ? ts_get_wcp(td) : "N/A"), ((DC || WCP_DC) ? ts_get_wdc(td) : "N/A"));
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
		if (hasHB) {
			ts_get_hb(currentThread).max(old);
		}
		if (hasWCP) {
			ts_get_wcp(currentThread).max(old); // Also update WCP since a barrier is basically an all-to-all hard WCP edge
		}
		if (hasNWC) {
			ts_get_nwc(currentThread).max(old); // Also update NWC since a barrier is basically an all-to-all hard NWC edge
		}
		if (hasDC) {
			ts_get_wdc(currentThread).max(old); // Updating WDC to HB seems fine at a barrier (?)
		}
		if (hasUDP) {
			ts_get_udp(currentThread).max(old);
		}
		if (hasWBR) {
			ts_get_wbr(currentThread).max(old);
		}
		if (hasLSHE) {
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
				thisEventNode = new EventNode(-2, td.getTid(), currentCriticalSection, "", "initialize");
			}
			handleEvent(e, thisEventNode);
			if (VERBOSE && !DISABLE_EVENT_GRAPH) Assert.assertTrue(thisEventNode.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));
			
			if (PRINT_EVENT) {
				Util.log("classInitialized by T"+td.getTid()+(!DISABLE_EVENT_GRAPH ? ", event count:"+thisEventNode.eventNumber : ""));
			}
			
			int tid = td.getTid();
			synchronized(classInitTime) { //Not sure what we discussed for classInit, but FT synchronizes on it so I assume the program executing does not protect accesses to classInit.
				killFastPath(td);
				if (hasHB) {
					final CV hb = ts_get_hb(td);
					classInitTime.get(e.getRRClass()).hbWrite.max(hb);
					hb.inc(tid);
				}
				if (hasWCP) {
					classInitTime.get(e.getRRClass()).wcpWrite.max(ts_get_wcp(td));
				}
				if (hasNWC) {
					classInitTime.get(e.getRRClass()).nwcWrite.max(ts_get_nwc(td));
				}
				if (hasDC) {
					final CV wdc = ts_get_wdc(td);
					classInitTime.get(e.getRRClass()).wdcWrite.max(wdc);
					wdc.inc(tid);
				}
				if (hasUDP) {
					final CV udp = ts_get_udp(td);
					classInitTime.get(e.getRRClass()).udp.max(udp);
					udp.inc(tid);
				}
				if (hasWBR) {
					final CV wbr = ts_get_wbr(td);
					classInitTime.get(e.getRRClass()).wbr.max(wbr);
					wbr.inc(tid);
				}
				if (hasLSHE) {
					final CV lshe = ts_get_lshe(td);
					classInitTime.get(e.getRRClass()).lshe.max(lshe);
					lshe.inc(tid);
				}
				if (!DISABLE_EVENT_GRAPH) {
					if (hasWBR) {
						classInitTime.get(e.getRRClass()).wbr.setEventNode(thisEventNode);
					} else if (hasUDP) {
						classInitTime.get(e.getRRClass()).udp.setEventNode(thisEventNode);
					}
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
				thisEventNode = new EventNode(-2, td.getTid(), currentCriticalSection, "", "class_accessed");
			}
			handleEvent(e, thisEventNode);
			if (VERBOSE && !DISABLE_EVENT_GRAPH) Assert.assertTrue(thisEventNode.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));
			
			if (PRINT_EVENT) {
				Util.log("classAccessed by T"+td.getTid()+(!DISABLE_EVENT_GRAPH ? ", event count:"+thisEventNode.eventNumber : ""));
			}
			
			synchronized(classInitTime) { //Not sure what we discussed for classInit, but FT synchronizes on it so I assume the program executing does not protect accesses to classInit.
				WDCVolatileData initTime = classInitTime.get(e.getRRClass());

				if (!DISABLE_EVENT_GRAPH) {
					if (hasWBR) {
						if (initTime.wbr.anyGt(ts_get_wbr(td))) {
							EventNode.addEdge(initTime.wbr.eventNode, thisEventNode);
						}
					} else if (hasUDP) {
						if (initTime.udp.anyGt(ts_get_udp(td))) {
							EventNode.addEdge(initTime.udp.eventNode, thisEventNode);
						}
					}
				}

				if (hasHB) {
					final CV hb = ts_get_hb(td);
					hb.max(initTime.hbWrite);
				}
				if (hasWCP) {
					final CV wcp = ts_get_wcp(td);
					wcp.max(initTime.hbWrite); // union with HB since this is effectively a hard WCP edge
				}
				if (hasNWC) {
					final CV nwc = ts_get_nwc(td);
					nwc.max(initTime.hbWrite); // union with HB since this is effectively a hard NWC edge
				}
				if (hasDC) {
					final CV wdc = ts_get_wdc(td);
					wdc.max(initTime.wdcWrite);
				}
				if (hasUDP) {
					final CV udp = ts_get_udp(td);
					udp.max(initTime.udp);
				}
				if (hasWBR) {
					final CV wbr = ts_get_wbr(td);
					wbr.max(initTime.wbr);
				}
				if (hasLSHE) {
					final CV lshe = ts_get_lshe(td);
					lshe.max(initTime.lshe);
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
