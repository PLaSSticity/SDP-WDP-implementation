package tools.wdc;

import tools.wdc.event.EventNode;

/** Vector clock with an event node */
class CVE extends CV {
	
	EventNode eventNode;
	
	public CVE(CV cv, EventNode eventNode) {
		super(cv);
		this.eventNode = eventNode;
	}

	public CVE(CVE cve) {
		super(cve);
		this.eventNode = cve.eventNode;
	}
	
	public void setEventNode(EventNode eventNode) {
		this.eventNode = eventNode;
	}

	@Override
	public String toString() {
		return super.toString() + " (event " + eventNode + ")";
	}

}
