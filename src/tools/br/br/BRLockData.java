package tools.br.br;


import java.util.*;

import acme.util.identityhash.WeakIdentityHashMap;
import rr.state.ShadowLock;
import rr.state.ShadowThread;
import rr.state.ShadowVar;
import rr.tool.RR;
import tools.br.BRLockBase;
import tools.br.BRToolBase;
import tools.br.CV;
import tools.br.event.AcqRelNode;
import tools.br.event.EventNode;
import tools.br.event.RdWrNode;


public class BRLockData extends BRLockBase {
	public final CV hb;
	public final CV br;

	// HB times of all release events on this lock that read the variable in the key
	public WeakIdentityHashMap<ShadowVar,CV> brReadMap;
	// HB times of all release events on this lock that wrote to the variable in the key
	public WeakIdentityHashMap<ShadowVar,CV> brWriteMap;

	// Event node of the last write to the variable in the key that occurred while this lock is held
	public WeakIdentityHashMap<ShadowVar,AcqRelNode> brWriteNodeMap;


	// B^r_{l,x}, the delayed read information
	public final WeakIdentityHashMap<ShadowVar, CV> brDelayedRd;

	// Delayed wr-wr edges, only when enabled
	public final WeakIdentityHashMap<ShadowVar, CV> brDelayedWrWr;

	public final HashMap<ShadowThread,ArrayDeque<CV>> brAcqQueueMap;
	public final HashMap<ShadowThread,ArrayDeque<CV>> brRelQueueMap;
	public final ArrayDeque<CV> brAcqQueueGlobal;
	public final ArrayDeque<CV> brRelQueueGlobal;

	// For VindicateRace: to correctly order critical sections to the observed executions, acquire events must update eventNumber to +1 of the latest release event of the same lock
	public EventNode latestRelNode;

	public BRLockData(ShadowLock peer) {
		super(peer);
		this.hb = new CV(BRToolBase.INIT_CV_SIZE);
		this.br = new CV(BRToolBase.INIT_CV_SIZE);
		
		this.brReadMap = new WeakIdentityHashMap<ShadowVar,CV>();
		this.brWriteMap = new WeakIdentityHashMap<ShadowVar,CV>();
		this.brAcqQueueMap = new HashMap<ShadowThread,ArrayDeque<CV>>();
		this.brRelQueueMap = new HashMap<ShadowThread,ArrayDeque<CV>>();
		this.brAcqQueueGlobal = new ArrayDeque<CV>();
		this.brRelQueueGlobal = new ArrayDeque<CV>();
		this.brDelayedRd = new WeakIdentityHashMap<>();
		this.brDelayedWrWr = RR.brWrWrEdges.get() ? new WeakIdentityHashMap<>() : null;
		brWriteNodeMap = new WeakIdentityHashMap<>();
		
		latestRelNode = null;
	}



	@Override
	public String toString() {
		return String.format("[HB=%s] [BR=%s] ", hb, br);
	}
}
