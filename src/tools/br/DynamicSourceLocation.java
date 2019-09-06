package tools.br;

import rr.event.AccessEvent;
import rr.meta.MethodInfo;
import rr.meta.SourceLocation;
import tools.br.event.EventNode;

public class DynamicSourceLocation {
	
	final SourceLocation loc;
	final MethodInfo eventMI;
	//eventNode needed for identifying RdWrNode of prior access for vindication
	public final EventNode eventNode;

	public DynamicSourceLocation(AccessEvent ae, MethodInfo eventMI) {
		this(ae.getAccessInfo().getLoc(), eventMI);
	}
	
	public DynamicSourceLocation(AccessEvent ae, MethodInfo eventMI, EventNode eventNode) {
		this(ae.getAccessInfo().getLoc(), eventMI, eventNode);
	}
	
	public DynamicSourceLocation(SourceLocation loc, MethodInfo eventMI) {
		this.loc = loc;
		this.eventMI = eventMI;
		this.eventNode = null;
	}
	
	public DynamicSourceLocation(SourceLocation loc, MethodInfo eventMI, EventNode eventNode) {
		this.loc = loc;
		this.eventMI = eventMI;
		this.eventNode = eventNode;
	}
	
	@Override
	public String toString() {
		return loc.toString();
	}

	public SourceLocation getLoc() {
		return loc;
	}

}
