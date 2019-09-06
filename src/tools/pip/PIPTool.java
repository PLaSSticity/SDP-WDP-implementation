package tools.pip;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Vector;

import acme.util.Assert;
import acme.util.Util;
import acme.util.count.AggregateCounter;
import acme.util.count.ThreadLocalCounter;
import acme.util.decorations.Decoration;
import acme.util.decorations.DecorationFactory;
import acme.util.decorations.DecorationFactory.Type;
import acme.util.decorations.DefaultValue;
import acme.util.identityhash.WeakIdentityHashMap;
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
import rr.meta.OperationInfo;
import rr.state.ShadowLock;
import rr.state.ShadowThread;
import rr.state.ShadowVar;
import rr.state.ShadowVolatile;
import rr.tool.RR;
import rr.tool.Tool;
import tools.util.Epoch;
import tools.util.VectorClock;
import tools.wdc.PerThreadQueue;

@Abbrev("PIP")
public class PIPTool extends Tool implements BarrierListener<PIPBarrierState> {

	private static final boolean COUNT_EVENTS = RR.countEventOption.get();//RRMain.slowMode();
	private static final boolean COUNT_RACES = RR.countRaceOption.get();//RRMain.slowMode();
	private static final boolean PRINT_EVENTS = RR.printEventOption.get();//RRMain.slowMode();
	private static final int INIT_VECTOR_CLOCK_SIZE = 4;
	private static final boolean HB = RR.pipHBOption.get();
	private static final boolean WCP = RR.pipWCPOption.get();
	private static final boolean DC = RR.pipDCOption.get();
	private static final boolean CAPO = RR.pipCAPOOption.get();
	private static final boolean WDP = RR.pipWDPOption.get();
	
	private static final boolean FTO = RR.pipFTOOption.get();
	private static final boolean RE = RR.pipREOption.get();
	
	private static final boolean AGG = RR.pipAGGOption.get();
	
