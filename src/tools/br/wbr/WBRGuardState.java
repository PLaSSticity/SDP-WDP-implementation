package tools.br.wbr;

import rr.state.ShadowThread;
import tools.br.BRGuardBase;
import tools.br.BRToolBase;
import tools.br.CV;
import tools.br.event.EventClock;

import java.util.HashMap;

public class WBRGuardState extends BRGuardBase {
    public final HashMap<ShadowThread,CV> brDelayedWr;
    public final HashMap<ShadowThread,EventClock> brDelayedWrEvents;

    public WBRGuardState() {
        brDelayedWr = new HashMap<>();
        brDelayedWrEvents = BRToolBase.BUILD_EVENT_GRAPH ? new HashMap<>() : null;
    }
}
