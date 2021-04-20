package tools.wdc;

import acme.util.Assert;
import rr.tool.RR;
import tools.wdc.event.EventNode;

/** Vector clock with an event node */
class CVE extends CV {
	
	EventNode eventNode;

	public CVE(int size, EventNode eventNode) {
		super(size);
		this.eventNode = eventNode;
	}

	public CVE(CV cv, EventNode eventNode) {
		super(cv);
		this.eventNode = eventNode;
	}

	public CVE(CVE cve) {
		super(cve);
		this.eventNode = cve.eventNode;
	}

	private static final boolean DISABLE_EVENT_GRAPH = !RR.wdcBuildEventGraph.get();

	public void setEventNode(EventNode eventNode) {
		// TODO: Also disallow this method being called if DISABLE_EVENT_GRAPH ?
		if (!DISABLE_EVENT_GRAPH) Assert.assertTrue(eventNode != null);
		this.eventNode = eventNode;
	}

	@Override
	public String toString() {
		if (!DISABLE_EVENT_GRAPH) return super.toString() + " (event " + eventNode + ")";
		else return super.toString();
	}

}