	private static final boolean VERBOSE = RR.verboseOption.get();
	public static final boolean DEBUG = RR.debugOption.get();
	
	
	// Counters for relative frequencies of each rule
	private static final ThreadLocalCounter readSameEpoch = new ThreadLocalCounter("PIP", "Read Same Epoch", RR.maxTidOption.get());
	private static final ThreadLocalCounter readSharedSameEpoch = new ThreadLocalCounter("PIP", "Read Shared Same Epoch", RR.maxTidOption.get());
	private static final ThreadLocalCounter readExclusive = new ThreadLocalCounter("PIP", "Read Exclusive", RR.maxTidOption.get());
	private static final ThreadLocalCounter readOwned = new ThreadLocalCounter("PIP", "Read Owned", RR.maxTidOption.get());
	private static final ThreadLocalCounter readShare = new ThreadLocalCounter("PIP", "Read Share", RR.maxTidOption.get());
	private static final ThreadLocalCounter readShared = new ThreadLocalCounter("PIP", "Read Shared", RR.maxTidOption.get());
	private static final ThreadLocalCounter readSharedOwned = new ThreadLocalCounter("PIP", "Read Shared Owned", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeReadError = new ThreadLocalCounter("PIP", "Write-Read Error", RR.maxTidOption.get());
	private static final ThreadLocalCounter sharedWriteReadError = new ThreadLocalCounter("PIP", "Shared Write to Read Error", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeSameEpoch = new ThreadLocalCounter("PIP", "Write Same Epoch", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeExclusive = new ThreadLocalCounter("PIP", "Write Exclusive", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeOwned = new ThreadLocalCounter("PIP", "Write Owned", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeShared = new ThreadLocalCounter("PIP", "Write Shared", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeWriteError = new ThreadLocalCounter("PIP", "Write-Write Error", RR.maxTidOption.get());
	private static final ThreadLocalCounter readWriteError = new ThreadLocalCounter("PIP", "Read-Write Error", RR.maxTidOption.get());
	private static final ThreadLocalCounter sharedWriteError = new ThreadLocalCounter("PIP", "Shared-Write Error", RR.maxTidOption.get());
	private static final ThreadLocalCounter acquire = new ThreadLocalCounter("PIP", "Acquire", RR.maxTidOption.get());
	private static final ThreadLocalCounter release = new ThreadLocalCounter("PIP", "Release", RR.maxTidOption.get());
	private static final ThreadLocalCounter fork = new ThreadLocalCounter("PIP", "Fork", RR.maxTidOption.get());
	private static final ThreadLocalCounter join = new ThreadLocalCounter("PIP", "Join", RR.maxTidOption.get());
	private static final ThreadLocalCounter barrier = new ThreadLocalCounter("PIP", "Barrier", RR.maxTidOption.get());
	private static final ThreadLocalCounter preWait = new ThreadLocalCounter("PIP", "Pre Wait", RR.maxTidOption.get());
	private static final ThreadLocalCounter postWait = new ThreadLocalCounter("PIP", "Post Wait", RR.maxTidOption.get());
	private static final ThreadLocalCounter classInit = new ThreadLocalCounter("PIP", "Class Initialized", RR.maxTidOption.get());
	private static final ThreadLocalCounter classAccess = new ThreadLocalCounter("PIP", "Class Accessed", RR.maxTidOption.get());
	private static final ThreadLocalCounter vol = new ThreadLocalCounter("PIP", "Volatile", RR.maxTidOption.get());
	
	private static final ThreadLocalCounter readFP = new ThreadLocalCounter("PIP", "Read Fast Path Taken", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeFP = new ThreadLocalCounter("PIP", "Write Fast Path Taken", RR.maxTidOption.get());
	
	private static final ThreadLocalCounter readSameEpochFP = new ThreadLocalCounter("PIP", "Read Same Epoch FP", RR.maxTidOption.get());
	private static final ThreadLocalCounter readSharedSameEpochFP = new ThreadLocalCounter("PIP", "Read Shared Same Epoch FP", RR.maxTidOption.get());
	private static final ThreadLocalCounter readExclusiveFP = new ThreadLocalCounter("PIP", "Read Exclusive FP", RR.maxTidOption.get());
	private static final ThreadLocalCounter readOwnedFP = new ThreadLocalCounter("PIP", "Read Owned FP", RR.maxTidOption.get());
	private static final ThreadLocalCounter readShareFP = new ThreadLocalCounter("PIP", "Read Share FP", RR.maxTidOption.get());
	private static final ThreadLocalCounter readSharedFP = new ThreadLocalCounter("PIP", "Read Shared FP", RR.maxTidOption.get());
	private static final ThreadLocalCounter readSharedOwnedFP = new ThreadLocalCounter("PIP", "Read Shared Owned FP", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeSameEpochFP = new ThreadLocalCounter("PIP", "Write Same Epoch FP", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeSharedSameEpochFP = new ThreadLocalCounter("PIP", "Write Shared Same Epoch FP", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeExclusiveFP = new ThreadLocalCounter("PIP", "Write Exclusive FP", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeOwnedFP = new ThreadLocalCounter("PIP", "Write Owned FP", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeSharedFP = new ThreadLocalCounter("PIP", "Write Shared FP", RR.maxTidOption.get());
	
	private static final ThreadLocalCounter writeIN = new ThreadLocalCounter("PIP", "Write accesses Inside Critical Sections", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeINFP = new ThreadLocalCounter("PIP", "Write accesses Inside Critical Sections succeeding Fast Path", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeOUT = new ThreadLocalCounter("PIP", "Write accesses Outside Critical Sections", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeOUTFP = new ThreadLocalCounter("PIP", "Write accesses Outside Critical Sections succeeding Fast Path", RR.maxTidOption.get());
	private static final ThreadLocalCounter readIN = new ThreadLocalCounter("PIP", "Read accesses Inside Critical Sections", RR.maxTidOption.get());
	private static final ThreadLocalCounter readINFP = new ThreadLocalCounter("PIP", "Read accesses Inside Critical Sections succeeding Fast Path", RR.maxTidOption.get());
	private static final ThreadLocalCounter readOUT = new ThreadLocalCounter("PIP", "Read accesses Outside Critical Sections", RR.maxTidOption.get());
	private static final ThreadLocalCounter readOUTFP = new ThreadLocalCounter("PIP", "Read accesses Outside Critical Sections succeeding Fast Path", RR.maxTidOption.get());
	
	private static final ThreadLocalCounter other = new ThreadLocalCounter("PIP", "Other", RR.maxTidOption.get());
	
	//Nested Locks Held during Rd/Wr Event Counts
	private static final ThreadLocalCounter holdLocks = new ThreadLocalCounter("PIP", "Holding Lock during Access Event", RR.maxTidOption.get());
	private static final ThreadLocalCounter oneLockHeld = new ThreadLocalCounter("PIP", "One Lock Held", RR.maxTidOption.get());
	private static final ThreadLocalCounter twoNestedLocksHeld = new ThreadLocalCounter("PIP", "Two Nested Locks Held", RR.maxTidOption.get());
	private static final ThreadLocalCounter threeNestedLocksHeld = new ThreadLocalCounter("PIP", "Three Nested Locks Held", RR.maxTidOption.get());
	private static final ThreadLocalCounter fourNestedLocksHeld = new ThreadLocalCounter("PIP", "Four Nested Locks Held", RR.maxTidOption.get());
	private static final ThreadLocalCounter fiveNestedLocksHeld = new ThreadLocalCounter("PIP", "Five Nested Locks Held", RR.maxTidOption.get());
	private static final ThreadLocalCounter sixNestedLocksHeld = new ThreadLocalCounter("PIP", "Six Nested Locks Held", RR.maxTidOption.get());
	private static final ThreadLocalCounter sevenNestedLocksHeld = new ThreadLocalCounter("PIP", "Seven Nested Locks Held", RR.maxTidOption.get());
	private static final ThreadLocalCounter eightNestedLocksHeld = new ThreadLocalCounter("PIP", "Eight Nested Locks Held", RR.maxTidOption.get());
	private static final ThreadLocalCounter nineNestedLocksHeld = new ThreadLocalCounter("PIP", "Nine Nested Locks Held", RR.maxTidOption.get());
	private static final ThreadLocalCounter tenNestedLocksHeld = new ThreadLocalCounter("PIP", "Ten Nested Locks Held", RR.maxTidOption.get());
	private static final ThreadLocalCounter hundredNestedLocksHeld = new ThreadLocalCounter("PIP", "Hundred Nested Locks Held", RR.maxTidOption.get());
	private static final ThreadLocalCounter manyNestedLocksHeld = new ThreadLocalCounter("PIP", "More than two Nested Locks Held", RR.maxTidOption.get()); 
	
	//readRuleA = line 8 | writeWRRuleA = line 15 | writeRDRuleA = line 17
	private static final ThreadLocalCounter readRuleASu = new ThreadLocalCounter("PIP", "Read Rule A Succeed", RR.maxTidOption.get());
	private static final ThreadLocalCounter readRuleATo = new ThreadLocalCounter("PIP", "Read Rule A Total Attempts", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeWRRuleASu = new ThreadLocalCounter("PIP", "Write Write Rule A Succeed", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeWRRuleATo = new ThreadLocalCounter("PIP", "Write Write Rule A Total Attempts", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeRDRuleASu = new ThreadLocalCounter("PIP", "Write Read Rule A Succeed", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeRDRuleATo = new ThreadLocalCounter("PIP", "Write Read Rule A Total Attempts", RR.maxTidOption.get());
	
	//CAPO data structure data
	private static final ThreadLocalCounter clearTotal = new ThreadLocalCounter("PIP", "Clears by CAPO", RR.maxTidOption.get());
	private static final ThreadLocalCounter read0 = new ThreadLocalCounter("PIP", "Read Set Size 0", RR.maxTidOption.get());
	private static final ThreadLocalCounter read1 = new ThreadLocalCounter("PIP", "Read Set Size 1", RR.maxTidOption.get());
	private static final ThreadLocalCounter readMore = new ThreadLocalCounter("PIP", "Read Set Size Gt 1", RR.maxTidOption.get());
	private static final ThreadLocalCounter write0 = new ThreadLocalCounter("PIP", "Write Set Size 0", RR.maxTidOption.get());
	private static final ThreadLocalCounter write1 = new ThreadLocalCounter("PIP", "Write Set Size 1", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeMore = new ThreadLocalCounter("PIP", "Write Set Size Gt 1", RR.maxTidOption.get());
	private static final ThreadLocalCounter readMap0 = new ThreadLocalCounter("PIP", "Read Map Size 0", RR.maxTidOption.get());
	private static final ThreadLocalCounter readMap1 = new ThreadLocalCounter("PIP", "Read Map Size 1", RR.maxTidOption.get());
	private static final ThreadLocalCounter readMap10 = new ThreadLocalCounter("PIP", "Read Map Size 10", RR.maxTidOption.get());
	private static final ThreadLocalCounter readMap100 = new ThreadLocalCounter("PIP", "Read Map Size 100", RR.maxTidOption.get());
	private static final ThreadLocalCounter readMap1000 = new ThreadLocalCounter("PIP", "Read Map Size 1000", RR.maxTidOption.get());
	private static final ThreadLocalCounter readMapMore = new ThreadLocalCounter("PIP", "Read Map Size Gt 1000", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeMap0 = new ThreadLocalCounter("PIP", "Write Map Size 0", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeMap1 = new ThreadLocalCounter("PIP", "Write Map Size 1", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeMap10 = new ThreadLocalCounter("PIP", "Write Map Size 10", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeMap100 = new ThreadLocalCounter("PIP", "Write Map Size 100", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeMap1000 = new ThreadLocalCounter("PIP", "Write Map Size 1000", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeMapMore = new ThreadLocalCounter("PIP", "Write Map Size Gt 1000", RR.maxTidOption.get());
	
	static {
		AggregateCounter reads = new AggregateCounter("PIP", "Total Reads", readSameEpoch, readSharedSameEpoch, readExclusive, readShare, readShared, writeReadError);
		AggregateCounter writes = new AggregateCounter("PIP", "Total Writes", writeSameEpoch, writeExclusive, writeShared, writeWriteError, readWriteError, sharedWriteError);
		AggregateCounter accesses = new AggregateCounter("PIP", "Total Access Ops", reads, writes);
		new AggregateCounter("PIP", "Total Ops", accesses, acquire, release, fork, join, barrier, preWait, postWait, classInit, classAccess, vol, other);
		new AggregateCounter("PIP", "Total Fast Path Taken", readFP, writeFP);
	}
	
	public final ErrorMessage<FieldInfo> fieldErrors = ErrorMessages.makeFieldErrorMessage("PIP");
	public final ErrorMessage<ArrayAccessInfo> arrayErrors = ErrorMessages.makeArrayErrorMessage("PIP");
	private final VectorClock maxEpochPerTid = new VectorClock(INIT_VECTOR_CLOCK_SIZE);
	
	public static final Decoration<ClassInfo, VectorClock> classInitTime = MetaDataInfoMaps.getClasses().makeDecoration("PIP:ClassInitTime", Type.MULTIPLE,
			new DefaultValue<ClassInfo, VectorClock>() {
		public VectorClock get(ClassInfo st) {
			return new VectorClock(INIT_VECTOR_CLOCK_SIZE);
		}
	});
	
	public static final Decoration<ClassInfo, VectorClock> classInitTimeWCPHB = MetaDataInfoMaps.getClasses().makeDecoration("PIPwcphb:ClassInitTime", Type.MULTIPLE,
			new DefaultValue<ClassInfo, VectorClock>() {
		public VectorClock get(ClassInfo st) {
			return new VectorClock(INIT_VECTOR_CLOCK_SIZE);
		}
	});
	
	public PIPTool(final String name, final Tool next, CommandLine commandLine) {
		super(name, next, commandLine);
		new BarrierMonitor<PIPBarrierState>(this, new DefaultValue<Object,PIPBarrierState>() {
			public PIPBarrierState get(Object k) {
				return new PIPBarrierState(k, INIT_VECTOR_CLOCK_SIZE);
			}
		});
		//Remove error reporting limit for comparison with PIP tools
		fieldErrors.setMax(Integer.MAX_VALUE);
		arrayErrors.setMax(Integer.MAX_VALUE);
	}
	
	//HB
	protected static int/*epoch*/ ts_get_eHB(ShadowThread st) { Assert.panic("Bad"); return -1; }
	protected static void ts_set_eHB(ShadowThread st, int/*epoch*/ e) { Assert.panic("Bad"); }
	
	protected static VectorClock ts_get_vHB(ShadowThread st) { Assert.panic("Bad"); return null; }
	protected static void ts_set_vHB(ShadowThread st, VectorClock V) { Assert.panic("Bad"); }
	
	//WCP
	protected static int/*epoch*/ ts_get_eWCP(ShadowThread st) { Assert.panic("Bad"); return -1; }
	protected static void ts_set_eWCP(ShadowThread st, int/*epoch*/ e) { Assert.panic("Bad"); }
	
	protected static VectorClock ts_get_vWCP(ShadowThread st) { Assert.panic("Bad"); return null; }
	protected static void ts_set_vWCP(ShadowThread st, VectorClock V) { Assert.panic("Bad"); }
	
	protected static HeldLS ts_get_heldlsWCPRE(ShadowThread st) { Assert.panic("Bad"); return null; }
	protected static void ts_set_heldlsWCPRE(ShadowThread st, HeldLS heldLS) { Assert.panic("Bad"); }
	
	//DC
	protected static int/*epoch*/ ts_get_eDC(ShadowThread st) { Assert.panic("Bad"); return -1; }
	protected static void ts_set_eDC(ShadowThread st, int/*epoch*/ e) { Assert.panic("Bad"); }
	
	protected static VectorClock ts_get_vDC(ShadowThread st) { Assert.panic("Bad"); return null; }
	protected static void ts_set_vDC(ShadowThread st, VectorClock V) { Assert.panic("Bad"); }
	
	protected static HeldLS ts_get_heldlsDCRE(ShadowThread st) { Assert.panic("Bad"); return null; }
	protected static void ts_set_heldlsDCRE(ShadowThread st, HeldLS heldLS) { Assert.panic("Bad"); }

	//WDP
	protected static int/*epoch*/ ts_get_eWDP(ShadowThread st) { Assert.panic("Bad"); return -1; }
	protected static void ts_set_eWDP(ShadowThread st, int/*epoch*/ e) { Assert.panic("Bad"); }

	protected static VectorClock ts_get_vWDP(ShadowThread st) { Assert.panic("Bad"); return null; }
	protected static void ts_set_vWDP(ShadowThread st, VectorClock V) { Assert.panic("Bad"); }

	protected static VectorClock ts_get_bWDP(ShadowThread st) { Assert.panic("Bad"); return null; }
	protected static void ts_set_bWDP(ShadowThread st, VectorClock V) { Assert.panic("Bad"); }

	protected static boolean ts_get_beWDP(ShadowThread st) { Assert.panic("Bad"); return false; }
	protected static void ts_set_beWDP(ShadowThread st, boolean V) { Assert.panic("Bad"); }

	protected static HeldLS ts_get_heldlsWDPRE(ShadowThread st) { Assert.panic("Bad"); return null; }
	protected static void ts_set_heldlsWDPRE(ShadowThread st, HeldLS heldLS) { Assert.panic("Bad"); }


	//CAPO
	protected static int/*epoch*/ ts_get_eCAPO(ShadowThread st) { Assert.panic("Bad"); return -1; }
	protected static void ts_set_eCAPO(ShadowThread st, int/*epoch*/ e) { Assert.panic("Bad"); }
	
	protected static VectorClock ts_get_vCAPO(ShadowThread st) { Assert.panic("Bad"); return null; }
	protected static void ts_set_vCAPO(ShadowThread st, VectorClock V) { Assert.panic("Bad"); }
	
	protected static HeldLS ts_get_heldlsCAPORE(ShadowThread st) { Assert.panic("Bad"); return null; }
	protected static void ts_set_heldlsCAPORE(ShadowThread st, HeldLS heldLS) { Assert.panic("Bad"); }
	
	//AGG
	protected static int/*epoch*/ ts_get_eAGG(ShadowThread st) { Assert.panic("Bad"); return -1; }
	protected static void ts_set_eAGG(ShadowThread st, int/*epoch*/ e) { Assert.panic("Bad"); }
	
	protected static VectorClock ts_get_vAGG(ShadowThread st) { Assert.panic("Bad"); return null; }
	protected static void ts_set_vAGG(ShadowThread st, VectorClock V) { Assert.panic("Bad"); }
	
	protected static HeldLS ts_get_heldlsAGG(ShadowThread st) { Assert.panic("Bad"); return null; }
	protected static void ts_set_heldlsAGG(ShadowThread st, HeldLS heldLS) { Assert.panic("Bad"); }


	static final HeldLS getHLS(final ShadowThread td) {
		if (WCP && RE) {
			return ts_get_heldlsWCPRE(td);
		}
		if (DC && RE) {
			return ts_get_heldlsDCRE(td);
		}
		if (WDP && RE) {
			return ts_get_heldlsWDPRE(td);
		}
		if (CAPO && RE) {
			return ts_get_heldlsCAPORE(td);
		}
		if (AGG) {
			return ts_get_heldlsAGG(td);
		}
		Assert.assertTrue(false); //Should never reach here
		return null;
	}
	
	static final void setHLS(final ShadowThread td, final HeldLS heldLS) {
		if (WCP && RE) {
			ts_set_heldlsWCPRE(td, heldLS);
		}
		if (DC && RE) {
			ts_set_heldlsDCRE(td, heldLS);
		}
		if (WDP && RE) {
			ts_set_heldlsWDPRE(td, heldLS);
		}
		if (CAPO && RE) {
			ts_set_heldlsCAPORE(td, heldLS);
		}
		if (AGG) {
			ts_set_heldlsAGG(td, heldLS);
		}
	}
	
	static final VectorClock getV(final ShadowThread td) {
		if (HB) {
			return ts_get_vHB(td);
		}
		if (WCP) {
			return ts_get_vWCP(td);
		}
		if (DC) {
			return ts_get_vDC(td);
		}
		if (WDP) {
			return ts_get_vWDP(td);
		}
		if (CAPO) {
			return ts_get_vCAPO(td);
		}
		if (AGG) {
			return ts_get_vAGG(td);
		}
		Assert.assertTrue(false); //Should never reach here
		return null;
	}
	
	static final void setV(final ShadowThread td, final VectorClock V) {
		if (HB) {
			ts_set_vHB(td, V);
		}
		if (WCP) {
			ts_set_vWCP(td, V);
		}
		if (DC) {
			ts_set_vDC(td, V);
		}
		if (WDP) {
			ts_set_vWDP(td, V);
		}
		if (CAPO) {
			ts_set_vCAPO(td, V);
		}
		if (AGG) {
			ts_set_vAGG(td, V);
		}
	}
	
	static final int/*epoch*/ getE(final ShadowThread td) {
		if (HB) {
			return ts_get_eHB(td);
		}
		if (WCP) {
			return ts_get_eWCP(td);
		}
		if (DC) {
			return ts_get_eDC(td);
		}
		if (WDP) {
			return ts_get_eWDP(td);
		}
		if (CAPO) {
			return ts_get_eCAPO(td);
		}
		if (AGG) {
			return ts_get_eAGG(td);
		}
		Assert.assertTrue(false); //Should never reach here
		return -1;
	}
	
	static final void setE(final ShadowThread td, final int/*epoch*/ e) {
		if (HB) {
			ts_set_eHB(td, e);
		}
		if (WCP) {
			ts_set_eWCP(td, e);
		}
		if (DC) {
			ts_set_eDC(td, e);
		}
		if (WDP) {
			ts_set_eWDP(td, e);
		}
		if (CAPO) {
			ts_set_eCAPO(td, e);
		}
		if (AGG) {
			ts_set_eAGG(td, e);
		}
	}
	
	static final Decoration<ShadowLock,LockState> lockVhb = ShadowLock.makeDecoration("PIPhb:ShadowLock", DecorationFactory.Type.MULTIPLE, 
			new DefaultValue<ShadowLock,LockState>() { public LockState get(final ShadowLock lock) { return new LockState(lock, INIT_VECTOR_CLOCK_SIZE); }});
	
	static final Decoration<ShadowLock,WCPLockState> lockVwcp = ShadowLock.makeDecoration("PIPwcp:ShadowLock", DecorationFactory.Type.MULTIPLE, 
			new DefaultValue<ShadowLock,WCPLockState>() { public WCPLockState get(final ShadowLock lock) { return new WCPLockState(lock, INIT_VECTOR_CLOCK_SIZE); }});
	
	static final Decoration<ShadowLock,WCPRELockState> lockVwcpRE = ShadowLock.makeDecoration("PIPwcpRE:ShadowLock", DecorationFactory.Type.MULTIPLE, 
			new DefaultValue<ShadowLock,WCPRELockState>() { public WCPRELockState get(final ShadowLock lock) { return new WCPRELockState(lock, INIT_VECTOR_CLOCK_SIZE); }});
	
	static final Decoration<ShadowLock,DCLockState> lockVdc = ShadowLock.makeDecoration("PIPdc:ShadowLock", DecorationFactory.Type.MULTIPLE, 
			new DefaultValue<ShadowLock,DCLockState>() { public DCLockState get(final ShadowLock lock) { return new DCLockState(lock, INIT_VECTOR_CLOCK_SIZE); }});
	
	static final Decoration<ShadowLock,DCRELockState> lockVdcRE = ShadowLock.makeDecoration("PIPdcRE:ShadowLock", DecorationFactory.Type.MULTIPLE, 
			new DefaultValue<ShadowLock,DCRELockState>() { public DCRELockState get(final ShadowLock lock) { return new DCRELockState(lock, INIT_VECTOR_CLOCK_SIZE); }});

	static final Decoration<ShadowLock,WDPLockState> lockVwdpRE = ShadowLock.makeDecoration("PIPwdp:ShadowLock", DecorationFactory.Type.MULTIPLE,
			new DefaultValue<ShadowLock,WDPLockState>() { public WDPLockState get(final ShadowLock lock) { return new WDPLockState(lock, INIT_VECTOR_CLOCK_SIZE); }});
	
	static final Decoration<ShadowLock,CAPOLockState> lockVcapo = ShadowLock.makeDecoration("PIPCAPO:ShadowLock", DecorationFactory.Type.MULTIPLE, 
			new DefaultValue<ShadowLock,CAPOLockState>() { public CAPOLockState get(final ShadowLock lock) { return new CAPOLockState(lock, INIT_VECTOR_CLOCK_SIZE); }});
	
	static final Decoration<ShadowLock,CAPORELockState> lockVcapoRE = ShadowLock.makeDecoration("PIPCAPORE:ShadowLock", DecorationFactory.Type.MULTIPLE, 
			new DefaultValue<ShadowLock,CAPORELockState>() { public CAPORELockState get(final ShadowLock lock) { return new CAPORELockState(lock, INIT_VECTOR_CLOCK_SIZE); }});
	
	static final LockState getV(final ShadowLock ld) {
		if (HB) {
			return lockVhb.get(ld);
		}
		if (WCP) {
			if (RE)	return lockVwcpRE.get(ld);
			if (!RE) return lockVwcp.get(ld);
		}
		if (DC) {
			if (RE) return lockVdcRE.get(ld);
			if (!RE) return lockVdc.get(ld);
		}
		if (WDP && RE) {
			return lockVwdpRE.get(ld);
		}
		if (CAPO) {
			if (RE) return lockVcapoRE.get(ld);
			if (!RE) return lockVcapo.get(ld);
		}
		if (AGG) {
			return lockVcapoRE.get(ld);
		}
		Assert.assertTrue(false); //Should never get here
		return null;
	}
	
	static final Decoration<ShadowVolatile,PIPVolatileState> volatileV = ShadowVolatile.makeDecoration("PIP:shadowVolatile", DecorationFactory.Type.MULTIPLE,
			new DefaultValue<ShadowVolatile,PIPVolatileState>() { public PIPVolatileState get(final ShadowVolatile vol) { return new PIPVolatileState(vol, INIT_VECTOR_CLOCK_SIZE); }});
	
	static final Decoration<ShadowVolatile,PIPVolatileState> volatileVwcpHB = ShadowVolatile.makeDecoration("PIPwcpHB:shadowVolatile", DecorationFactory.Type.MULTIPLE,
			new DefaultValue<ShadowVolatile,PIPVolatileState>() { public PIPVolatileState get(final ShadowVolatile vol) { return new PIPVolatileState(vol, INIT_VECTOR_CLOCK_SIZE); }});
	
	static final PIPVolatileState getV(final ShadowVolatile ld) {
		return volatileV.get(ld);
	}

	public static boolean hasCommon(HeldLS curr, HeldLS other) {
		while (curr != null && other != null) {
			if (curr.lock == other.lock) return true;
			curr = curr.next;
			other = other.next;
		}
		return false;
	}

	public static boolean csCheck(ShadowThread td, HeldLS curr, HeldLS other) {
		while (curr != null && other != null) {
			if (curr.lock == other.lock) {
				if (DEBUG) other.vc.assertNoMax();
				ts_get_bWDP(td).max(other.vc);
				ts_set_beWDP(td, true);
				return true;
			}
			curr = curr.next;
			other = other.next;
		}
		return false;
	}

	@Override
	public ShadowVar makeShadowVar(final AccessEvent event) {
		if (event.getKind() == Kind.VOLATILE) {
			final ShadowThread st = event.getThread();
			final VectorClock volV = getV(((VolatileAccessEvent)event).getShadowVolatile());
			volV.max(getV(st));
			if (WCP) {
				final VectorClock volVhb = volatileVwcpHB.get(((VolatileAccessEvent)event).getShadowVolatile());
				volVhb.max(ts_get_vHB(st));
			}
			return super.makeShadowVar(event);
		} else {
			if ((WCP && RE) || (DC && RE) || (CAPO && RE) || AGG) {
				REVarState x;
				if (WCP) {
					x = new REVarState(event.isWrite(), ts_get_eHB(event.getThread()), FTO);
				} else {
					x = new REVarState(event.isWrite(), getE(event.getThread()), FTO);
				}
				//Update Rule(a) metadata
				final ShadowThread st = event.getThread();
				if (event.isWrite()) x.Wm = getHLS(st);
				x.Rm = getHLS(st);
				return x;
			} else if (WDP && RE) {
				WDPVarState x = new WDPVarState(event.isWrite(), getE(event.getThread()), getHLS(event.getThread()));
				//Update Rule(a) metadata
				final ShadowThread st = event.getThread();
				if (event.isWrite()) x.Wm = getHLS(st);
				else x.Rm = getHLS(st);
				return x;
			} else {
				PIPVarState sx;
				if (WCP) {
					sx = new PIPVarState(event.isWrite(), ts_get_eHB(event.getThread()), FTO);
				} else {
					sx = new PIPVarState(event.isWrite(), getE(event.getThread()), FTO);
				}
				if (FTO && !HB) { //Rule (a) [so HB should skip this]
					if (DEBUG) Assert.assertTrue(!RE);
					ShadowThread td = event.getThread();
					for (int i = 0; i < td.getNumLocksHeld(); i++) { //outer most to inner most
						ShadowLock lock = td.getHeldLock(i);
						if (WCP) {
							if (DEBUG) Assert.assertTrue(getV(lock) instanceof WCPLockState);
							WCPLockState lockData = (WCPLockState)getV(lock);
							//Update write/read Vars
							if (event.isWrite()) lockData.writeVars.add(sx);
							lockData.readVars.add(sx);
						}
						if (DC) {
							if (DEBUG) Assert.assertTrue(getV(lock) instanceof DCLockState);
							DCLockState lockData = (DCLockState)getV(lock);
							//Update write/read Vars
							if (event.isWrite()) lockData.writeVars.add(sx);
							lockData.readVars.add(sx);
						}
						if (CAPO) {
							if (DEBUG) Assert.assertTrue(getV(lock) instanceof CAPOLockState);
							CAPOLockState lockData = (CAPOLockState)getV(lock);
							//Update write/read Vars
							if (event.isWrite()) lockData.writeVars.add(sx);
							lockData.readVars.add(sx);
						}
					}
				}
				return sx;
			}
		}
	}
	
	@Override
	public void create(NewThreadEvent event) {
		final ShadowThread td = event.getThread();
		if (!WCP) {
			if (getV(td) == null) {
				final int tid = td.getTid();
				final VectorClock tV = new VectorClock(INIT_VECTOR_CLOCK_SIZE);
				setV(td, tV);
				if (WDP) {
					ts_set_bWDP(td, new VectorClock(INIT_VECTOR_CLOCK_SIZE));
					ts_set_beWDP(td, false);
				}
				synchronized(maxEpochPerTid) {
					final int/*epoch*/ epoch = maxEpochPerTid.get(tid) + 1;
					tV.set(tid, epoch);
					setE(td, epoch);
				}
				incEpochAndCV(td);
				if (PRINT_EVENTS) Util.log("Initial Epoch for " + tid + ": " + Epoch.toString(getE(td)));
			}
			setHLS(td, null);
		}
		if (WCP) { //WCP needs to track HB for left and right composition
			if (ts_get_vHB(td) == null) {
				final int tid = td.getTid();
				final VectorClock tVHB = new VectorClock(INIT_VECTOR_CLOCK_SIZE);
				ts_set_vHB(td, tVHB);
				synchronized(maxEpochPerTid) {
					final int/*epoch*/ epoch = maxEpochPerTid.get(tid) + 1;
					tVHB.set(tid, epoch);
					ts_set_eHB(td, epoch);
				}
				tVHB.tick(tid);
				ts_set_eHB(td, tVHB.get(tid));
			}
			
			if (getV(td) == null) {	
				final int tid = td.getTid();
				final VectorClock tV = new VectorClock(INIT_VECTOR_CLOCK_SIZE);
				ts_set_vWCP(td, tV);
				synchronized(maxEpochPerTid) {
					final int/*epoch*/ epoch = maxEpochPerTid.get(tid) + 1;
					tV.set(tid, epoch);
					ts_set_eWCP(td, epoch);
				}
				if (PRINT_EVENTS) Util.log("Initial Epoch for " + tid + ": " + Epoch.toString(ts_get_eHB(td)));
			}
			setHLS(td, null);
		}
		super.create(event);
	}
	
	//TODO: remove this after debugging
	@Override
	public void init() {
		if (COUNT_EVENTS) {
			Util.log("HB analysis: " + HB);
			Util.log("WCP analysis: " + WCP);
			Util.log("DC analysis: " + DC);
			Util.log("WDP analysis: " + WDP);
			Util.log("CAPO analysis: " + CAPO);
			Util.log("CAPO+RE Aggressive analysis: " + AGG);
			Util.log("FTO enabled: " + FTO);
			Util.log("RE enabled: " + RE);
		}
		if (AGG || RE) {
			Assert.assertTrue(FTO);
		}
	}
	
	@Override
	public void fini() {
		StaticRace.reportRaces();
	}
	
//	@Override
//	public void get(ShadowThread tdFuture, ShadowThread tdMain) {
//		//instrument futures
//		super.get(tdFuture, tdMain);
//	}
	
//	@Override
//	public void exit(MethodEvent event) {
//		
//		super.exit(event);
//	}
	
	protected void maxAndIncEpochAndCV(ShadowThread st, VectorClock other) {
		final int tid = st.getTid();
		final VectorClock tV = getV(st);
		tV.max(other);
		tV.tick(tid);
		setE(st, tV.get(tid));
	}
	
	protected static void maxEpochAndCV(ShadowThread st, VectorClock other) {
		final int tid = st.getTid();
		final VectorClock tV = getV(st);
		tV.max(other);
		setE(st, tV.get(tid));
	}
	
	protected void incEpochAndCV(ShadowThread st) {
		final int tid = st.getTid();
		final VectorClock tV = getV(st);
		tV.tick(tid);
		setE(st, tV.get(tid));
	}
	
	@Override
	public void acquire(final AcquireEvent event) {
		if (COUNT_EVENTS) acquire.inc(event.getThread().getTid());
		
		final ShadowThread td = event.getThread();

		if (HB || WCP || DC || WDP || (CAPO && RE) || AGG) {
			final ShadowLock lock = event.getLock();
			handleAcquire(td, lock, event.getInfo());
		} else {
			incAtAcquire(td);
		}
		
		if (PRINT_EVENTS) Util.log("acq("+event.getLock()+") by T"+event.getThread().getTid());
		super.acquire(event);
	}
	
	public void incAtAcquire(ShadowThread td) {
		// Increment first to distinguish accesses outside critical sections from accesses inside critical sections
		if (!HB && !WCP) {
			incEpochAndCV(td);
		}
		//Jake: Usually HB does not increment at acquires, but to prevent accesses
		//before and after acquires from being same-epoch HB increments here and then wcp union po
		//should not take the fast path.
		if (WCP) {
			ts_get_vHB(td).tick(td.getTid());
			ts_set_eHB(td, ts_get_vHB(td).get(td.getTid()));
		}
	}
	
	public void handleAcquire(ShadowThread td, ShadowLock lock, OperationInfo info) {
		final LockState lockV = getV(lock);
		
		if (HB) {
			maxEpochAndCV(td, lockV);
		}
		if (WCP) {
			if (RE) {
				ts_get_vHB(td).max(((WCPRELockState)lockV).hb);
				ts_set_eHB(td, ts_get_vHB(td).get(td.getTid()));
				maxEpochAndCV(td, lockV);
			}
			if (!RE) {
				ts_get_vHB(td).max(((WCPLockState)lockV).hb);
				ts_set_eHB(td, ts_get_vHB(td).get(td.getTid()));
				maxEpochAndCV(td, lockV);
			}
		}
		
		if (WCP) {
			if (RE) {
				if (DEBUG) Assert.assertTrue(lockV instanceof WCPRELockState);
				VectorClock wcpUnionPO = new VectorClock(getV(td));
				wcpUnionPO.set(td.getTid(), ts_get_eHB(td));
				//Rule (b)
				WCPRELockState lockData = (WCPRELockState) lockV;
				for (ShadowThread tdOther : ShadowThread.getThreads()) {
					ArrayDeque<VectorClock> queue = lockData.AcqQueueMap.get(tdOther);
					if (queue == null) {
						queue = lockData.AcqQueueGlobal.clone();
						lockData.AcqQueueMap.put(tdOther, queue);
					}
					if (tdOther != td) {
						queue.addLast(wcpUnionPO);
					}
				}
				lockData.AcqQueueGlobal.addLast(wcpUnionPO);
				ArrayDeque<VectorClock> queue = lockData.RelQueueMap.get(td);
				if (queue == null) {
					queue = lockData.RelQueueGlobal.clone();
					lockData.RelQueueMap.put(td, queue);
				}
				//Rule(a)
				//create a new vector clock C_m
				lockData.Cm = new VectorClock(INIT_VECTOR_CLOCK_SIZE);
				lockData.Cm.set(td.getTid(), Epoch.make(td.getTid(), Epoch.MAX_CLOCK)); //To indicate shallow copy Cm has not been set by release event yet
				if (PRINT_EVENTS) Util.log(lockData.Cm.toString());
				//Update last Rule(a) metadata
				//Build a new list of held locks
				WCPRELockState outerMostLock = (WCPRELockState)getV(td.getHeldLock(0));
				HeldLS thrLock = new HeldLS(td.getHeldLock(0), outerMostLock.Cm); //Outer most lock held
				setHLS(td, thrLock);
				for (int i = 1; i < td.getNumLocksHeld(); i++) {
					ShadowLock heldLock = td.getHeldLock(i);
					WCPRELockState heldLockData = (WCPRELockState)getV(heldLock);
					//Store a shallow reference to Cm: from outer most to inner most lock held
					thrLock.next = new HeldLS(heldLock, heldLockData.Cm);
					thrLock = thrLock.next;
				}
			}
			if (!RE) {
				VectorClock wcpUnionPO = new VectorClock(getV(td));
				wcpUnionPO.set(td.getTid(), ts_get_eHB(td));
				//Rule (b)
				WCPLockState lockData = (WCPLockState) lockV;
				for (ShadowThread tdOther : ShadowThread.getThreads()) {
					ArrayDeque<VectorClock> queue = lockData.AcqQueueMap.get(tdOther);
					if (queue == null) {
						queue = lockData.AcqQueueGlobal.clone();
						lockData.AcqQueueMap.put(tdOther, queue);
					}
					if (tdOther != td) {
						queue.addLast(wcpUnionPO);
					}
				}
				lockData.AcqQueueGlobal.addLast(wcpUnionPO);
				ArrayDeque<VectorClock> queue = lockData.RelQueueMap.get(td);
				if (queue == null) {
					queue = lockData.RelQueueGlobal.clone();
					lockData.RelQueueMap.put(td, queue);
				}
			}
		}
		if (DC) {
			if (RE) {
				//Rule (b)
				VectorClock copyDC = new VectorClock(getV(td));
				if (DEBUG) Assert.assertTrue(lockV instanceof DCRELockState);
				DCRELockState lockData = (DCRELockState) lockV;
				for (ShadowThread tdOther : ShadowThread.getThreads()) {
					PerThreadQueue<VectorClock> queue = lockData.AcqQueueMap.get(tdOther);
					if (queue == null) {
						queue = lockData.AcqQueueGlobal.clone();
						lockData.AcqQueueMap.put(tdOther, queue);
					}
					if (tdOther != td) {
						queue.addLast(td, copyDC);
					}
				}
				lockData.AcqQueueGlobal.addLast(td, copyDC);
				PerThreadQueue<VectorClock> queue = lockData.RelQueueMap.get(td);
				if (queue == null) {
					queue = lockData.RelQueueGlobal.clone();
					lockData.RelQueueMap.put(td, queue);
				}
				//Rule(a)
				//create a new vector clock C_m
				lockData.Cm = new VectorClock(INIT_VECTOR_CLOCK_SIZE);
				lockData.Cm.set(td.getTid(), Epoch.make(td.getTid(), Epoch.MAX_CLOCK)); //To indicate shallow copy Cm has not been set by release event yet
				if (PRINT_EVENTS) Util.log(lockData.Cm.toString());
				//Update last Rule(a) metadata
				//Build a new list of held locks
				DCRELockState outerMostLock = (DCRELockState)getV(td.getHeldLock(0));
				HeldLS thrLock = new HeldLS(td.getHeldLock(0), outerMostLock.Cm); //Outer most lock held
				setHLS(td, thrLock);
				for (int i = 1; i < td.getNumLocksHeld(); i++) {
					ShadowLock heldLock = td.getHeldLock(i);
					DCRELockState heldLockData = (DCRELockState)getV(heldLock);
					//Store a shallow reference to Cm: from outer most to inner most lock held
					thrLock.next = new HeldLS(heldLock, heldLockData.Cm);
					thrLock = thrLock.next;
				}
			}
			if (!RE) {
				//Rule (b)
				VectorClock copyDC = new VectorClock(getV(td));
				DCLockState lockData = (DCLockState) lockV;
				for (ShadowThread tdOther : ShadowThread.getThreads()) {
					PerThreadQueue<VectorClock> queue = lockData.AcqQueueMap.get(tdOther);
					if (queue == null) {
						queue = lockData.AcqQueueGlobal.clone();
						lockData.AcqQueueMap.put(tdOther, queue);
					}
					if (tdOther != td) {
						queue.addLast(td, copyDC);
					}
				}
				lockData.AcqQueueGlobal.addLast(td, copyDC);
				PerThreadQueue<VectorClock> queue = lockData.RelQueueMap.get(td);
				if (queue == null) {
					queue = lockData.RelQueueGlobal.clone();
					lockData.RelQueueMap.put(td, queue);
				}
			}
		}
		if (WDP) {
			//Rule (b)
			VectorClock copyWDP = new VectorClock(getV(td));
			if (DEBUG) Assert.assertTrue(lockV instanceof WDPLockState);
			WDPLockState lockData = (WDPLockState) lockV;
			for (ShadowThread tdOther : ShadowThread.getThreads()) {
				PerThreadQueue<VectorClock> queue = lockData.AcqQueueMap.get(tdOther);
				if (queue == null) {
					queue = lockData.AcqQueueGlobal.clone();
					lockData.AcqQueueMap.put(tdOther, queue);
				}
				if (tdOther != td) {
					queue.addLast(td, copyWDP);
				}
			}
			lockData.AcqQueueGlobal.addLast(td, copyWDP);
			PerThreadQueue<VectorClock> queue = lockData.RelQueueMap.get(td);
			if (queue == null) {
				queue = lockData.RelQueueGlobal.clone();
				lockData.RelQueueMap.put(td, queue);
			}
			//Rule(a)
			//create a new vector clock C_m
			lockData.Cm = new VectorClock(INIT_VECTOR_CLOCK_SIZE);
			lockData.Cm.set(td.getTid(), Epoch.make(td.getTid(), Epoch.MAX_CLOCK)); //To indicate shallow copy Cm has not been set by release event yet
			if (PRINT_EVENTS) Util.log(lockData.Cm.toString());
			//Update last Rule(a) metadata
			//Build a new list of held locks
			final HeldLS newHLS = new HeldLS(lock, lockData.Cm);
			if (getHLS(td) == null) {
				setHLS(td, newHLS);
			} else {
				HeldLS lastHLS = getHLS(td);
				while (lastHLS.next != null) lastHLS = lastHLS.next;
				lastHLS.next = newHLS;
			}
		}
		if ((CAPO && RE) || AGG) {
			//create a new vector clock C_m
			if (DEBUG) Assert.assertTrue(lockV instanceof CAPORELockState);
			CAPORELockState lockData = (CAPORELockState) lockV;
			lockData.Cm = new VectorClock(INIT_VECTOR_CLOCK_SIZE);
			lockData.Cm.set(td.getTid(), Epoch.make(td.getTid(), Epoch.MAX_CLOCK)); //To indicate shallow copy Cm has not been set by release event yet
			if (PRINT_EVENTS) Util.log(lockData.Cm.toString() + "|tid: " + td.getTid() + "|c at t: " + Epoch.clock(lockData.Cm.get(td.getTid())));
			//Update last Rule(a) metadata
			//Build a new list of held locks
			CAPORELockState outerMostLock = (CAPORELockState)getV(td.getHeldLock(0));
			HeldLS thrLock = new HeldLS(td.getHeldLock(0), outerMostLock.Cm); //Outer most lock held
			setHLS(td, thrLock);
			for (int i = 1; i < td.getNumLocksHeld(); i++) {
				ShadowLock heldLock = td.getHeldLock(i);
				CAPORELockState heldLockData = (CAPORELockState)getV(heldLock);
				//Store a shallow reference to Cm: from outer most to inner most lock held
				thrLock.next = new HeldLS(heldLock, heldLockData.Cm);
				thrLock = thrLock.next;
			}
		}
		
		if (!HB) {
			incAtAcquire(td);
		}
	}
	
	public void handleAcquireHardEdge(ShadowThread td, ShadowLock lock, OperationInfo info) {
		final LockState lockV = getV(lock);
		
		if (WCP) {
			if (RE) {
				ts_get_vHB(td).max(((WCPRELockState)lockV).hb);
				ts_set_eHB(td, ts_get_vHB(td).get(td.getTid()));
				
				getV(td).max(((WCPRELockState)lockV).hb);
				ts_set_eWCP(td, ts_get_vWCP(td).get(td.getTid())); //TODO: don't set for max?
			}
			if (!RE) {
				ts_get_vHB(td).max(((WCPLockState)lockV).hb);
				ts_set_eHB(td, ts_get_vHB(td).get(td.getTid()));
				
				getV(td).max(((WCPLockState)lockV).hb);
				ts_set_eWCP(td, ts_get_vWCP(td).get(td.getTid())); //TODO: don't set for max?
			}
		}
		if (!WCP) {
			maxEpochAndCV(td, lockV);
		}
		
		if (WCP) {
			if (RE) {
				if (DEBUG) Assert.assertTrue(lockV instanceof WCPRELockState);
				VectorClock wcpUnionPO = new VectorClock(getV(td));
				wcpUnionPO.set(td.getTid(), ts_get_eHB(td));
				//Rule (b)
				WCPRELockState lockData = (WCPRELockState) lockV;
				for (ShadowThread tdOther : ShadowThread.getThreads()) {
					ArrayDeque<VectorClock> queue = lockData.AcqQueueMap.get(tdOther);
					if (queue == null) {
						queue = lockData.AcqQueueGlobal.clone();
						lockData.AcqQueueMap.put(tdOther, queue);
					}
					if (tdOther != td) {
						queue.addLast(wcpUnionPO);
					}
				}
				lockData.AcqQueueGlobal.addLast(wcpUnionPO);
				ArrayDeque<VectorClock> queue = lockData.RelQueueMap.get(td);
				if (queue == null) {
					queue = lockData.RelQueueGlobal.clone();
					lockData.RelQueueMap.put(td, queue);
				}
				//Rule(a)
				//create a new vector clock C_m
				lockData.Cm = new VectorClock(INIT_VECTOR_CLOCK_SIZE);
				lockData.Cm.set(td.getTid(), Epoch.make(td.getTid(), Epoch.MAX_CLOCK)); //To indicate shallow copy Cm has not been set by release event yet
				if (PRINT_EVENTS) Util.log(lockData.Cm.toString());
				//Update last Rule(a) metadata
				//Build a new list of held locks
				WCPRELockState outerMostLock = (WCPRELockState)getV(td.getHeldLock(0));
				HeldLS thrLock = new HeldLS(td.getHeldLock(0), outerMostLock.Cm); //Outer most lock held
				setHLS(td, thrLock);
				for (int i = 1; i < td.getNumLocksHeld(); i++) {
					ShadowLock heldLock = td.getHeldLock(i);
					WCPRELockState heldLockData = (WCPRELockState)getV(heldLock);
					//Store a shallow reference to Cm: from outer most to inner most lock held
					thrLock.next = new HeldLS(heldLock, heldLockData.Cm);
					thrLock = thrLock.next;
				}
			}
			if (!RE) {
				VectorClock wcpUnionPO = new VectorClock(getV(td));
				wcpUnionPO.set(td.getTid(), ts_get_eHB(td));
				//Rule (b)
				WCPLockState lockData = (WCPLockState) lockV;
				for (ShadowThread tdOther : ShadowThread.getThreads()) {
					ArrayDeque<VectorClock> queue = lockData.AcqQueueMap.get(tdOther);
					if (queue == null) {
						queue = lockData.AcqQueueGlobal.clone();
						lockData.AcqQueueMap.put(tdOther, queue);
					}
					if (tdOther != td) {
						queue.addLast(wcpUnionPO);
					}
				}
				lockData.AcqQueueGlobal.addLast(wcpUnionPO);
				ArrayDeque<VectorClock> queue = lockData.RelQueueMap.get(td);
				if (queue == null) {
					queue = lockData.RelQueueGlobal.clone();
					lockData.RelQueueMap.put(td, queue);
				}
			}
		}
		if (DC) {
			if (RE) {
				//Rule (b)
				VectorClock copyDC = new VectorClock(getV(td));
				if (DEBUG) Assert.assertTrue(lockV instanceof DCRELockState);
				DCRELockState lockData = (DCRELockState) lockV;
				for (ShadowThread tdOther : ShadowThread.getThreads()) {
					PerThreadQueue<VectorClock> queue = lockData.AcqQueueMap.get(tdOther);
					if (queue == null) {
						queue = lockData.AcqQueueGlobal.clone();
						lockData.AcqQueueMap.put(tdOther, queue);
					}
					if (tdOther != td) {
						queue.addLast(td, copyDC);
					}
				}
				lockData.AcqQueueGlobal.addLast(td, copyDC);
				PerThreadQueue<VectorClock> queue = lockData.RelQueueMap.get(td);
				if (queue == null) {
					queue = lockData.RelQueueGlobal.clone();
					lockData.RelQueueMap.put(td, queue);
				}
				//Rule(a)
				//create a new vector clock C_m
				lockData.Cm = new VectorClock(INIT_VECTOR_CLOCK_SIZE);
				lockData.Cm.set(td.getTid(), Epoch.make(td.getTid(), Epoch.MAX_CLOCK)); //To indicate shallow copy Cm has not been set by release event yet
				if (PRINT_EVENTS) Util.log(lockData.Cm.toString());
				//Update last Rule(a) metadata
				//Build a new list of held locks
				DCRELockState outerMostLock = (DCRELockState)getV(td.getHeldLock(0));
				HeldLS thrLock = new HeldLS(td.getHeldLock(0), outerMostLock.Cm); //Outer most lock held
				setHLS(td, thrLock);
				for (int i = 1; i < td.getNumLocksHeld(); i++) {
					ShadowLock heldLock = td.getHeldLock(i);
					DCRELockState heldLockData = (DCRELockState)getV(heldLock);
					//Store a shallow reference to Cm: from outer most to inner most lock held
					thrLock.next = new HeldLS(heldLock, heldLockData.Cm);
					thrLock = thrLock.next;
				}
			}
			if (!RE) {
				//Rule (b)
				VectorClock copyDC = new VectorClock(getV(td));
				DCLockState lockData = (DCLockState) lockV;
				for (ShadowThread tdOther : ShadowThread.getThreads()) {
					PerThreadQueue<VectorClock> queue = lockData.AcqQueueMap.get(tdOther);
					if (queue == null) {
						queue = lockData.AcqQueueGlobal.clone();
						lockData.AcqQueueMap.put(tdOther, queue);
					}
					if (tdOther != td) {
						queue.addLast(td, copyDC);
					}
				}
				lockData.AcqQueueGlobal.addLast(td, copyDC);
				PerThreadQueue<VectorClock> queue = lockData.RelQueueMap.get(td);
				if (queue == null) {
					queue = lockData.RelQueueGlobal.clone();
					lockData.RelQueueMap.put(td, queue);
				}
			}
		}
		if (WDP && RE) {
			//Rule (b)
			VectorClock copyWDP = new VectorClock(getV(td));
			if (DEBUG) Assert.assertTrue(lockV instanceof WDPLockState);
			WDPLockState lockData = (WDPLockState) lockV;
			for (ShadowThread tdOther : ShadowThread.getThreads()) {
				PerThreadQueue<VectorClock> queue = lockData.AcqQueueMap.get(tdOther);
				if (queue == null) {
					queue = lockData.AcqQueueGlobal.clone();
					lockData.AcqQueueMap.put(tdOther, queue);
				}
				if (tdOther != td) {
					queue.addLast(td, copyWDP);
				}
			}
			lockData.AcqQueueGlobal.addLast(td, copyWDP);
			PerThreadQueue<VectorClock> queue = lockData.RelQueueMap.get(td);
			if (queue == null) {
				queue = lockData.RelQueueGlobal.clone();
				lockData.RelQueueMap.put(td, queue);
			}
			//Rule(a)
			//create a new vector clock C_m
			lockData.Cm = new VectorClock(INIT_VECTOR_CLOCK_SIZE);
			lockData.Cm.set(td.getTid(), Epoch.make(td.getTid(), Epoch.MAX_CLOCK)); //To indicate shallow copy Cm has not been set by release event yet
			if (PRINT_EVENTS) Util.log(lockData.Cm.toString());
			//Update last Rule(a) metadata
			//Build a new list of held locks
			WDPLockState outerMostLock = (WDPLockState)getV(td.getHeldLock(0));
			HeldLS thrLock = new HeldLS(td.getHeldLock(0), outerMostLock.Cm); //Outer most lock held
			setHLS(td, thrLock);
			for (int i = 1; i < td.getNumLocksHeld(); i++) {
				ShadowLock heldLock = td.getHeldLock(i);
				WDPLockState heldLockData = (WDPLockState)getV(heldLock);
				//Store a shallow reference to Cm: from outer most to inner most lock held
				thrLock.next = new HeldLS(heldLock, heldLockData.Cm);
				thrLock = thrLock.next;
			}
		}
		if ((CAPO && RE) || AGG) {
			//create a new vector clock C_m
			if (DEBUG) Assert.assertTrue(lockV instanceof CAPORELockState);
			CAPORELockState lockData = (CAPORELockState) lockV;
			lockData.Cm = new VectorClock(INIT_VECTOR_CLOCK_SIZE);
			lockData.Cm.set(td.getTid(), Epoch.make(td.getTid(), Epoch.MAX_CLOCK)); //To indicate shallow copy Cm has not been set by release event yet
			if (PRINT_EVENTS) Util.log(lockData.Cm.toString());
			//Update last Rule(a) metadata
			//Build a new list of held locks
			CAPORELockState outerMostLock = (CAPORELockState)getV(td.getHeldLock(0));
			HeldLS thrLock = new HeldLS(td.getHeldLock(0), outerMostLock.Cm); //Outer most lock held
			setHLS(td, thrLock);
			for (int i = 1; i < td.getNumLocksHeld(); i++) {
				ShadowLock heldLock = td.getHeldLock(i);
				CAPORELockState heldLockData = (CAPORELockState)getV(heldLock);
				//Store a shallow reference to Cm: from outer most to inner most lock held
				thrLock.next = new HeldLS(heldLock, heldLockData.Cm);
				thrLock = thrLock.next;
			}
		}
		
		incAtAcquire(td);
	}
	
	@Override
	public void release(final ReleaseEvent event) {
		final ShadowThread td = event.getThread();
		final LockState lockV = getV(event.getLock());
		
		if (COUNT_EVENTS) release.inc(td.getTid());
		
		handleRelease(td, lockV, event.getInfo());
		
		if (PRINT_EVENTS) Util.log("rel("+event.getLock()+") by T"+td.getTid()); //Util.log("rel("+Util.objectToIdentityString(event.getLock())+") by T"+td.getTid());
		super.release(event);
	}
	
	public void handleRelease(ShadowThread td, LockState lockV, OperationInfo info) {
		final VectorClock tV = getV(td);
		
		if (WCP) {
			if (RE) {
				VectorClock wcpUnionPO = new VectorClock(tV);
				wcpUnionPO.set(td.getTid(), ts_get_eHB(td));
				//Rule (b)
				if (DEBUG) Assert.assertTrue(lockV instanceof WCPRELockState);
				WCPRELockState lockData = (WCPRELockState) lockV;
				ArrayDeque<VectorClock> acqQueue = lockData.AcqQueueMap.get(td);
				ArrayDeque<VectorClock> relQueue = lockData.RelQueueMap.get(td);
				while (!acqQueue.isEmpty() && !acqQueue.peekFirst().anyGt(wcpUnionPO)) {
					acqQueue.removeFirst();
					maxEpochAndCV(td, relQueue.removeFirst());
				}
				//Rule (a)
				//Update the vector clock that was shallow-copied during the current critical section
				lockData.Cm.copy(ts_get_vHB(td));
				if (PRINT_EVENTS) Util.log(lockData.Cm.toString());
				//Update last Rule(a) metadata
				//Build a new list of held locks
				if (td.getNumLocksHeld() == 0) {
					setHLS(td, null);
				} else {
					WCPRELockState outerMostLock = (WCPRELockState)getV(td.getHeldLock(0));
					HeldLS thrLock = new HeldLS(td.getHeldLock(0), outerMostLock.Cm); //Outer most lock held
					setHLS(td, thrLock);
					for (int i = 1; i < td.getNumLocksHeld(); i++) {
						ShadowLock heldLock = td.getHeldLock(i);
						WCPRELockState heldLockData = (WCPRELockState)getV(heldLock);
						//Store a shallow reference to Cm: from outer most to inner most lock held
						thrLock.next = new HeldLS(heldLock, heldLockData.Cm);
						thrLock = thrLock.next;
					}
				}
			}
			if (!RE) {
				VectorClock wcpUnionPO = new VectorClock(tV);
				wcpUnionPO.set(td.getTid(), ts_get_eHB(td));
				//Rule (b)
				if (DEBUG) Assert.assertTrue(lockV instanceof WCPLockState);
				WCPLockState lockData = (WCPLockState) lockV;
				ArrayDeque<VectorClock> acqQueue = lockData.AcqQueueMap.get(td);
				ArrayDeque<VectorClock> relQueue = lockData.RelQueueMap.get(td);
				while (!acqQueue.isEmpty() && !acqQueue.peekFirst().anyGt(wcpUnionPO)) {
					acqQueue.removeFirst();
					maxEpochAndCV(td, relQueue.removeFirst());
				}
				//Rule (a)
				for (ShadowVar var : lockData.readVars) {
					VectorClock cv = lockData.ReadMap.get(var);
					if (cv == null) {
						cv = new VectorClock(PIPTool.INIT_VECTOR_CLOCK_SIZE);
						lockData.ReadMap.put(var, cv);
					}
					cv.max(ts_get_vHB(td));
				}
				for (ShadowVar var : lockData.writeVars) {
					VectorClock cv = lockData.WriteMap.get(var);
					if (cv == null) {
						cv = new VectorClock(PIPTool.INIT_VECTOR_CLOCK_SIZE);
						lockData.WriteMap.put(var, cv);
					}
					cv.max(ts_get_vHB(td));
				}
			}
		}
		
		if (DC) {
			if (RE) {
				//Rule (b)
				if (DEBUG) Assert.assertTrue(lockV instanceof DCRELockState);
				DCRELockState lockData = (DCRELockState) lockV;
				PerThreadQueue<VectorClock> acqQueue = lockData.AcqQueueMap.get(td);
				PerThreadQueue<VectorClock> relQueue = lockData.RelQueueMap.get(td);
				for (ShadowThread tdOther : ShadowThread.getThreads()) {
					if (tdOther != td) {
						while (!acqQueue.isEmpty(tdOther) && !acqQueue.peekFirst(tdOther).anyGt(getV(td))) {
							acqQueue.removeFirst(tdOther);
							maxEpochAndCV(td, relQueue.removeFirst(tdOther));
						}
					}
				}
				//Rule (a)
				//Update the vector clock that was shallow-copied during the current critical section
				lockData.Cm.copy(getV(td));
				if (PRINT_EVENTS) Util.log(lockData.Cm.toString());
				//Update last Rule(a) metadata
				//Build a new list of held locks
				if (td.getNumLocksHeld() == 0) {
					setHLS(td, null);
				} else {
					DCRELockState outerMostLock = (DCRELockState)getV(td.getHeldLock(0));
					HeldLS thrLock = new HeldLS(td.getHeldLock(0), outerMostLock.Cm); //Outer most lock held
					setHLS(td, thrLock);
					for (int i = 1; i < td.getNumLocksHeld(); i++) {
						ShadowLock heldLock = td.getHeldLock(i);
						DCRELockState heldLockData = (DCRELockState)getV(heldLock);
						//Store a shallow reference to Cm: from outer most to inner most lock held
						thrLock.next = new HeldLS(heldLock, heldLockData.Cm);
						thrLock = thrLock.next;
					}
				}
			}
			if (!RE) {
				//Rule (b)
				if (DEBUG) Assert.assertTrue(lockV instanceof DCLockState);
				DCLockState lockData = (DCLockState) lockV;
				PerThreadQueue<VectorClock> acqQueue = lockData.AcqQueueMap.get(td);
				PerThreadQueue<VectorClock> relQueue = lockData.RelQueueMap.get(td);
				for (ShadowThread tdOther : ShadowThread.getThreads()) {
					if (tdOther != td) {
						while (!acqQueue.isEmpty(tdOther) && !acqQueue.peekFirst(tdOther).anyGt(getV(td))) {
							acqQueue.removeFirst(tdOther);
							maxEpochAndCV(td, relQueue.removeFirst(tdOther));
						}
					}
				}
				//Rule (a)
				for (ShadowVar var : lockData.readVars) {
					VectorClock cv = lockData.ReadMap.get(var);
					if (cv == null) {
						cv = new VectorClock(PIPTool.INIT_VECTOR_CLOCK_SIZE);
						lockData.ReadMap.put(var, cv);
					}
					cv.max(getV(td));
				}
				for (ShadowVar var : lockData.writeVars) {
					VectorClock cv = lockData.WriteMap.get(var);
					if (cv == null) {
						cv = new VectorClock(PIPTool.INIT_VECTOR_CLOCK_SIZE);
						lockData.WriteMap.put(var, cv);
					}
					cv.max(getV(td));
				}
			}
		}
		if (WDP) {
			//Rule (b)
			if (DEBUG) Assert.assertTrue(lockV instanceof WDPLockState);
			WDPLockState lockData = (WDPLockState) lockV;
			PerThreadQueue<VectorClock> acqQueue = lockData.AcqQueueMap.get(td);
			PerThreadQueue<VectorClock> relQueue = lockData.RelQueueMap.get(td);
			for (ShadowThread tdOther : ShadowThread.getThreads()) {
				if (tdOther != td) {
					while (!acqQueue.isEmpty(tdOther) && !acqQueue.peekFirst(tdOther).anyGt(getV(td))) {
						acqQueue.removeFirst(tdOther);
						maxEpochAndCV(td, relQueue.removeFirst(tdOther));
					}
				}
			}
			//Rule (a)
			//Update the vector clock that was shallow-copied during the current critical section
			lockData.Cm.copy(getV(td));
			if (DEBUG) lockData.Cm.assertNoMax();
			if (PRINT_EVENTS) Util.log(lockData.Cm.toString());
			//Update last Rule(a) metadata
			HeldLS heldLS = getHLS(td);
			if (heldLS.next == null) setHLS(td, null);
			else {
				HeldLS prev;
				do {
					prev = heldLS;
					heldLS = heldLS.next;
				} while (heldLS.next != null);
				prev.next = null;
			}
		}
		if (CAPO) {
			if (RE) {
				//Update the vector clock that was shallow-copied during the current critical section
				if (DEBUG) Assert.assertTrue(lockV instanceof CAPORELockState);
				CAPORELockState lockData = (CAPORELockState) lockV;
				lockData.Cm.copy(getV(td));
				if (PRINT_EVENTS) Util.log("AFTER: " + lockData.Cm.toString() + " |max epoch: " + Epoch.clock(Epoch.MAX_CLOCK));
				//Update last Rule(a) metadata
				//Build a new list of held locks
				if (td.getNumLocksHeld() == 0) {
					setHLS(td, null);
				} else {
					CAPORELockState outerMostLock = (CAPORELockState)getV(td.getHeldLock(0));
					HeldLS thrLock = new HeldLS(td.getHeldLock(0), outerMostLock.Cm); //Outer most lock held
					setHLS(td, thrLock);
					for (int i = 1; i < td.getNumLocksHeld(); i++) {
						ShadowLock heldLock = td.getHeldLock(i);
						CAPORELockState heldLockData = (CAPORELockState)getV(heldLock);
						//Store a shallow reference to Cm: from outer most to inner most lock held
						thrLock.next = new HeldLS(heldLock, heldLockData.Cm);
						thrLock = thrLock.next;
					}
				}
			}
			if (!RE) {
				//Rule (a)
				if (DEBUG) Assert.assertTrue(lockV instanceof CAPOLockState);
				CAPOLockState lockData = (CAPOLockState) lockV;
				for (ShadowVar var : lockData.readVars) {
					VectorClock cv = lockData.ReadMap.get(var);
					if (cv == null) {
						cv = new VectorClock(PIPTool.INIT_VECTOR_CLOCK_SIZE);
						lockData.ReadMap.put(var, cv);
					}
					cv.max(getV(td));
				}
				for (ShadowVar var : lockData.writeVars) {
					VectorClock cv = lockData.WriteMap.get(var);
					if (cv == null) {
						cv = new VectorClock(PIPTool.INIT_VECTOR_CLOCK_SIZE);
						lockData.WriteMap.put(var, cv);
					}
					cv.max(getV(td));
				}
			}
		}
		
		if (AGG) {
			//Update the vector clock that was shallow-copied during the current critical section
			if (DEBUG) Assert.assertTrue(lockV instanceof CAPORELockState);
			CAPORELockState lockData = (CAPORELockState) lockV;
			lockData.Cm.copy(getV(td));
			if (PRINT_EVENTS) Util.log("AFTER: " + lockData.Cm.toString() + " |max epoch: " + Epoch.clock(Epoch.MAX_CLOCK));
			//Update last Rule(a) metadata
			//Build a new list of held locks
			if (td.getNumLocksHeld() == 0) {
				setHLS(td, null);
			} else {
				CAPORELockState outerMostLock = (CAPORELockState)getV(td.getHeldLock(0));
				HeldLS thrLock = new HeldLS(td.getHeldLock(0), outerMostLock.Cm); //Outer most lock held
				setHLS(td, thrLock);
				for (int i = 1; i < td.getNumLocksHeld(); i++) {
					ShadowLock heldLock = td.getHeldLock(i);
					CAPORELockState heldLockData = (CAPORELockState)getV(heldLock);
					//Store a shallow reference to Cm: from outer most to inner most lock held
					thrLock.next = new HeldLS(heldLock, heldLockData.Cm);
					thrLock = thrLock.next;
				}
			}
		}
		
		//Assign to lock
		if (WCP) {
			if (RE) ((WCPRELockState)lockV).hb.max(ts_get_vHB(td));
			if (!RE) ((WCPLockState)lockV).hb.max(ts_get_vHB(td));
		}
		lockV.max(tV); // Used for hard notify -> wait edge
		
		if (WCP) {
			if (RE) {
				VectorClock copyHB = new VectorClock(ts_get_vHB(td));
				if (DEBUG) Assert.assertTrue(lockV instanceof WCPRELockState);
				WCPRELockState lockData = (WCPRELockState) lockV;
				//Rule (b)
				for (ShadowThread tdOther : ShadowThread.getThreads()) {
					if (tdOther != td) {
						ArrayDeque<VectorClock> queue = lockData.RelQueueMap.get(tdOther);
						if (queue == null) {
							queue = lockData.RelQueueGlobal.clone();
							lockData.RelQueueMap.put(tdOther, queue);
						}
						queue.addLast(copyHB);
					}
				}
				lockData.RelQueueGlobal.addLast(copyHB);
			}
			if (!RE) {
				VectorClock copyHB = new VectorClock(ts_get_vHB(td));
				if (DEBUG) Assert.assertTrue(lockV instanceof WCPLockState);
				WCPLockState lockData = (WCPLockState) lockV;
				//Rule (b)
				for (ShadowThread tdOther : ShadowThread.getThreads()) {
					if (tdOther != td) {
						ArrayDeque<VectorClock> queue = lockData.RelQueueMap.get(tdOther);
						if (queue == null) {
							queue = lockData.RelQueueGlobal.clone();
							lockData.RelQueueMap.put(tdOther, queue);
						}
						queue.addLast(copyHB);
					}
				}
				lockData.RelQueueGlobal.addLast(copyHB);
				//Clear
				lockData.readVars = new HashSet<ShadowVar>();
				lockData.writeVars = new HashSet<ShadowVar>();
				lockData.ReadMap = getPotentiallyShrunkMap(lockData.ReadMap);
				lockData.WriteMap = getPotentiallyShrunkMap(lockData.WriteMap);
			}
		}
		
		if (DC) {
			if (RE) {
				VectorClock copyDC = new VectorClock(getV(td));
				if (DEBUG) Assert.assertTrue(lockV instanceof DCRELockState);
				DCRELockState lockData = (DCRELockState) lockV;
				//Rule (b)
				for (ShadowThread tdOther : ShadowThread.getThreads()) {
					if (tdOther != td) {
						PerThreadQueue<VectorClock> queue = lockData.RelQueueMap.get(tdOther);
						if (queue == null) {
							queue = lockData.RelQueueGlobal.clone();
							lockData.RelQueueMap.put(tdOther, queue);
						}
						queue.addLast(td, copyDC);
					}
				}
				lockData.RelQueueGlobal.addLast(td, copyDC);
			}
			if (!RE) {
				VectorClock copyDC = new VectorClock(getV(td));
				if (DEBUG) Assert.assertTrue(lockV instanceof DCLockState);
				DCLockState lockData = (DCLockState) lockV;
				//Rule (b)
				for (ShadowThread tdOther : ShadowThread.getThreads()) {
					if (tdOther != td) {
						PerThreadQueue<VectorClock> queue = lockData.RelQueueMap.get(tdOther);
						if (queue == null) {
							queue = lockData.RelQueueGlobal.clone();
							lockData.RelQueueMap.put(tdOther, queue);
						}
						queue.addLast(td, copyDC);
					}
				}
				lockData.RelQueueGlobal.addLast(td, copyDC);
				//Clear
				lockData.readVars = new HashSet<ShadowVar>();
				lockData.writeVars = new HashSet<ShadowVar>();
				lockData.ReadMap = getPotentiallyShrunkMap(lockData.ReadMap);
				lockData.WriteMap = getPotentiallyShrunkMap(lockData.WriteMap);
			}
		}

		if (WDP) {
			VectorClock copyWDP = new VectorClock(getV(td));
			if (DEBUG) Assert.assertTrue(lockV instanceof WDPLockState);
			WDPLockState lockData = (WDPLockState) lockV;
			//Rule (b)
			for (ShadowThread tdOther : ShadowThread.getThreads()) {
				if (tdOther != td) {
					PerThreadQueue<VectorClock> queue = lockData.RelQueueMap.get(tdOther);
					if (queue == null) {
						queue = lockData.RelQueueGlobal.clone();
						lockData.RelQueueMap.put(tdOther, queue);
					}
					queue.addLast(td, copyWDP);
				}
			}
			lockData.RelQueueGlobal.addLast(td, copyWDP);
		}

		if (CAPO) {
			if (!RE) {
			if (DEBUG) Assert.assertTrue(lockV instanceof CAPOLockState);
			CAPOLockState lockData = (CAPOLockState) lockV;
			if (COUNT_EVENTS) clearTotal.inc(td.getTid());
			if (COUNT_EVENTS) {
				if (lockData.readVars.isEmpty()) {
					read0.inc(td.getTid());
				} else if (lockData.readVars.size() == 1) {
					read1.inc(td.getTid());
				} else if (lockData.readVars.size() > 1) {
					readMore.inc(td.getTid());
				}
			}
			lockData.readVars = new HashSet<ShadowVar>();
			if (COUNT_EVENTS) {
				if (lockData.writeVars.isEmpty()) {
					write0.inc(td.getTid());
				} else if (lockData.writeVars.size() == 1) {
					write1.inc(td.getTid());
				} else if (lockData.writeVars.size() > 1) {
					writeMore.inc(td.getTid());
				}
			}
			lockData.writeVars = new HashSet<ShadowVar>();
			if (COUNT_EVENTS) {
				if (lockData.ReadMap.isEmpty()) {
					readMap0.inc(td.getTid());
				} else if (lockData.ReadMap.size() == 1) {
					readMap1.inc(td.getTid());
				} else if (lockData.ReadMap.size() <= 10) {
					readMap10.inc(td.getTid());
				} else if (lockData.ReadMap.size() <= 100) {
					readMap100.inc(td.getTid());
				} else if (lockData.ReadMap.size() <= 1000) {
					readMap1000.inc(td.getTid());
				} else if (lockData.ReadMap.size() > 1000) {
					readMapMore.inc(td.getTid());
				}
			}
			lockData.ReadMap = getPotentiallyShrunkMap(lockData.ReadMap);
			if (COUNT_EVENTS) {
				if (lockData.WriteMap.isEmpty()) {
					writeMap0.inc(td.getTid());
				} else if (lockData.WriteMap.size() == 1) {
					writeMap1.inc(td.getTid());
				} else if (lockData.WriteMap.size() <= 10) {
					writeMap10.inc(td.getTid());
				} else if (lockData.WriteMap.size() <= 100) {
					writeMap100.inc(td.getTid());
				} else if (lockData.WriteMap.size() <= 1000) {
					writeMap1000.inc(td.getTid());
				} else if (lockData.WriteMap.size() > 1000) {
					writeMapMore.inc(td.getTid());
				}
			}
			lockData.WriteMap = getPotentiallyShrunkMap(lockData.WriteMap);
			}
		}

		//Increments last
		if (WCP) {
			ts_get_vHB(td).tick(td.getTid());
			ts_set_eHB(td, ts_get_vHB(td).get(td.getTid()));
		}
		if (!WCP) {
			incEpochAndCV(td);
		}
	}
	
	static <K,V> WeakIdentityHashMap<K,V>getPotentiallyShrunkMap(WeakIdentityHashMap<K,V> map) {
		if (map.tableSize() > 16 &&
		    10 * map.size() < map.tableSize() * map.loadFactorSize()) {
			return new WeakIdentityHashMap<K,V>(2 * (int)(map.size() / map.loadFactorSize()), map);
		}
		return map;
	}
	
	static PIPVarState ts_get_badVarState(ShadowThread st) { Assert.panic("Bad");	return null;	}
	static void ts_set_badVarState(ShadowThread st, PIPVarState v) { Assert.panic("Bad");  }

	protected static ShadowVar getOriginalOrBad(ShadowVar original, ShadowThread st) {
		final PIPVarState savedState = ts_get_badVarState(st);
		if (savedState != null) {
			ts_set_badVarState(st, null);
			return savedState;
		} else {
			return original;
		}
	}
	
	public static boolean readFastPath(final ShadowVar orig, final ShadowThread td) {
		final PIPVarState sx = ((PIPVarState)orig);

		int/*epoch*/ e;
		if (WCP) {
			e = ts_get_eHB(td);
		} else {
			e = getE(td);
		}

		/* optional */ {
			final int/*epoch*/ r = sx.R;
			if (r == e) {
				if (COUNT_EVENTS) readSameEpochFP.inc(td.getTid());
				if (COUNT_EVENTS) readFP.inc(td.getTid());
				return true;
			} else if (r == Epoch.READ_SHARED && sx.get(td.getTid()) == e) {
				if (COUNT_EVENTS) readSharedSameEpochFP.inc(td.getTid());
				if (COUNT_EVENTS) readFP.inc(td.getTid());
				return true;
			}
		}

		if (HB || AGG || RE || FTO || td.getNumLocksHeld() == 0) {
			synchronized(sx) {
				final int tid = td.getTid();
				final VectorClock tV = getV(td);
				if (WCP) tV.set(tid, ts_get_eHB(td)); //WCP union PO
				final int/*epoch*/ r = sx.R;
				final int/*epoch*/ w = sx.W;
				final int wTid = Epoch.tid(w);
				
				if (COUNT_EVENTS) {
					if (td.getNumLocksHeld() > 0) {
						holdLocks.inc(tid);
						if (td.getNumLocksHeld() == 1) {
							oneLockHeld.inc(tid);
						} else if (td.getNumLocksHeld() == 2) {
							twoNestedLocksHeld.inc(tid);
						} else if (td.getNumLocksHeld() == 3) {
							threeNestedLocksHeld.inc(tid);
						} else if (td.getNumLocksHeld() == 4) {
							fourNestedLocksHeld.inc(tid);
						} else if (td.getNumLocksHeld() == 5) {
							fiveNestedLocksHeld.inc(tid);
						} else if (td.getNumLocksHeld() == 6) {
							sixNestedLocksHeld.inc(tid);
						} else if (td.getNumLocksHeld() == 7) {
							sevenNestedLocksHeld.inc(tid);
						} else if (td.getNumLocksHeld() == 8) {
							eightNestedLocksHeld.inc(tid);
						} else if (td.getNumLocksHeld() == 9) {
							nineNestedLocksHeld.inc(tid);
						} else if (td.getNumLocksHeld() == 10) {
							tenNestedLocksHeld.inc(tid);
						} else if (td.getNumLocksHeld() <= 100) {
							hundredNestedLocksHeld.inc(tid);
						} else if (td.getNumLocksHeld() > 100) {
							manyNestedLocksHeld.inc(tid);
						}
					}
				}

				if (WDP) {
					WDPVarState xRE = (WDPVarState) sx;
					if (DEBUG) {
						if (xRE.isSharedRead()) {
							Assert.assertTrue(xRE.R == Epoch.READ_SHARED);
						} else {
							Assert.assertFalse(xRE.R == Epoch.READ_SHARED);
						}
						Assert.assertTrue(xRE.isSharedWrite() == (xRE.W == Epoch.WRITE_SHARED));
					}
					if (w != Epoch.WRITE_SHARED) { // Read after ordered writes
						if (Epoch.tid(w) != tid) {
							if(!Epoch.leq(w, e) && !csCheck(td, getHLS(td), xRE.Wm)) { // csCheck updates pending edge
								// Write - Read race
								ts_set_badVarState(td, sx);
								return false;
							}
						}
					} else { // Read after unordered writes
						HeldLS[] writes = xRE.getSharedWrites();
						for (int i = 0; i < writes.length; i++) {
							if (i != tid && writes[i] != null) {
								if(!Epoch.leq(xRE.writeVC.get(i), e) &&
										((xRE.lastWriterTid == i && !csCheck(td, getHLS(td), writes[i])) // csCheck establishes Rule (a) for last writer
												|| !hasCommon(writes[i], getHLS(td)))) {
									// Write - Read race
									ts_set_badVarState(td, sx);
									return false;
								}
							}
						}
					}
					if (r != Epoch.READ_SHARED) { // Read after ordered reads
						if (Epoch.tid(r) == tid || Epoch.leq(r, e)) { // still ordered, keep epoch
							xRE.R = e;
							xRE.Rm = getHLS(td);
							if (COUNT_EVENTS) readExclusiveFP.inc(tid);
						} else { // no longer ordered, switch to vector clock
							xRE.R = Epoch.READ_SHARED;
							xRE.makeReadShared(Util.max(Epoch.tid(r), tid, INIT_VECTOR_CLOCK_SIZE));
							xRE.putReadShared(tid, getHLS(td));
							xRE.putReadShared(Epoch.tid(r), xRE.Rm);
							xRE.Rm = null;
							xRE.set(tid, e);
							xRE.set(Epoch.tid(r), r);
							if (COUNT_EVENTS) readSharedFP.inc(tid);
						}
					} else { // Read after unordered reads
						if (xRE.leq(tV)) { // All prior reads are ordered to this read, switch to epoch
							xRE.R = e;
							xRE.Rm = getHLS(td);
							xRE.clearSharedRead();
							if (COUNT_EVENTS) readOwnedFP.inc(tid);
						} else { // Still unordered, keep vector clock
							xRE.putReadShared(tid, getHLS(td));
							xRE.set(tid, e);
							if (COUNT_EVENTS) readSharedFP.inc(tid);
						}
					}
				} else if (AGG || RE) { //AGG and FTO or ([WCP/DC] + RE) and FTO
					if (!AGG) {
						REVarState xRE = (REVarState)sx;
						if (!xRE.Ew.isEmpty()) {
							for (int i = 0; i < td.getNumLocksHeld(); i++) { //outer most to inner most
								ShadowLock lock = td.getHeldLock(i);
								for (int prevTid : xRE.Ew.keySet()) {
									if (prevTid != tid && xRE.Ew.get(prevTid).containsKey(lock)) {
										maxEpochAndCV(td, xRE.Ew.get(prevTid).get(lock));
									}
								}
							}
						}
					}
					
					if (r != Epoch.READ_SHARED) { //read epoch
						final int rTid = Epoch.tid(r);
						if (rTid == tid) { //Read-Owned, Rule(a) Check is unneeded for read-owned case since the prior write access was on the same thread
							//Update last Rule(a) metadata
							REVarState xRE = (REVarState)sx;
							xRE.Rm = getHLS(td);
							//Update last access metadata
							sx.R = e; //readOwned
							if (COUNT_EVENTS) readOwnedFP.inc(tid);
							if (PRINT_EVENTS) Util.log("rd owned FP");
						} else {
							REVarState xRE = (REVarState)sx;
							HeldLS rdLock = xRE.Rm;
							//If prior access was not protected by a lock and the prior access is not ordered to the current access
							//Or if the outer most lock protecting the prior access is not ordered to the current access then read-share
							//Otherwise, read-exclusive
							if ((rdLock == null && !Epoch.leq(r, tV.get(rTid))) || (rdLock != null && !Epoch.leq(rdLock.vc.get(rTid), tV.get(rTid)))) {
								//Rule(a) Check
								HeldLS wrLock = xRE.Wm;
								while (wrLock != null) {
									if (wTid != tid) {
										if (Epoch.leq(wrLock.vc.get(wTid), tV.get(wTid))) {
											break; //Outer most lock already ordered to the current access
										} else if (td.equals(wrLock.lock.getHoldingThread())) { //Outer most lock conflicts with current access
											//Establish Rule(a) and avoid checking for write-read race
											if (PRINT_EVENTS) Util.log("wr to rd-share (FP) Rule a: " + wrLock.vc.toString());
											if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
											maxEpochAndCV(td, wrLock.vc);
											if (WCP) tV.set(tid, ts_get_eHB(td)); //WCP union PO
											break;
										}
									}
									wrLock = wrLock.next;
								}
								if (wrLock == null && wTid != tid && !Epoch.leq(w, tV.get(wTid))) { //Write-Read Race. wrLock is null if Rule(a) is not established. wTid != tid is guaranteed since rTid != tid
									ts_set_badVarState(td, sx);
									if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
									return false;
								} //Read-Share
								//Update last Rule(a) metadata
								int initSharedHeldLSSize = Math.max(Math.max(rTid, tid)+1, INIT_VECTOR_CLOCK_SIZE);
								xRE.makeSharedHeldLS(initSharedHeldLSSize);
								xRE.setSharedHeldLS(rTid, xRE.Rm);
								xRE.setSharedHeldLS(tid, getHLS(td));
								//Update last access metadata
								int initSize = Math.max(Math.max(rTid, tid)+1, INIT_VECTOR_CLOCK_SIZE);
								sx.makeCV(initSize);
								sx.set(rTid, r);
								sx.set(tid, e);
								sx.R = Epoch.READ_SHARED; //readShare
								if (COUNT_EVENTS) readShareFP.inc(tid);
								if (PRINT_EVENTS) Util.log("rd share FP");
							} else { //Read-Exclusive
								//Rule(a) Check is unneeded for read-exclusive case since the prior write access is ordered to the current read access
								//Update last Rule(a) metadata
								xRE.Rm = getHLS(td);
								//Update last access metadata
								sx.R = e; //readExclusive
								if (COUNT_EVENTS) readExclusiveFP.inc(tid);
								if (PRINT_EVENTS) Util.log("rd exclusive FP");
							}
						}
					} else { //read vector
						if (Epoch.clock(sx.get(tid)) != Epoch.ZERO) { //Read-Shared-Owned
							//Rule(a) Check is unneeded for read-shared-owned case since the prior write access is ordered to the current read access
							//Update last Rule(a) metadata
							REVarState xRE = (REVarState)sx;
							xRE.setSharedHeldLS(tid, getHLS(td));
							//Update last access metadata
							sx.set(tid, e); //readSharedOwnedq
							if (COUNT_EVENTS) readSharedOwnedFP.inc(tid);
							if (PRINT_EVENTS) Util.log("rd shared owned FP");
						} else {
							//Rule(a) Check
							REVarState xRE = (REVarState)sx;
							HeldLS wrLock = xRE.Wm;
							while (wrLock != null) {
								if (wTid != tid) {
									if (Epoch.leq(wrLock.vc.get(wTid), tV.get(wTid))) {
										break; //Outer most lock already ordered to the current access
									} else if (td.equals(wrLock.lock.getHoldingThread())) { //Outer most lock conflicts with current access
										//Establish Rule(a) and avoid checking for write-read race
										if (PRINT_EVENTS) Util.log("wr to rd-share (FP) Rule a: " + wrLock.vc.toString());
										if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
										maxEpochAndCV(td, wrLock.vc);
										if (WCP) tV.set(tid, ts_get_eHB(td)); //WCP union PO
										break;
									}
								}
								wrLock = wrLock.next;
							}
							if (wrLock == null && wTid != tid && !Epoch.leq(w, tV.get(wTid))) { //Write-Read Race. wrLock is null if Rule(a) is not established.
								ts_set_badVarState(td, sx);
								if (WCP) tV.set(td.getTid(), getE(td)); //revert WCP union PO
								return false;
							} //Read-Shared
							//Update last Rule(a) metadata
							xRE.setSharedHeldLS(tid, getHLS(td));
							//Update last access metadata
							sx.set(tid, e); //readShared
							if (COUNT_EVENTS) readSharedFP.inc(tid);
							if (PRINT_EVENTS) Util.log("rd shared FP");
						}
					}
				}
				
				//FTO but not AGG nor [WCP/DC] + RE
				if (!AGG && !RE && FTO) {
					if (!HB) {
					//Establish Rule(a)
					for (int i = 0; i < td.getNumLocksHeld(); i++) { //outer most to inner most
						ShadowLock lock = td.getHeldLock(i);
						if (WCP) {
							if (DEBUG) Assert.assertTrue(getV(lock) instanceof WCPLockState);
							WCPLockState lockData = (WCPLockState)getV(lock);
							//Establish Rule(a)
							VectorClock priorCSAfterAccess = lockData.WriteMap.get(sx);
							if (priorCSAfterAccess != null) {
								tV.set(tid, getE(td)); //revert WCP union PO
								maxEpochAndCV(td, priorCSAfterAccess);
								tV.set(tid, ts_get_eHB(td)); //WCP union PO
							}
							//Update write/read Vars
							lockData.readVars.add(sx);
						}
						if (DC) {
							if (DEBUG) Assert.assertTrue(getV(lock) instanceof DCLockState);
							DCLockState lockData = (DCLockState)getV(lock);
							//Establish Rule(a)
							VectorClock priorCSAfterAccess = lockData.WriteMap.get(sx);
							if (priorCSAfterAccess != null) {
								maxEpochAndCV(td, priorCSAfterAccess);
							}
							//Update write/read Vars
							lockData.readVars.add(sx);
						}
						if (CAPO) {
							if (DEBUG) Assert.assertTrue(getV(lock) instanceof CAPOLockState);
							CAPOLockState lockData = (CAPOLockState)getV(lock);
							//Establish Rule(a)
							VectorClock priorCSAfterAccess = lockData.WriteMap.get(sx);
							if (priorCSAfterAccess != null) {
								maxEpochAndCV(td, priorCSAfterAccess);
							}
							//Update write/read Vars
							lockData.readVars.add(sx);
						}
					}
					}
					
					if (r != Epoch.READ_SHARED) { //read epoch
						final int rTid = Epoch.tid(r);
						if (rTid == tid) { //Read-Owned
							//Update last access metadata
							sx.R = e; //readOwned
							if (COUNT_EVENTS) readOwnedFP.inc(tid);
						} else {
							if (!Epoch.leq(r, tV.get(rTid))) {
								if (wTid != tid && !Epoch.leq(w, tV.get(wTid))) { //Write-Read Race.
									ts_set_badVarState(td, sx);
									if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
									return false;
								} //Read-Share
								//Update last access metadata
								int initSize = Math.max(Math.max(rTid, tid)+1, INIT_VECTOR_CLOCK_SIZE);
								sx.makeCV(initSize);
								sx.set(rTid, r);
								sx.set(tid, e);
								sx.R = Epoch.READ_SHARED; //readShare
								if (COUNT_EVENTS) readShareFP.inc(tid);
							} //Read-Exclusive
							//Update last access metadata
							sx.R = e; //readExclusive
							if (COUNT_EVENTS) readExclusiveFP.inc(tid);
						}
					} else { //read vector
						if (Epoch.clock(sx.get(tid)) != Epoch.ZERO) { //Read-Shared-Owned
							//Update last access metadata
							sx.set(tid, e); //readSharedOwned
							if (COUNT_EVENTS) readSharedOwnedFP.inc(tid);
						} else {
							if (wTid != tid && !Epoch.leq(w, tV.get(wTid))) { //Write-Read Race.
								ts_set_badVarState(td, sx);
								if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
								return false;
							} //Read-Shared
							//Update last access metadata
							sx.set(tid, e); //readShared
							if (COUNT_EVENTS) readSharedFP.inc(tid);
						}
					}
				}
				
				//Not AGG nor FTO nor [WCP/DC] + RE
				if (!AGG && !RE && !FTO) {
					//Write-Read Race Check.
					if (wTid != tid && !Epoch.leq(w, tV.get(wTid))) {
						ts_set_badVarState(td, sx);
						if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
						return false;
					}
					
					if (r != Epoch.READ_SHARED) { //read epoch
						final int rTid = Epoch.tid(r);
						if (rTid == tid || Epoch.leq(r, tV.get(rTid))) { //Read-Exclusive
							//Update last access metadata
							sx.R = e; //readExclusive
							if (COUNT_EVENTS) readExclusiveFP.inc(tid);
						} else { //Read-Share
							//Update last access metadata
							int initSize = Math.max(Math.max(rTid, tid)+1, INIT_VECTOR_CLOCK_SIZE);
							sx.makeCV(initSize);
							sx.set(rTid, r);
							sx.set(tid, e);
							sx.R = Epoch.READ_SHARED; //readShare
							if (COUNT_EVENTS) readShareFP.inc(tid);
						}
					} else { //read vector
						//Update last access metadata
						sx.set(tid, e); //readShared
						if (COUNT_EVENTS) readSharedFP.inc(tid);
					}
				}
				
				//Counting and WCP update
				if (COUNT_EVENTS) readFP.inc(td.getTid());
				if (COUNT_EVENTS) {
					if (td.getNumLocksHeld() == 0) {
						readOUT.inc(td.getTid());
						readOUTFP.inc(td.getTid());
					} else {
						readIN.inc(td.getTid());
						readINFP.inc(td.getTid());
					}
				}
				if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
				return true;
			}
		} else {
			return false;
		}
	}
	
	protected void read(final AccessEvent event, final ShadowThread td, final PIPVarState x) {
		int/*epoch*/ e;
		if (WCP) {
			e = ts_get_eHB(td);
		} else {
			e = getE(td);
		}
		
		if (COUNT_EVENTS) {
			if (td.getNumLocksHeld() == 0) {
				readOUT.inc(td.getTid());
			} else {
				readIN.inc(td.getTid());
			}
		}
		
		/* optional */ {
			final int/*epoch*/ r = x.R;
			if (r == e) {
				if (COUNT_EVENTS) readSameEpoch.inc(td.getTid());
				return;
			} else if (r == Epoch.READ_SHARED && x.get(td.getTid()) == e) {
				if (COUNT_EVENTS) readSharedSameEpoch.inc(td.getTid());
				return;
			}
		}
		
		synchronized(x) {
			final VectorClock tV = getV(td);
			final int/*epoch*/ r = x.R;
			final int/*epoch*/ w = x.W;
			final int wTid = Epoch.tid(w);
			final int tid = td.getTid();
			if (WCP) tV.set(tid, ts_get_eHB(td)); //WCP union PO
			
			if (COUNT_EVENTS) {
				if (td.getNumLocksHeld() > 0) {
					holdLocks.inc(tid);
					if (td.getNumLocksHeld() == 1) {
						oneLockHeld.inc(tid);
					} else if (td.getNumLocksHeld() == 2) {
						twoNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() == 3) {
						threeNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() == 4) {
						fourNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() == 5) {
						fiveNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() == 6) {
						sixNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() == 7) {
						sevenNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() == 8) {
						eightNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() == 9) {
						nineNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() == 10) {
						tenNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() <= 100) {
						hundredNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() > 100) {
						manyNestedLocksHeld.inc(tid);
					}
				}
			}

			if (WDP) {
				WDPVarState xRE = (WDPVarState) x;
				if (w != Epoch.WRITE_SHARED) { // Read after ordered writes
					if (Epoch.tid(w) != tid) {
						if(!Epoch.leq(w, e) && !csCheck(td, getHLS(td), xRE.Wm)) { // csCheck updates pending edge
							// Write - Read race
							error(event, x, "Write-Read Race", "Write by ", wTid, "Read by ", tid);
							//Update vector clocks to make execution race free
							if (DEBUG) Assert.assertTrue(Epoch.clock(w) < Epoch.MAX_CLOCK);
							ts_get_bWDP(td).set(wTid, w);
							ts_set_beWDP(td, true);
							if (COUNT_EVENTS) writeReadError.inc(tid);
						}
					}
				} else { // Read after unordered writes
					HeldLS[] writes = xRE.getSharedWrites();
					for (int i = 0; i < writes.length; i++) {
						if (i != tid && writes[i] != null) {
							if(!Epoch.leq(xRE.writeVC.get(i), e) &&
									((xRE.lastWriterTid == i && !csCheck(td, getHLS(td), writes[i])) // csCheck establishes Rule (a) for last writer
											|| !hasCommon(writes[i], getHLS(td)))) {
								// Write - Read race
								error(event, x, "Write-Read Race", "Write by ", i, "Read by ", tid); // TODO: Count shortest raace only
								//Update vector clocks to make execution race free
								if (i == wTid) {
									if (DEBUG) Assert.assertTrue(Epoch.clock(w) < Epoch.MAX_CLOCK);
									ts_get_bWDP(td).set(i, w);
									ts_set_beWDP(td, true);
									if (COUNT_EVENTS) {
										sharedWriteReadError.inc(tid);
									}
								}
							}
						}
					}
				}
				if (r != Epoch.READ_SHARED) { // Read after ordered reads
					if (Epoch.tid(r) == tid || Epoch.leq(r, e)) { // still ordered, keep epoch
						xRE.R = e;
						xRE.Rm = getHLS(td);
					} else { // no longer ordered, switch to vector clock
						xRE.R = Epoch.READ_SHARED;
						xRE.makeReadShared(Util.max(Epoch.tid(r), tid, INIT_VECTOR_CLOCK_SIZE));
						xRE.putReadShared(tid, getHLS(td));
						xRE.putReadShared(Epoch.tid(r), xRE.Rm);
						xRE.Rm = null;
						xRE.set(tid, e);
						xRE.set(Epoch.tid(r), r);
					}
				} else { // Read after unordered reads
					if (xRE.leq(tV)) { // All prior reads are ordered to this read, switch to epoch
						xRE.R = e;
						xRE.Rm = getHLS(td);
						xRE.clearSharedRead();
					} else { // Still unordered, keep vector clock
						xRE.putReadShared(tid, getHLS(td));
						xRE.set(tid, e);
					}
				}
			} else if (AGG || RE) { //AGG and FTO or ([WCP/DC] + RE) and FTO
				if (r != Epoch.READ_SHARED) { //read epoch
					final int rTid = Epoch.tid(r);
					if (rTid == tid) { //Read-Owned, Rule(a) Check is unneeded for read-owned case since the prior write access was on the same thread
						//Update last Rule(a) metadata
						REVarState xRE = (REVarState)x;
						xRE.Rm = getHLS(td);
						//Update last access metadata
						x.R = e; //readOwned
						if (COUNT_EVENTS) readOwned.inc(tid);
					} else {
						REVarState xRE = (REVarState)x;
						HeldLS rdLock = xRE.Rm;
						//If prior access was not protected by a lock and the prior access is not ordered to the current access
						//Or if the outer most lock protecting the prior access is not ordered to the current access then read-share
						//Otherwise, read-exclusive
						if ((rdLock == null && !Epoch.leq(r, tV.get(rTid))) || (rdLock != null && !Epoch.leq(rdLock.vc.get(rTid), tV.get(rTid)))) {
							//Rule(a) Check
							HeldLS wrLock = xRE.Wm;
							while (wrLock != null) {
								if (wTid != tid && !Epoch.leq(wrLock.vc.get(wTid), tV.get(wTid)) && td.equals(wrLock.lock.getHoldingThread())) {
									//Establish Rule(a) and avoid checking for write-read race
									if (PRINT_EVENTS) Util.log("wr to rd-share Rule a: " + wrLock.vc.toString());
									if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
									maxEpochAndCV(td, wrLock.vc);
									if (WCP) tV.set(tid, ts_get_eHB(td)); //WCP union PO
									break;
								}
								wrLock = wrLock.next;
							}
							if (wrLock == null && wTid != tid && !Epoch.leq(w, tV.get(wTid))) { //Write-Read Race. wrLock is null if Rule(a) is not established.
								error(event, x, "Write-Read Race", "Write by ", wTid, "Read by ", tid);
								//Update vector clocks to make execution race free, w represents WCP union PO of the prior access
								if (WCP) tV.set(wTid, Epoch.max(tV.get(wTid), w));
								if (!WCP) tV.set(wTid, w);
								if (COUNT_EVENTS) writeReadError.inc(tid);
							} //Read-Share
							//Update last Rule(a) metadata
							int initSharedHeldLSSize = Math.max(Math.max(rTid, tid)+1, INIT_VECTOR_CLOCK_SIZE);
							xRE.makeSharedHeldLS(initSharedHeldLSSize);
							xRE.setSharedHeldLS(rTid, xRE.Rm);
							xRE.setSharedHeldLS(tid, getHLS(td));
							//Update last access metadata
							int initSize = Math.max(Math.max(rTid, tid)+1, INIT_VECTOR_CLOCK_SIZE);
							x.makeCV(initSize);
							x.set(rTid, r);
							x.set(tid, e);
							x.R = Epoch.READ_SHARED; //readShare
							if (COUNT_EVENTS) readShare.inc(tid);
						} else { //Read-Exclusive, Rule(a) Check is unneeded for read-exclusive case since the prior write access is ordered to the current read access
							//Update last Rule(a) metadata
							xRE.Rm = getHLS(td);
							//Update last access metadata
							x.R = e; //readExclusive
							if (COUNT_EVENTS) readExclusive.inc(tid);
						}
					}
				} else { //read vector
					if (Epoch.clock(x.get(tid)) != Epoch.ZERO) { //Read-Shared-Owned, Rule(a) Check is unneeded for read-shared-owned case since the prior write access is ordered to the current read access
						//Update last Rule(a) metadata
						REVarState xRE = (REVarState)x;
						xRE.setSharedHeldLS(tid, getHLS(td));
						//Update last access metadata
						x.set(tid, e); //readSharedOwned
						if (COUNT_EVENTS) readSharedOwned.inc(tid);
					} else {
						//Rule(a) Check
						REVarState xRE = (REVarState)x;
						HeldLS wrLock = xRE.Wm;
						while (wrLock != null) {
							if (wTid != tid && !Epoch.leq(wrLock.vc.get(wTid), tV.get(wTid)) && td.equals(wrLock.lock.getHoldingThread())) {
								//Establish Rule(a) and avoid check for write-read race
								if (PRINT_EVENTS) Util.log("wr to rd-shared Rule a: " + wrLock.vc.toString());
								if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
								maxEpochAndCV(td, wrLock.vc);
								if (WCP) tV.set(tid, ts_get_eHB(td)); //WCP union PO
								break;
							}
							wrLock = wrLock.next;
						}
						if (wrLock == null && wTid != tid && !Epoch.leq(w, tV.get(wTid))) { //Write-Read Race. wrLock is null if Rule(a) is not established.
							error(event, x, "Write-Read Race", "Write by ", wTid, "Read by ", tid);
							//Update vector clocks to make execution race free, w represents WCP union PO of the prior access
							if (WCP) tV.set(wTid, Epoch.max(tV.get(wTid), w));
							if (!WCP) tV.set(wTid, w);
							if (COUNT_EVENTS) writeReadError.inc(tid);
						} //Read-Shared
						//Update last Rule(a) metadata
						xRE.setSharedHeldLS(tid, getHLS(td));
						//Update last access metadata
						x.set(tid, e); //readShared
						if (COUNT_EVENTS) readShared.inc(tid);
					}
				}
			}
			
			//FTO but not AGG nor [WCP/DC] + RE
			if (!AGG && !RE && FTO) {
				if (r != Epoch.READ_SHARED) { //read epoch
					final int rTid = Epoch.tid(r);
					if (rTid == tid) { //Read-Owned
						//Update last access metadata
						x.R = e; //readOwned
						if (COUNT_EVENTS) readOwned.inc(tid);
					} else {
						if (!Epoch.leq(r, tV.get(rTid))) {
							if (wTid != tid && !Epoch.leq(w, tV.get(wTid))) { //Write-Read Race.
								if (PRINT_EVENTS) Util.log("wr-rd share error");
								error(event, x, "Write-Read Race", "Write by ", wTid, "Read by ", tid);
								//Update vector clocks to make execution race free, w represents WCP union PO of the prior access
								if (WCP) tV.set(wTid, Epoch.max(tV.get(wTid), w));
								if (!WCP) tV.set(wTid, w);
								if (COUNT_EVENTS) writeReadError.inc(tid);
							} //Read-Share
							//Update last access metadata
							int initSize = Math.max(Math.max(rTid, tid)+1, INIT_VECTOR_CLOCK_SIZE);
							x.makeCV(initSize);
							x.set(rTid, r);
							x.set(tid, e);
							x.R = Epoch.READ_SHARED; //readShare
							if (COUNT_EVENTS) readShare.inc(tid);
						} else { //Read-Exclusive
							//Update last access metadata
							x.R = e; //readExclusive
							if (COUNT_EVENTS) readExclusive.inc(tid);
						}
					}
				} else { //read vector
					if (Epoch.clock(x.get(tid)) != Epoch.ZERO) { //Read-Shared-Owned
						//Update last access metadata
						x.set(tid, e); //readSharedOwned
						if (COUNT_EVENTS) readSharedOwned.inc(tid);
					} else {
						if (wTid != tid && !Epoch.leq(w, tV.get(wTid))) { //Write-Read Race.
							if (PRINT_EVENTS) Util.log("wr-rd shared error");
							error(event, x, "Write-Read Race", "Write by ", wTid, "Read by ", tid);
							//Update vector clocks to make execution race free, w represents WCP union PO of the prior access
							if (WCP) tV.set(wTid, Epoch.max(tV.get(wTid), w));
							if (!WCP) tV.set(wTid, w);
							if (COUNT_EVENTS) writeReadError.inc(tid);
						} //Read-Shared
						//Update last access metadata
						x.set(tid, e); //readShared
						if (COUNT_EVENTS) readShared.inc(tid);
					}
				}
			}
			
			//Not AGG nor FTO nor [WCP/DC] + RE
			if (!AGG && !RE && !FTO) {
				//Write-Read Race Check.
				if (wTid != tid && !Epoch.leq(w, tV.get(wTid))) {
					error(event, x, "Write-Read Race", "Write by ", wTid, "Read by ", tid);
					//Update vector clocks to make execution race free, w represents WCP union PO of the prior access
					if (WCP) tV.set(wTid, Epoch.max(tV.get(wTid), w));
					if (!WCP) tV.set(wTid, w);
					if (COUNT_EVENTS) writeReadError.inc(tid);
				}
				
				if (r != Epoch.READ_SHARED) { //read epoch
					final int rTid = Epoch.tid(r);
					if (rTid == tid || Epoch.leq(r, tV.get(rTid))) { //Read-Exclusive
						//Update last access metadata
						x.R = e; //readExclusive
						if (COUNT_EVENTS) readExclusive.inc(tid);
					} else { //Read-Share
						//Update last access metadata
						int initSize = Math.max(Math.max(rTid, tid)+1, INIT_VECTOR_CLOCK_SIZE);
						x.makeCV(initSize);
						x.set(rTid, r);
						x.set(tid, e);
						x.R = Epoch.READ_SHARED; //readShare
						if (COUNT_EVENTS) readShare.inc(tid);
					}
				} else { //read vector
					//Update last access metadata
					x.set(tid, e); //readShared
					if (COUNT_EVENTS) readShared.inc(tid);
				}
			}
			
			if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
		}
	}
	
	public static boolean writeFastPath(final ShadowVar orig, final ShadowThread td) {
		final PIPVarState sx = ((PIPVarState)orig);

		int/*epoch*/ E;
		if (WCP) {
			E = ts_get_eHB(td);
		} else {
			E = getE(td);
		}

		/* optional */ {
			final int/*epoch*/ w = sx.W;
			if (w == E) {
				if (COUNT_EVENTS) writeSameEpochFP.inc(td.getTid());
				if (COUNT_EVENTS) writeFP.inc(td.getTid());
				return true;
			}
			if (WDP && w == Epoch.WRITE_SHARED && ((WDPVarState)sx).writeVC.get(td.getTid()) == E) {
				if (COUNT_EVENTS) writeSharedSameEpochFP.inc(td.getTid());
				if (COUNT_EVENTS) writeFP.inc(td.getTid());
				return true;
			}
		}

		if (HB || AGG || RE || FTO || td.getNumLocksHeld() == 0) {
			synchronized(sx) {
				final int tid = td.getTid();
				final int/*epoch*/ w = sx.W;
				final int wTid = Epoch.tid(w);
				final VectorClock tV = getV(td);
				if (WCP) tV.set(tid, ts_get_eHB(td)); //WCP union PO
				
				if (COUNT_EVENTS) {
					if (td.getNumLocksHeld() > 0) {
						holdLocks.inc(tid);
						if (td.getNumLocksHeld() == 1) {
							oneLockHeld.inc(tid);
						} else if (td.getNumLocksHeld() == 2) {
							twoNestedLocksHeld.inc(tid);
						} else if (td.getNumLocksHeld() == 3) {
							threeNestedLocksHeld.inc(tid);
						} else if (td.getNumLocksHeld() == 4) {
							fourNestedLocksHeld.inc(tid);
						} else if (td.getNumLocksHeld() == 5) {
							fiveNestedLocksHeld.inc(tid);
						} else if (td.getNumLocksHeld() == 6) {
							sixNestedLocksHeld.inc(tid);
						} else if (td.getNumLocksHeld() == 7) {
							sevenNestedLocksHeld.inc(tid);
						} else if (td.getNumLocksHeld() == 8) {
							eightNestedLocksHeld.inc(tid);
						} else if (td.getNumLocksHeld() == 9) {
							nineNestedLocksHeld.inc(tid);
						} else if (td.getNumLocksHeld() == 10) {
							tenNestedLocksHeld.inc(tid);
						} else if (td.getNumLocksHeld() <= 100) {
							hundredNestedLocksHeld.inc(tid);
						} else if (td.getNumLocksHeld() > 100) {
							manyNestedLocksHeld.inc(tid);
						}
					}
				}
				
				if (WDP) {
					WDPVarState xRE = (WDPVarState)sx;
					final int r = sx.R;
					final int rTid = Epoch.tid(r);
					if (r != Epoch.READ_SHARED) { // write after ordered reads
						if (rTid != tid && !Epoch.leq(r, E) && !hasCommon(xRE.Rm, getHLS(td))) {
							// Read - Write race
							ts_set_badVarState(td, sx);
							return false;
						}
					} else { // write after unordered reads
						HeldLS[] reads = xRE.getSharedReads();
						for (int i = 0; i < reads.length; i++) {
							if (i != tid && reads[i] != null) {
								if(!Epoch.leq(xRE.get(i), E) && !hasCommon(reads[i], getHLS(td))) {
									// Read - Write race
									ts_set_badVarState(td, sx);
									return false;
								}
							}
						}
					}
					if (w != Epoch.WRITE_SHARED) { // write after ordered writes
						if (wTid == tid || Epoch.leq(w, E)) {
							// Current write is ordered too, keep epoch
							sx.W = E;
							xRE.Wm = getHLS(td);
						} else {
							// Current write is not ordered to last write
							if (!hasCommon(xRE.Wm, getHLS(td))) {
								// Write - Write race
								ts_set_badVarState(td, sx);
								return false;
							}
							// Unordered write protected by same lock. Switch to VC
							xRE.makeWriteShared(Util.max(tid, wTid, INIT_VECTOR_CLOCK_SIZE));
							xRE.putWriteShared(tid, getHLS(td));
							xRE.putWriteShared(wTid, xRE.Wm);
							xRE.Wm = null;
							xRE.W = Epoch.WRITE_SHARED;
							xRE.writeVC.set(wTid, w);
							xRE.writeVC.set(tid, E);
							xRE.lastWriterTid = tid;
						}
					} else { // write after unordered writes
						boolean allOrdered = true;
						HeldLS[] writes = xRE.getSharedWrites();
						for (int i = 0; i < writes.length; i++) {
							if (i != tid && writes[i] != null) {
								if (Epoch.leq(xRE.writeVC.get(i), E)) continue;
								if (!hasCommon(writes[i], getHLS(td))) {
									// Write - Write race
									ts_set_badVarState(td, sx);
									return false;
								}
								allOrdered = false;
							}
						}
						if (allOrdered) { // switch to epoch
							xRE.W = E;
							xRE.Wm = getHLS(td);
							xRE.clearSharedWrite();
							xRE.lastWriterTid = -1;
						} else { // still unordered, keep VC
							xRE.putWriteShared(tid, getHLS(td));
							xRE.writeVC.set(tid, E);
							xRE.lastWriterTid = tid;
						}
					}
				} else if (AGG || RE) { //AGG and FTO or ([WCP/DC] + RE) and FTO
					if (!AGG) {
						REVarState xRE = (REVarState)sx;
						if (!xRE.Er.isEmpty()) {
							for (int i = 0; i < td.getNumLocksHeld(); i++) { //outer most to inner most
								ShadowLock lock = td.getHeldLock(i);
								for (int prevTid : xRE.Er.keySet()) {
									if (prevTid != tid && xRE.Er.get(prevTid).containsKey(lock)) {
										maxEpochAndCV(td, xRE.Er.get(prevTid).get(lock));
										xRE.Er.get(prevTid).remove(lock);
										if (xRE.Er.get(prevTid).isEmpty()) xRE.Er.remove(prevTid);
										
										if (xRE.Ew.get(prevTid) != null) xRE.Ew.get(prevTid).remove(lock);
										if (xRE.Ew.get(prevTid) != null && xRE.Ew.get(prevTid).isEmpty()) xRE.Ew.remove(prevTid);
									}
								}
							}
							xRE.Er.remove(tid);
							xRE.Ew.remove(tid);
						}
					}
					
					final int/*epoch*/ r = sx.R;
					final int rTid = Epoch.tid(r);
					if (r != Epoch.READ_SHARED) { //read epoch
						if (rTid == tid) { //Write-Owned. Rule(a) Check is unneeded for write-owned case since the prior read and write access is ordered to the current write access
							//Update last Rule(a) metadata
							REVarState xRE = (REVarState)orig;
							xRE.Wm = getHLS(td);
							xRE.Rm = getHLS(td);
							//Update last access metadata
							sx.W = E;
							sx.R = E;
							if (COUNT_EVENTS) writeOwnedFP.inc(tid);
						} else {
							//Check Rule(a)
							REVarState xRE = (REVarState)sx;
							HeldLS rdLock = xRE.Rm;
							while (rdLock != null) {
								if (Epoch.leq(rdLock.vc.get(rTid), tV.get(rTid))) {
									break; //Outer most lock already ordered to the current access
								} else if (td.equals(rdLock.lock.getHoldingThread())) { //Outer most lock conflicts with current access
									//Establish Rule(a) and avoid checking for read-write race
									if (PRINT_EVENTS) Util.log("rd to wr-exclusive (FP) Rule a: " + rdLock.vc.toString());
									if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
									maxEpochAndCV(td, rdLock.vc);
									if (WCP) tV.set(tid, ts_get_eHB(td)); //WCP union PO
									break;
								} else {
									if (!AGG) {
										HashMap<ShadowLock, VectorClock> rEL = xRE.Er.get(rTid);
										if (rEL == null) {
											rEL = new HashMap<ShadowLock, VectorClock>();
										}
										rEL.put(rdLock.lock, rdLock.vc);
										xRE.Er.put(rTid, rEL);
									}
								}
								rdLock = rdLock.next;
							}
							
							//Is Write
							if (!AGG) {
								HeldLS wrLock = xRE.Wm;
								while (wrLock != null) {
									if (Epoch.leq(wrLock.vc.get(wTid), tV.get(wTid))) {
										break;
									} else if (td.equals(wrLock.lock.getHoldingThread())) {
										break;
									} else {
										HashMap<ShadowLock, VectorClock> wEL = xRE.Ew.get(wTid);
										if (wEL == null) {
											wEL = new HashMap<ShadowLock, VectorClock>();
										}
										wEL.put(wrLock.lock, wrLock.vc);
										xRE.Ew.put(wTid, wEL);
									}
									wrLock = wrLock.next;
								}
							}
							
							if (rdLock == null && !Epoch.leq(r, tV.get(rTid))) {
								ts_set_badVarState(td, sx);
								if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
								return false;
							} //Write-Exclusive
							//Update last Rule(a) metadata
							xRE.Wm = getHLS(td);
							xRE.Rm = getHLS(td);
							//Update last access metadata
							sx.W = E;
							sx.R = E;
							if (COUNT_EVENTS) writeExclusiveFP.inc(tid);
						}
					} else { //read vector
						//Rule(a) Check is pushed to slow path for all threads if a read by any thread races with the current write access
						if (sx.anyGt(tV)) {
							ts_set_badVarState(td, sx);
							if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
							return false;
						} else { //Write-Shared
							//Rule(a) Check
							REVarState xRE = (REVarState)sx;
							for (int prevRdTid = 0; prevRdTid < xRE.SharedRm.length; prevRdTid++) {
								HeldLS rdShrLock = xRE.getSharedHeldLS(prevRdTid);
								while (rdShrLock != null) {
									if (prevRdTid != tid) {
										if (Epoch.leq(rdShrLock.vc.get(prevRdTid), tV.get(prevRdTid))) {
											break; //Outer most lock already ordered to the current access
										} else if (td.equals(rdShrLock.lock.getHoldingThread())) { //Outer most lock conflicts with current access
											//Establish Rule(a); Race Check already done
											if (PRINT_EVENTS) Util.log("rd to wr-shared (FP) Rule a: " + rdShrLock.vc.toString());
											if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
											maxEpochAndCV(td, rdShrLock.vc);
											if (WCP) tV.set(tid, ts_get_eHB(td)); //WCP union PO
											break;
										} else {
											if (!AGG) {
												HashMap<ShadowLock, VectorClock> rShrEL = xRE.Er.get(prevRdTid);
												if (rShrEL == null) {
													rShrEL = new HashMap<ShadowLock, VectorClock>();
												}
												rShrEL.put(rdShrLock.lock, rdShrLock.vc);
												xRE.Er.put(prevRdTid, rShrEL);
											}
										}
									}
									rdShrLock = rdShrLock.next;
								}
								
								//Is Write
								if (!AGG) {
									HeldLS wrLock = xRE.Wm;
									while (wrLock != null) {
										if (Epoch.leq(wrLock.vc.get(wTid), tV.get(wTid))) {
											break;
										} else if (td.equals(wrLock.lock.getHoldingThread())) {
											break;
										} else {
											HashMap<ShadowLock, VectorClock> wEL = xRE.Ew.get(wTid);
											if (wEL == null) {
												wEL = new HashMap<ShadowLock, VectorClock>();
											}
											wEL.put(wrLock.lock, wrLock.vc);
											xRE.Ew.put(wTid, wEL);
										}
										wrLock = wrLock.next;
									}
								}
							}
							//Update last Rule(a) metadata
							xRE.Wm = getHLS(td);
							xRE.clearSharedHeldLS();
							xRE.Rm = getHLS(td);
							//Update last access metadata
							sx.W = E;
							sx.R = E;
							if (COUNT_EVENTS) writeSharedFP.inc(tid);
						}
					}
				}
				
				//FTO but not AGG nor [WCP/DC] + RE
				if (!AGG && !RE && FTO) {
					if (!HB) {
					//Establish Rule(a)
					for (int i = 0; i < td.getNumLocksHeld(); i++) { //outer most to inner most
						ShadowLock lock = td.getHeldLock(i);
						if (WCP) {
							if (DEBUG) Assert.assertTrue(getV(lock) instanceof WCPLockState);
							WCPLockState lockData = (WCPLockState)getV(lock);
							//Establish Rule(a)
							tV.set(tid, getE(td)); //revert WCP union PO
							VectorClock priorCSAfterAccess = lockData.WriteMap.get(sx);
							if (priorCSAfterAccess != null) {
								maxEpochAndCV(td, priorCSAfterAccess);
							}
							priorCSAfterAccess = lockData.ReadMap.get(sx);
							if (priorCSAfterAccess != null) {
								maxEpochAndCV(td, priorCSAfterAccess);
							}
							tV.set(tid, ts_get_eHB(td)); //WCP union PO
							//Update write/read Vars
							lockData.writeVars.add(sx);
							lockData.readVars.add(sx);
						}
						if (DC) {
							if (DEBUG) Assert.assertTrue(getV(lock) instanceof DCLockState);
							DCLockState lockData = (DCLockState)getV(lock);
							//Establish Rule(a)
							VectorClock priorCSAfterAccess = lockData.WriteMap.get(sx);
							if (priorCSAfterAccess != null) {
								maxEpochAndCV(td, priorCSAfterAccess);
							}
							priorCSAfterAccess = lockData.ReadMap.get(sx);
							if (priorCSAfterAccess != null) {
								maxEpochAndCV(td, priorCSAfterAccess);
							}
							//Update write/read Vars
							lockData.writeVars.add(sx);
							lockData.readVars.add(sx);
						}
						if (CAPO) {
							if (DEBUG) Assert.assertTrue(getV(lock) instanceof CAPOLockState);
							CAPOLockState lockData = (CAPOLockState)getV(lock);
							//Establish Rule(a)
							VectorClock priorCSAfterAccess = lockData.WriteMap.get(sx);
							if (priorCSAfterAccess != null) {
								maxEpochAndCV(td, priorCSAfterAccess);
							}
							priorCSAfterAccess = lockData.ReadMap.get(sx);
							if (priorCSAfterAccess != null) {
								maxEpochAndCV(td, priorCSAfterAccess);
							}
							//Update write/read Vars
							lockData.writeVars.add(sx);
							lockData.readVars.add(sx);
						}
					}
					}
					
					final int/*epoch*/ r = sx.R;
					final int rTid = Epoch.tid(r);
					if (r != Epoch.READ_SHARED) { //read epoch
						if (rTid == tid) { //Write-Owned
							//Update last access metadata
							sx.W = E;
							sx.R = E;
							if (COUNT_EVENTS) writeOwnedFP.inc(tid);
						} else {
							if (!Epoch.leq(r, tV.get(rTid))) {
								ts_set_badVarState(td, sx);
								if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
								return false;
							} //Write-Exclusive
							//Update last access metadata
							sx.W = E;
							sx.R = E;
							if (COUNT_EVENTS) writeExclusiveFP.inc(tid);
						}
					} else { //read vector
						if (sx.anyGt(tV)) {
							ts_set_badVarState(td, sx);
							if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
							return false;
						} //Write-Shared
						//Update last access metadata
						sx.W = E;
						sx.R = E;
						if (COUNT_EVENTS) writeSharedFP.inc(tid);
					}
				}
				
				//Not AGG nor FTO nor [WCP/DC] + RE
				if (!AGG && !RE && !FTO) {
					//Write-Write Race Check.
					if (wTid != tid && !Epoch.leq(w, tV.get(wTid))) {
						ts_set_badVarState(td, sx);
						if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
						return false;
					}
					
					final int/*epoch*/ r = sx.R;
					if (r != Epoch.READ_SHARED) {	
						//Read-Write Race Check.
						final int rTid = Epoch.tid(r);
						if (rTid != tid && !Epoch.leq(r, tV.get(rTid))) {
							ts_set_badVarState(td, sx);
							if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
							return false;
						}
						if (COUNT_EVENTS) writeExclusiveFP.inc(tid);
					} else {	
						//Read(Shr)-Write Race Check.
						if (sx.anyGt(tV)) {
							ts_set_badVarState(td, sx);
							if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
							return false;
						}
						if (COUNT_EVENTS) writeSharedFP.inc(tid);
					}
					
					//Update last access metadata
					sx.W = E; //Write-Exclusive; -Shared
				}
				
				//Counting and WCP update
				if (COUNT_EVENTS) writeFP.inc(td.getTid());
				if (COUNT_EVENTS) {
					if (td.getNumLocksHeld() == 0) {
						writeOUT.inc(td.getTid());
						writeOUTFP.inc(td.getTid());
					} else {
						writeIN.inc(td.getTid());
						writeINFP.inc(td.getTid());
					}
				}
				if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
				return true;
			}
		} else {
			return false;
		}
	}
	
	protected void write(final AccessEvent event, final ShadowThread td, final PIPVarState x) {
		int/*epoch*/ e;
		if (WCP) {
			e = ts_get_eHB(td);
		} else {
			e = getE(td);
		}
		
		if (COUNT_EVENTS) {
			if (td.getNumLocksHeld() == 0) {
				writeOUT.inc(td.getTid());
			} else {
				writeIN.inc(td.getTid());
			}
		}
		
		/* optional */ {
			final int/*epoch*/ w = x.W;
			if (w == e) {
				if (COUNT_EVENTS) writeSameEpoch.inc(td.getTid());
				return;
			}
		}
		
		synchronized(x) {
			final int/*epoch*/ w = x.W;
			final int wTid = Epoch.tid(w);
			final int tid = td.getTid();
			final VectorClock tV = getV(td);
			if (WCP) tV.set(tid, ts_get_eHB(td)); //WCP union PO
			
			if (COUNT_EVENTS) {
				if (td.getNumLocksHeld() > 0) {
					holdLocks.inc(tid);
					if (td.getNumLocksHeld() == 1) {
						oneLockHeld.inc(tid);
					} else if (td.getNumLocksHeld() == 2) {
						twoNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() == 3) {
						threeNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() == 4) {
						fourNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() == 5) {
						fiveNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() == 6) {
						sixNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() == 7) {
						sevenNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() == 8) {
						eightNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() == 9) {
						nineNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() == 10) {
						tenNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() <= 100) {
						hundredNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() > 100) {
						manyNestedLocksHeld.inc(tid);
					}
				}
			}
			
			// Find the shortest race 
			int shortestRaceTid = -1;
			boolean shortestRaceIsWrite = false;
			String shortestRaceType = "";

			if (WDP) {
				WDPVarState xRE = (WDPVarState)x;
				final int r = x.R;
				final int rTid = Epoch.tid(r);
				if (r != Epoch.READ_SHARED) { // write after ordered reads
					if (rTid != tid && !Epoch.leq(r, e) && !hasCommon(xRE.Rm, getHLS(td))) {
						// Read - Write race
						error(event, x, "Read - Write race", "Read by ", rTid, "Write by ", tid);
					}
				} else { // write after unordered reads
					HeldLS[] reads = xRE.getSharedReads();
					for (int i = 0; i < reads.length; i++) {
						if (i != tid && reads[i] != null) {
							if(!Epoch.leq(xRE.get(i), e) && !hasCommon(reads[i], getHLS(td))) {
								// Read - Write race
								error(event, x, "Read - Write race", "Read by ", i, "Write by ", tid); // TODO: Count shortest race only
							}
						}
					}
				}
				if (w != Epoch.WRITE_SHARED) { // write after ordered writes
					if (wTid == tid || Epoch.leq(w, e)) {
						// Current write is ordered too, keep epoch
						x.W = e;
						xRE.Wm = getHLS(td);
					} else {
						// Current write is not ordered to last write
						if (!hasCommon(xRE.Wm, getHLS(td))) {
							// Write - Write race
							error(event, x, "Write - Write race", "Write by ", wTid, "Write by ", tid); // TODO: Count shortest race only
						}
						// Unordered write protected by same lock. Switch to VC
						xRE.makeWriteShared(Util.max(tid, wTid, INIT_VECTOR_CLOCK_SIZE));
						xRE.putWriteShared(tid, getHLS(td));
						xRE.putWriteShared(wTid, xRE.Wm);
						xRE.Wm = null;
						xRE.W = Epoch.WRITE_SHARED;
						xRE.writeVC.set(wTid, w);
						xRE.writeVC.set(tid, e);
						xRE.lastWriterTid = tid;
					}
				} else { // write after unordered writes
					boolean allOrdered = true;
					HeldLS[] writes = xRE.getSharedWrites();
					for (int i = 0; i < writes.length; i++) {
						if (i != tid && writes[i] != null) {
							if (Epoch.leq(xRE.writeVC.get(i), e)) continue;
							if (!hasCommon(writes[i], getHLS(td))) {
								// Write - Write race
								error(event, x, "Write - Write race", "Write by ", i, "Write by ", tid);
							}
							allOrdered = false;
						}
					}
					if (allOrdered) { // switch to epoch
						xRE.W = e;
						xRE.Wm = getHLS(td);
						xRE.clearSharedWrite();
						xRE.lastWriterTid = -1;
					} else { // still unordered, keep VC
						xRE.putWriteShared(tid, getHLS(td));
						xRE.writeVC.set(tid, e);
						xRE.lastWriterTid = tid;
					}
				}
			} else if (AGG || RE) { //AGG and FTO or ([WCP/DC] + RE) and FTO
				final int/*epoch*/ r = x.R;
				final int rTid = Epoch.tid(r);
				if (r != Epoch.READ_SHARED) { //read epoch
					if (rTid == tid) { //Write-Owned. Rule(a) Check is unneeded for write-owned case since the prior read and write access is ordered to the current write access
						//Update last Rule(a) metadata
						REVarState xRE = (REVarState)x;
						xRE.Wm = getHLS(td);
						xRE.Rm = getHLS(td);
						//Update last access metadata
						x.W = e;
						x.R = e;
						if (COUNT_EVENTS) writeOwned.inc(tid);
					} else {
						//Check Rule(a)
						REVarState xRE = (REVarState)x;
						HeldLS rdLock = xRE.Rm;
						while (rdLock != null) {
							if (!Epoch.leq(rdLock.vc.get(rTid), tV.get(rTid)) && td.equals(rdLock.lock.getHoldingThread())) {
								//Establish Rule(a) and avoid checking for read-write race
								if (PRINT_EVENTS) Util.log("rd to wr-exclusive (FP) Rule a: " + rdLock.vc.toString());
								if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
								maxEpochAndCV(td, rdLock.vc);
								if (WCP) tV.set(tid, ts_get_eHB(td)); //WCP union PO
								break;
							}
							rdLock = rdLock.next;
						}
						if (rdLock == null && !Epoch.leq(r, tV.get(rTid))) {
							shortestRaceTid = rTid;
							shortestRaceIsWrite = false;
							shortestRaceType = "Read-Write Race";
							//Update vector clocks to make execution race free, w represents WCP union PO of the prior access
							if (WCP) tV.set(wTid, Epoch.max(tV.get(wTid), w));
							if (!WCP) tV.set(wTid, w);
							//Both last read and last write must be ordered to the current write for ownership.
							if (WCP) tV.set(rTid, Epoch.max(tV.get(rTid), r));
							if (!WCP) tV.set(rTid, r);
							if (COUNT_EVENTS) readWriteError.inc(tid);
						}
						// Report shortest race
						if (shortestRaceTid >= 0) {
							error(event, x, shortestRaceType, shortestRaceIsWrite ? "Write by " : "Read by ", shortestRaceTid, "Write by ", tid);
						} //Write-Exclusive
						//Update last Rule(a) metadata
						xRE.Wm = getHLS(td);
						xRE.Rm = getHLS(td);
						//Update last access metadata
						x.W = e;
						x.R = e;
						if (COUNT_EVENTS) writeExclusive.inc(tid);
					}
				} else { //read vector
					//Rule(a) Check
					REVarState xRE = (REVarState)x;
					for (int prevRdTid = 0; prevRdTid < xRE.SharedRm.length; prevRdTid++) {
						HeldLS rdShrLock = xRE.getSharedHeldLS(prevRdTid);
						while (rdShrLock != null) {
							if (prevRdTid != tid && !Epoch.leq(rdShrLock.vc.get(prevRdTid), tV.get(prevRdTid)) && td.equals(rdShrLock.lock.getHoldingThread())) {
								//Establish Rule(a); Race Check already done
								if (PRINT_EVENTS) Util.log("rd to wr-shared (FP) Rule a: " + rdShrLock.vc.toString());
								if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
								maxEpochAndCV(td, rdShrLock.vc);
								if (WCP) tV.set(tid, ts_get_eHB(td)); //WCP union PO
								break;
							}
							rdShrLock = rdShrLock.next;
						}
						if (rdShrLock == null && prevRdTid != tid && !Epoch.leq(x.get(prevRdTid), tV.get(prevRdTid))) {
							shortestRaceTid = prevRdTid;
							shortestRaceIsWrite = false;
							shortestRaceType = "Read(Shared)-Write Race";
							//Update vector clocks to make execution race free, w represents WCP union PO of the prior access
							if (WCP) tV.set(wTid, Epoch.max(tV.get(wTid), w));
							if (!WCP) tV.set(wTid, w);
							//Both last read and last write must be ordered to the current write for ownership.
							tV.max(x); //read-shared							
							if (COUNT_EVENTS) sharedWriteError.inc(tid);
						}
					}
					// Report shortest race
					if (shortestRaceTid >= 0) {
						error(event, x, shortestRaceType, shortestRaceIsWrite ? "Write by " : "Read by ", shortestRaceTid, "Write by ", tid);
					} //Write-Shared
					//Update last Rule(a) metadata
					xRE.Wm = getHLS(td);
					xRE.clearSharedHeldLS();
					xRE.Rm = getHLS(td);
					//Update last access metadata
					x.W = e;
					x.R = e;
					if (COUNT_EVENTS) writeShared.inc(tid);
				}
			}
			
			//FTO but not AGG nor [WCP/DC] + RE
			if (!AGG && !RE && FTO) {
				final int/*epoch*/ r = x.R;
				final int rTid = Epoch.tid(r);
				if (r != Epoch.READ_SHARED) { //read epoch
					if (rTid == tid) { //Write-Owned
						//Update last access metadata
						x.W = e;
						x.R = e;
						if (COUNT_EVENTS) writeOwned.inc(tid);
					} else {
						if (!Epoch.leq(r, tV.get(rTid))) {
							if (PRINT_EVENTS) Util.log("rd-wr exclusive error");
							shortestRaceTid = rTid;
							shortestRaceIsWrite = false;
							shortestRaceType = "Read-Write Race";
							//Update vector clocks to make execution race free, w represents WCP union PO of the prior access
							if (WCP) tV.set(wTid, Epoch.max(tV.get(wTid), w));
							if (!WCP) tV.set(wTid, w);
							//Both last read and last write must be ordered to the current write for ownership.
							if (WCP) tV.set(rTid, Epoch.max(tV.get(rTid), r));
							if (!WCP) tV.set(rTid, r);
							if (COUNT_EVENTS) readWriteError.inc(tid);
						}
						// Report shortest race
						if (shortestRaceTid >= 0) {
							error(event, x, shortestRaceType, shortestRaceIsWrite ? "Write by " : "Read by ", shortestRaceTid, "Write by ", tid);
						} //Write-Exclusive
						//Update last access metadata
						x.W = e;
						x.R = e;
						if (COUNT_EVENTS) writeExclusive.inc(tid);
					}
				} else { //read vector
					if (x.anyGt(tV)) {
						//Check for Read-Write race
						for (int prevReader = x.nextGt(tV, 0); prevReader > -1; prevReader = x.nextGt(tV, prevReader + 1)) {
							if (PRINT_EVENTS) Util.log("rd-wr share error");
							shortestRaceTid = prevReader;
							shortestRaceIsWrite = false;
							shortestRaceType = "Read(Shared)-Write Race";
						}
						//Update vector clocks to make execution race free, w represents WCP union PO of the prior access
						if (WCP) tV.set(wTid, Epoch.max(tV.get(wTid), w));
						if (!WCP) tV.set(wTid, w);
						tV.max(x); //read-shared
						if (COUNT_EVENTS) sharedWriteError.inc(tid);
					}
					// Report shortest race
					if (shortestRaceTid >= 0) {
						error(event, x, shortestRaceType, shortestRaceIsWrite ? "Write by " : "Read by ", shortestRaceTid, "Write by ", tid);
					} //Write-Shared
					//Update last access metadata
					x.W = e;
					x.R = e;
					if (COUNT_EVENTS) writeShared.inc(tid);
				}
			}
			
			//Not AGG nor FTO nor [WCP/DC] + RE
			if (!AGG && !RE && !FTO) {
				//Write-Write Race Check.
				if (wTid != tid && !Epoch.leq(w, tV.get(wTid))) {
					shortestRaceTid = wTid;
					shortestRaceIsWrite = true;
					shortestRaceType = "Write-Write Race";
					if (COUNT_EVENTS) writeWriteError.inc(tid);
				}
				
				final int/*epoch*/ r = x.R;
				if (r != Epoch.READ_SHARED) {	
					//Read-Write Race Check.
					final int rTid = Epoch.tid(r);
					if (rTid != tid && !Epoch.leq(r, tV.get(rTid))) {
						shortestRaceTid = rTid;
						shortestRaceIsWrite = false;
						shortestRaceType = "Read-Write Race";
						if (COUNT_EVENTS) readWriteError.inc(tid);
					}
					if (COUNT_EVENTS) writeExclusive.inc(tid);
				} else {	
					//Read(Shr)-Write Race Check.
					if (x.anyGt(tV)) {
						for (int prevReader = x.nextGt(tV, 0); prevReader > -1; prevReader = x.nextGt(tV, prevReader + 1)) {
							shortestRaceTid = prevReader;
							shortestRaceIsWrite = false;
							shortestRaceType = "Read(Shared)-Write Race";
						}
						if (COUNT_EVENTS) sharedWriteError.inc(tid);
					}
					if (COUNT_EVENTS) writeShared.inc(tid);
				}
				
				//Update vector clocks to make execution race free
				if (shortestRaceTid >= 0) {
					error(event, x, shortestRaceType, shortestRaceIsWrite ? "Write by " : "Read by ", shortestRaceTid, "Write by ", tid);
					if (shortestRaceIsWrite) {
						//Update vector clocks to make execution race free, w represents WCP union PO of the prior access
						if (WCP) tV.set(wTid, Epoch.max(tV.get(wTid), w));
						if (!WCP) tV.set(wTid, w);
					} else {
						if (r == Epoch.READ_SHARED) {
							tV.max(x); //The variable's VC is the read shared VC of the variable
						} else {
							int rTid = Epoch.tid(r);
							//Update vector clocks to make execution race free
							if (WCP) tV.set(rTid, Epoch.max(tV.get(rTid), r));
							if (!WCP) tV.set(rTid, r);
						}
					}
				}
				
				//Update last access metadata
				x.W = e; //Write-Exclusive; -Shared
			}
			
			if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
		}
	}
	
	@Override
	public void access(final AccessEvent event) {
		final ShadowThread td = event.getThread();
		final ShadowVar orig = getOriginalOrBad(event.getOriginalShadow(), td);
		
		if (orig instanceof PIPVarState) {
			Object target = event.getTarget();
			
			if (target == null) {
				ClassInfo owner = ((FieldAccessEvent)event).getInfo().getField().getOwner();
				synchronized(classInitTime) {
					if (WCP) {
						VectorClock initTimehb = classInitTimeWCPHB.get(owner);
						ts_get_vHB(td).max(initTimehb);
						ts_set_eHB(td, ts_get_vHB(td).get(td.getTid())); //TODO: update after max?
						
						maxEpochAndCV(td, initTimehb);
					}
					if (!WCP) {
						VectorClock initTime = classInitTime.get(owner);
						maxEpochAndCV(td, initTime);
					}
				}
			}
			
			if (COUNT_EVENTS) {
				if (!RE && !FTO) {
				final int tid = td.getTid();
				if (td.getNumLocksHeld() > 0) {
					holdLocks.inc(tid);
					if (td.getNumLocksHeld() == 1) {
						oneLockHeld.inc(tid);
					} else if (td.getNumLocksHeld() == 2) {
						twoNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() == 3) {
						threeNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() == 4) {
						fourNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() == 5) {
						fiveNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() == 6) {
						sixNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() == 7) {
						sevenNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() == 8) {
						eightNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() == 9) {
						nineNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() == 10) {
						tenNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() <= 100) {
						hundredNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() > 100) {
						manyNestedLocksHeld.inc(tid);
					}
				}
				}
			}
			
			//FTO establishes Rule(a) during [read/write]FastPath events
			//RE establishes Rule(a) based on [read/write] cases at read[readFP]/write[writeFP] events
			if (!FTO && !RE) {
				if (!HB) {
					PIPVarState x = (PIPVarState)orig;
					for (int i = td.getNumLocksHeld() - 1; i >= 0; i--) {
						ShadowLock lock = td.getHeldLock(i);
						if (WCP) {
							if (DEBUG) Assert.assertTrue(getV(lock) instanceof WCPLockState);
							WCPLockState lockData = (WCPLockState)getV(lock);
							VectorClock priorCSAfterAccess = lockData.WriteMap.get(x);
							//Establish Rule(a)
							if (priorCSAfterAccess != null) {
								maxEpochAndCV(td, priorCSAfterAccess);
							}
							if (event.isWrite()) {
								priorCSAfterAccess = lockData.ReadMap.get(x);
								if (priorCSAfterAccess != null) {
									maxEpochAndCV(td, priorCSAfterAccess);
								}
							}
							//Update write/read Vars
							if (event.isWrite()) {
								lockData.writeVars.add(x);
							} else {
								lockData.readVars.add(x);
							}
						}
						if (DC) {
							if (DEBUG) Assert.assertTrue(getV(lock) instanceof DCLockState);
							DCLockState lockData = (DCLockState)getV(lock);
							VectorClock priorCSAfterAccess = lockData.WriteMap.get(x);
							//Establish Rule(a)
							if (priorCSAfterAccess != null) {
								maxEpochAndCV(td, priorCSAfterAccess);
							}
							if (event.isWrite()) {
								priorCSAfterAccess = lockData.ReadMap.get(x);
								if(priorCSAfterAccess != null) {
									maxEpochAndCV(td, priorCSAfterAccess);
								}
							}
							//Update write/read Vars
							if (event.isWrite()) {
								lockData.writeVars.add(x);
							} else {
								lockData.readVars.add(x);
							}
						}
						if (CAPO) {
							if (DEBUG) Assert.assertTrue(getV(lock) instanceof CAPOLockState);
							CAPOLockState lockData = (CAPOLockState)getV(lock);
							VectorClock priorCSAfterAccess = lockData.WriteMap.get(x);
							//Establish Rule(a)
							if (priorCSAfterAccess != null) {
								maxEpochAndCV(td, priorCSAfterAccess);
							}
							if (event.isWrite()) {
								priorCSAfterAccess = lockData.ReadMap.get(x);
								if (priorCSAfterAccess != null) {
									maxEpochAndCV(td, priorCSAfterAccess);
								}
							}
							//Update write/read Vars
							if (event.isWrite()) {
								lockData.writeVars.add(x);
							} else {
								lockData.readVars.add(x);
							}
						}
					}
				}
			}
			
			if (event.isWrite()) {
				PIPVarState x = (PIPVarState)orig;
				write(event, td, x);
			} else {
				PIPVarState x = (PIPVarState)orig;
				read(event, td, x);
			}
			
			if (PRINT_EVENTS) {
				String fieldName = "";
				if (event instanceof FieldAccessEvent) {
					fieldName = ((FieldAccessEvent)event).getInfo().getField().getName();						
				} else if (event instanceof ArrayAccessEvent) {
					fieldName = Util.objectToIdentityString(event.getTarget()) + "[" + ((ArrayAccessEvent)event).getIndex() + "]";
				}
				PIPVarState x = (PIPVarState)orig;
				if (event.isWrite()) {
					Util.log("wr("+ fieldName +") by T"+td.getTid() + " | epoch: " + x.toString());
				} else {
					Util.log("rd("+ fieldName +") by T"+td.getTid() + " | epoch: " + x.toString());
				}
			}
		} else {
			Util.log("Not expecting to reach here for access event: " + event.getClass() + " | original shadow: " + event.getOriginalShadow());
			Assert.assertTrue(false); //Not expecting to reach here
			super.access(event);
		}
	}
	
	@Override
	public void volatileAccess(final VolatileAccessEvent event) {
		final ShadowThread td = event.getThread();
		final PIPVolatileState vd = getV(event.getShadowVolatile());
		final VectorClock volV = getV(event.getShadowVolatile());
		
		if (COUNT_EVENTS) vol.inc(td.getTid());
		
		//Vindicator synchronizes on volV, but FT2 does not.
		if(event.isWrite()) {
			final VectorClock tV = getV(td);
			if (WCP) {
				final VectorClock volVhb = volatileVwcpHB.get(event.getShadowVolatile());
				final PIPVolatileState vdhb = volatileVwcpHB.get(event.getShadowVolatile());
				//incomming rd-wr edge
				ts_get_vHB(td).max(vdhb.readsJoined);
				ts_get_vWCP(td).max(vdhb.readsJoined);
				//incomming wr-wr edge
				ts_get_vHB(td).max(volVhb);
				ts_get_vWCP(td).max(volVhb);
				//outgoing wr-wr and wr-rd edge
				volV.max(ts_get_vHB(td));
				ts_set_eWCP(td, ts_get_vWCP(td).get(td.getTid())); //TODO: don't set for max?
				
				volVhb.max(ts_get_vHB(td));
				ts_get_vHB(td).tick(td.getTid());
				ts_set_eHB(td, ts_get_vHB(td).get(td.getTid()));
			}
			if (!WCP) {
				if (!WDP) {
					//incomming rd-wr edge
					tV.max(vd.readsJoined);
					//incomming wr-wr edge
					tV.max(volV);//volV -> vd.write
				}
				//outgoing wr-wr and wr-rd edge
				volV.max(tV);
				incEpochAndCV(td);
			}
		} else {
			final VectorClock tV = getV(td);
			if (WCP) {
				final VectorClock volVhb = volatileVwcpHB.get(event.getShadowVolatile());
				final PIPVolatileState vdhb = volatileVwcpHB.get(event.getShadowVolatile());
				//incomming wr-rd edge
				ts_get_vHB(td).max(volVhb);
				ts_set_eHB(td, ts_get_vHB(td).get(td.getTid()));
				maxEpochAndCV(td, volVhb);
				//outgoing rd-wr edge
				vdhb.readsJoined.max(ts_get_vHB(td));
				vd.readsJoined.max(ts_get_vHB(td));
				ts_get_vHB(td).tick(td.getTid());
				ts_set_eHB(td, ts_get_vHB(td).get(td.getTid()));
			}
			if (!WCP) {
				if (!WDP) {
					//incomming wr-rd edge
					maxEpochAndCV(td, volV);//volV -> vd.write
					//outgoing rd-wr edge
					vd.readsJoined.max(tV);
					incEpochAndCV(td);
				} else { // WDP
					if (DEBUG) volV.assertNoMax();
					ts_get_bWDP(td).max(volV);
					ts_set_beWDP(td, true);
				}
			}
		}
		
		if (PRINT_EVENTS) {
			String fieldName = event.getInfo().getField().getName();
			if (event.isWrite()) {
				Util.log("volatile wr("+ fieldName +") by T"+td.getTid());
			} else {
				Util.log("volatile rd("+ fieldName +") by T"+td.getTid());
			}
		}
		super.volatileAccess(event);
	}

	@Override
	public void branch(BranchEvent be) {
		if (WDP) {
			final ShadowThread td = be.getThread();
			if (ts_get_beWDP(td)) {
				final VectorClock b = ts_get_bWDP(td);
				if (DEBUG) b.assertNoMax();
				maxEpochAndCV(td, b);
				ts_set_beWDP(td, false);
			}
		}
	}

	@Override
	public void preStart(final StartEvent event) {
		final ShadowThread td = event.getThread();
		final ShadowThread forked = event.getNewThread();
		final VectorClock tV = getV(td);
		
		if (COUNT_EVENTS) fork.inc(td.getTid());
		
		//FT2 inc, Vindicator claims not needed since create() does an increment
		if (WCP) {
			getV(forked).max(ts_get_vHB(td));
			ts_set_eWCP(forked, ts_get_vWCP(forked).get(forked.getTid())); //TODO: don't set for max?
			
			ts_get_vHB(forked).max(ts_get_vHB(td));
			ts_get_vHB(td).tick(td.getTid());
			ts_set_eHB(td, ts_get_vHB(td).get(td.getTid()));
		}
		if (!WCP) {
			maxEpochAndCV(forked, tV);
			incEpochAndCV(td);
		}
		
		if (PRINT_EVENTS) Util.log("preStart by T"+td.getTid());
		super.preStart(event);
	}
	
	@Override
	public void postJoin(final JoinEvent event) {
		final ShadowThread td = event.getThread();
		final ShadowThread joining = event.getJoiningThread();
		
		if (COUNT_EVENTS) join.inc(td.getTid());
		
		if (WCP) {
			getV(td).max(ts_get_vHB(joining));
			ts_set_eWCP(td, ts_get_vWCP(td).get(td.getTid())); //TODO: don't set for max?
			
			ts_get_vHB(td).max(ts_get_vHB(joining));
			ts_set_eHB(td, ts_get_vHB(td).get(td.getTid()));
		}
		if (!WCP) {
			maxEpochAndCV(td, getV(joining));
		}
		
		if (PRINT_EVENTS) Util.log("postJoin by T"+td.getTid());
		super.postJoin(event);
	}
	
	@Override
	public void preWait(WaitEvent event) {
		final ShadowThread td = event.getThread();
		
		if (COUNT_EVENTS) preWait.inc(td.getTid());
		
		if (HB) {
			final VectorClock lockV = getV(event.getLock());
			lockV.max(getV(td)); // we hold lock, so no need to sync here...
			incEpochAndCV(td);
		}
		if (!HB) {
			handleRelease(td, getV(event.getLock()), event.getInfo());
		}
		
		if (PRINT_EVENTS) Util.log("preWait by T"+td.getTid());
		super.preWait(event);
	}
	
	@Override
	public void postWait(WaitEvent event) {
		final ShadowThread td = event.getThread();
		final ShadowLock lock = event.getLock();
		
		if (COUNT_EVENTS) postWait.inc(td.getTid());
		
		if (HB) {
			maxEpochAndCV(td, getV(lock)); // we hold lock here
		}
		if (!HB) {
			handleAcquireHardEdge(td, lock, event.getInfo());
		}
		
		if (PRINT_EVENTS) Util.log("postWait by T"+td.getTid());
		super.postWait(event);
	}
	
	@Override
	public void preDoBarrier(BarrierEvent<PIPBarrierState> be) {
		// TODO Auto-generated method stub
		Assert.assertTrue(false);
		
	}

	@Override
	public void postDoBarrier(BarrierEvent<PIPBarrierState> be) {
		// TODO Auto-generated method stub
		Assert.assertTrue(false);
		
	}
	
	@Override
	public void classInitialized(ClassInitializedEvent event) {
		final ShadowThread td = event.getThread();
		final VectorClock tV = getV(td);
		
		if (COUNT_EVENTS) classInit.inc(td.getTid());
		
		synchronized(classInitTime) {
			VectorClock initTime = classInitTime.get(event.getRRClass());
			initTime.copy(tV);
			if (WCP) {
				VectorClock initTimehb = classInitTimeWCPHB.get(event.getRRClass());
				initTimehb.copy(ts_get_vHB(td));
			}
		}
		if (WCP) {			
			ts_get_vHB(td).tick(td.getTid());
			ts_set_eHB(td, ts_get_vHB(td).get(td.getTid()));
		}
		if (!WCP) {
			incEpochAndCV(td);
		}
		
		if (PRINT_EVENTS) Util.log("classInitialized by T"+td.getTid());
		super.classInitialized(event);
	}
	
	@Override
	public void classAccessed(ClassAccessedEvent event) {
		final ShadowThread td = event.getThread();
		
		if (COUNT_EVENTS) classAccess.inc(td.getTid());
		
		synchronized(classInitTime) {
			final VectorClock initTime = classInitTime.get(event.getRRClass());
			if (WCP) {
				final VectorClock initTimehb = classInitTimeWCPHB.get(event.getRRClass());
				getV(td).max(initTimehb);
				ts_set_eWCP(td, ts_get_vWCP(td).get(td.getTid())); // TODO: don't set for max?
				
				ts_get_vHB(td).max(initTimehb);
				ts_set_eHB(td, ts_get_vHB(td).get(td.getTid()));
			}
			if (!WCP) {
				maxEpochAndCV(td, initTime);
			}
		}
		
		if (PRINT_EVENTS) Util.log("classAccessed by T"+td.getTid());
	}
	
	public static String toString(final ShadowThread td) {
		return String.format("[tid=%-2d C=%s E=%s]", td.getTid(), getV(td), Epoch.toString(getE(td)));
	}
	
	protected void recordRace(final AccessEvent event) {
		StaticRace staticRace = new StaticRace(event.getAccessInfo().getLoc());
		StaticRace.addRace(staticRace);
	}
	
	protected void error(final AccessEvent event, final PIPVarState x, final String description, final String prevOp, final int prevTid, final String curOp, final int curTid) {
		if (COUNT_RACES) {
			recordRace(event);
			// Don't bother printing error during performance execution. All race information is collected using StaticRace now.
			//Update: ErrorMessage has added errorQuite so that race counting prints nothing during execution.
			if (COUNT_EVENTS) {
				if (event instanceof FieldAccessEvent) {
					fieldError((FieldAccessEvent) event, x, description, prevOp, prevTid, curOp, curTid);
				} else {
					arrayError((ArrayAccessEvent) event, x, description, prevOp, prevTid, curOp, curTid);
				}
			}
		}
	}
	
	protected void fieldError(final FieldAccessEvent event, final PIPVarState x, final String description, final String prevOp, final int prevTid, final String curOp, final int curTid) {
		final FieldInfo fd = event.getInfo().getField();
		final ShadowThread td = event.getThread();
		final Object target = event.getTarget();
		
//		if (fieldErrors.stillLooking(fd)) {
			fieldErrors.errorQuite(td,
					fd, 
					"Shadow State",		x,
					"Current Thread",	toString(td),
					"Class",			(target==null?fd.getOwner():target.getClass()),
					"Field",			Util.objectToIdentityString(target) + "." + fd,
					"Message",			description,
					"Previous Op",		prevOp + " " + ShadowThread.get(prevTid),
					"Current Op",		curOp + " " + ShadowThread.get(curTid),
					"Stack",			ShadowThread.stackDumpForErrorMessage(td));
//		}
		
		if (DEBUG) Assert.assertTrue(prevTid != curTid);
		
//		if (!fieldErrors.stillLooking(fd)) {
//			advance(event);
//		}
	}
	
	protected void arrayError(final ArrayAccessEvent event, final PIPVarState x, final String description, final String prevOp, final int prevTid, final String curOp, final int curTid) {
		final ShadowThread td = event.getThread();
		final Object target = event.getTarget();
		
//		if (arrayErrors.stillLooking(event.getInfo())) {
			arrayErrors.errorQuite(td, 
					event.getInfo(),
					"Alloc Site",		ArrayAllocSiteTracker.get(target),
					"Shadow State",		x,
					"Current Thread",	toString(td),
					"Array",			Util.objectToIdentityString(target) + "[" + event.getIndex() + "]",
					"Message",			description,
					"Previous Op",		prevOp + " " + ShadowThread.get(prevTid),
					"Current Op",		curOp + " " + ShadowThread.get(curTid),
					"Stack",			ShadowThread.stackDumpForErrorMessage(td));
//		}
		
		if (DEBUG) Assert.assertTrue(prevTid != curTid);
		
		event.getArrayState().specialize();
		
//		if(!arrayErrors.stillLooking(event.getInfo())) {
//			advance(event);
//		}
	}
}
