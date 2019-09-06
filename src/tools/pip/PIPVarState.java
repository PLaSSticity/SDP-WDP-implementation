package tools.pip;

import java.util.HashMap;

import acme.util.Assert;
import rr.state.ShadowLock;
import rr.state.ShadowThread;
import rr.state.ShadowVar;
import tools.util.Epoch;
import tools.util.VectorClock;

public class PIPVarState extends VectorClock implements ShadowVar {

	public volatile int/*epoch*/ W;
	
	public volatile int/*epoch*/ R;
	
	protected PIPVarState() {}
	
	public PIPVarState(boolean isWrite, int/*epoch*/ epoch, boolean isOwned) {
		if (isWrite) {
			if (isOwned) {
				R = epoch;
				W = epoch;
			} else {
				R = Epoch.ZERO;
				W = epoch;
			}
		} else {
			W = Epoch.ZERO;
			R = epoch;
		}
	}
	
	@Override
	public synchronized void makeCV(int len) {
		super.makeCV(len);
	}
	
	@Override
	public synchronized String toString() {
		return String.format("[W=%s R=%s V=%s]", Epoch.toString(W), Epoch.toString(R), super.toString());
	}
}

class HeldLS {
	ShadowLock lock;
	VectorClock vc;
	HeldLS next = null;
	
	public HeldLS (ShadowLock lock, VectorClock vc) {
		this.lock = lock;
		this.vc = vc;
	}

}

//class HeldEL {
//	ShadowLock lock;
//	VectorClock vc;
//	int tid;
//	
//	HeldEL next = null;
//	HeldEL prev = null;
//	
//	public HeldEL (ShadowLock lock, VectorClock vc, int tid) {
//		this.lock = lock;
//		this.vc = vc;
//		this.tid = tid;
//	}
//}

class REVarState extends PIPVarState {	
	HeldLS Wm = null;
	HeldLS Rm = null;
	HeldLS[] SharedRm = null;
	
//	HeldEL Ew = null;
//	HeldEL Er = null;
	
//	HashMap<ShadowLock, VectorClock[]/*tid*/> Ew = new HashMap<ShadowLock, VectorClock[]>();
//	HashMap<ShadowLock, VectorClock[]/*tid*/> Er = new HashMap<ShadowLock, VectorClock[]>();
	
	HashMap<Integer/*tid*/, HashMap<ShadowLock, VectorClock>> Ew = new HashMap<Integer/*tid*/, HashMap<ShadowLock, VectorClock>>();
	HashMap<Integer/*tid*/, HashMap<ShadowLock, VectorClock>> Er = new HashMap<Integer/*tid*/, HashMap<ShadowLock, VectorClock>>();
	
	public REVarState(boolean isWrite, int epoch, boolean isOwned) {
		super(isWrite, epoch, isOwned);
	}
	
//	public void makeEL(int len, ShadowLock lock, boolean isWrite) {
//		VectorClock[] ELTids = new VectorClock[len];
//		if (isWrite) {
//			this.Ew.put(lock, ELTids);
//		} else {
//			this.Er.put(lock, ELTids);
//		}
//	}
//	
//	public void setEL(int tid, ShadowLock lock, VectorClock Cm, boolean isWrite) {
//		ensureCapacityEL(tid + 1, lock, isWrite);
//		if (isWrite) {
//			this.Ew.get(lock)[tid] = Cm;
//		} else {
//			this.Er.get(lock)[tid] = Cm;
//		}
//	}
//	
//	private void ensureCapacityEL(int len, ShadowLock lock, boolean isWrite) {
//		int curLength;
//		if (isWrite) {
//			curLength = this.Ew.get(lock).length;
//		} else {
//			curLength = this.Er.get(lock).length;
//		}
//		if (curLength < len) {
//			VectorClock[] b = new VectorClock[len];
//			for (int i = 0; i < curLength; i++) {
//				if (isWrite) {
//					b[i] = this.Ew.get(lock)[i];
//				} else {
//					b[i] = this.Er.get(lock)[i];
//				}
//			}
//			if (isWrite) {
//				this.Ew.put(lock, b);
//			} else {
//				this.Er.put(lock, b);
//			}
//		}
//	}
	
