package tools.br.wbr;

import acme.util.identityhash.WeakIdentityHashMap;
import rr.state.ShadowLock;
import rr.state.ShadowThread;
import rr.state.ShadowVar;
import tools.br.BRLockBase;
import tools.br.BRToolBase;
import tools.br.CV;
import tools.br.event.EventClock;
import tools.br.event.EventNode;

import java.util.HashMap;

class WBRLockData extends BRLockBase {


    // Rule (a) queues
    final HashMap<ShadowThread,PerThreadQueue<CV>> wbrAcqQueueMap;
    final HashMap<ShadowThread,PerThreadQueue<CV>> wbrRelQueueMap;
    final PerThreadQueue<CV> wbrAcqQueueGlobal;
    final PerThreadQueue<CV> wbrRelQueueGlobal;

    // WBR times of all release events on this lock that wrote to the variable in the key
    WeakIdentityHashMap<ShadowVar,CV> wbrWriteMap;

    // Event nodes known by the threads at the time of release that wrote to the variable in the key
    WeakIdentityHashMap<ShadowVar,EventClock> wbrWriteEventMap;

    // WBR time of the last release event on this lock
    CV wbr;

    // Event nodes known by the thread that last released this lock, at the time of the release
    EventClock eventClock;

    
	// For VindicateRace: to correctly order critical sections to the observed executions, acquire events must update eventNumber to +1 of the latest release event of the same lock
	public EventNode latestRelNode;

    WBRLockData(ShadowLock peer) {
        super(peer);

        wbrAcqQueueMap = new HashMap<>();
        wbrRelQueueMap = new HashMap<>();
        wbrAcqQueueGlobal = new PerThreadQueue<>();
        wbrRelQueueGlobal = new PerThreadQueue<>();

        wbrWriteMap = new WeakIdentityHashMap<>();
        wbr = new CV(BRToolBase.INIT_CV_SIZE);

        latestRelNode = null;
        if (BRToolBase.BUILD_EVENT_GRAPH) {
            wbrWriteEventMap = new WeakIdentityHashMap<>();
            eventClock = new EventClock();
        }
    }
}
