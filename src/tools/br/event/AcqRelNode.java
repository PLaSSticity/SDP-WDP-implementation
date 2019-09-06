package tools.br.event;

import acme.util.Assert;
import acme.util.Util;
import rr.state.ShadowLock;
import rr.state.ShadowThread;

public class AcqRelNode extends EventNode {
	public final ShadowLock shadowLock;
	//final int threadID;
	final boolean isAcquire;
	public AcqRelNode otherCriticalSectionNode;

	public AcqRelNode(long eventNumber, long exampleNumber, ShadowLock shadowLock, int threadID, boolean isAcquire, AcqRelNode inCS) {
		super(eventNumber, exampleNumber, threadID, inCS);
		if (VERBOSE_GRAPH) Assert.assertFalse(shadowLock == null);
		this.shadowLock = shadowLock;
		//this.thr_id = threadID;
		this.isAcquire = isAcquire;
		if (!isAcquire) {
			this.otherCriticalSectionNode = inCS;
			inCS.otherCriticalSectionNode = this;
		}
	}

	public boolean isAcquire() {
		return isAcquire;
	}

	public AcqRelNode getOtherCriticalSectionNode() {
		return otherCriticalSectionNode;
	}

	public ShadowThread getShadowThread() {
		return ShadowThread.get(threadID);
	}

	@Override
	public String getNodeLabel(){
		return (isAcquire ? "acq" : "rel") + "(" + Util.objectToIdentityString(shadowLock.getLock()) + ") by T" + threadID;
	}
}