	//makeSharedHeldLS does not need to be synchronized since the VarState is locked on
	//SharedRm does not need to be volatile since the lock elements being added are already held
	//Input should be similar to: int initSize = Math.max(Math.max(rTid,tid), INIT_VECTOR_CLOCK_SIZE);
	public void makeSharedHeldLS(int len) {
		this.SharedRm = new HeldLS[len];
	}
	
	//TODO: SharedRm should use set when attempting to add elements since the capacity must be ensured
	public void setSharedHeldLS(int tid, HeldLS heldLock) {
//		Assert.assertTrue(tid == this.holdingThread); //TODO: something like that
		ensureCapacitySharedHeldLS(tid + 1);
		this.SharedRm[tid] = heldLock;
	}
	
	private void ensureCapacitySharedHeldLS(int len) {
		int curLength = this.SharedRm.length;
		if (curLength < len) {
			HeldLS[] b = new HeldLS[len];
			for (int i = 0; i < curLength; i++) {
				b[i] = this.SharedRm[i];
			}
			this.SharedRm = b;
		}
	}
	
	public HeldLS getSharedHeldLS(int tid) {
		HeldLS[] mySharedRm = this.SharedRm;
		if (tid < mySharedRm.length) {
			return this.SharedRm[tid];
		} else {
			return null;
		}
	}
	
	public void clearSharedHeldLS() {
		this.SharedRm = null;
	}
}

class WDPVarState extends PIPVarState {
	final VectorClock writeVC = new VectorClock(0);
	public int lastWriterTid = -1; // only maintained if write shared, otherwise -1
	HeldLS Wm = null;
	HeldLS Rm = null;
	private HeldLS[] SharedRm = null;
	private HeldLS[] SharedWm = null;

	public WDPVarState(boolean isWrite, int epoch, HeldLS heldLS) {
		if (isWrite) {
			R = Epoch.ZERO;
			W = epoch;
			Wm = heldLS;
		} else {
			W = Epoch.ZERO;
			R = epoch;
			Rm = heldLS;
		}
	}

	//makeShared* does not need to be synchronized since the VarState is locked on
	//SharedRm does not need to be volatile since the lock elements being added are already held
	//Input should be similar to: int initSize = Util.max(rTid, tid, INIT_VECTOR_CLOCK_SIZE);

	public final void makeReadShared(int len) {
		this.SharedRm = new HeldLS[len];
	}

	public final void makeWriteShared(int len) {
		this.SharedWm = new HeldLS[len];
	}

	public final void putReadShared(int tid, HeldLS heldLock) {
		if (PIPTool.DEBUG) Assert.assertTrue(isSharedRead());
		if (tid >= SharedRm.length) SharedRm = upsizeArray(SharedRm, tid + 1);
		this.SharedRm[tid] = heldLock;
	}

	public final void putWriteShared(int tid, HeldLS heldLock) {
		if (PIPTool.DEBUG) Assert.assertTrue(isSharedWrite());
		if (tid >= SharedWm.length) SharedWm = upsizeArray(SharedWm, tid + 1);
		this.SharedWm[tid] = heldLock;
	}

	private HeldLS[] upsizeArray(HeldLS[] arr, int len) {
		HeldLS[] newArr = new HeldLS[len];
		System.arraycopy(arr, 0, newArr, 0, arr.length);
		return newArr;
	}

	public final HeldLS getSharedRead(int tid) {
		return getOrNull(SharedRm, tid);
	}

	public final HeldLS getSharedWrite(int tid) {
		return getOrNull(SharedWm, tid);
	}

	public final HeldLS[] getSharedReads() {
		return SharedRm;
	}

	public final HeldLS[] getSharedWrites() {
		return SharedWm;
	}

	private HeldLS getOrNull(HeldLS[] arr, int ind) {
		return (arr.length > ind) ? arr[ind] : null;
	}

	public final void clearSharedRead() {
		if (PIPTool.DEBUG) Assert.assertTrue(this.R != Epoch.READ_SHARED);
		SharedRm = null;
	}

	public final void clearSharedWrite() {
		SharedWm = null;
	}

	public final boolean isSharedRead() {
		return SharedRm != null;
	}

	public final boolean isSharedWrite() {
		return SharedWm != null;
	}
}