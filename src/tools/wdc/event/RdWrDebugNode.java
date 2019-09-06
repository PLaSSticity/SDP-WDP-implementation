package tools.wdc.event;


import tools.wdc.WDCGuardState;

/* RdWrNode with debug info attached */
public class RdWrDebugNode extends RdWrNode {
	private String fieldName;

	public RdWrDebugNode(long eventNumber, long exampleNumber, boolean isWrite, String fieldName, WDCGuardState var, int threadID, AcqRelNode currentCriticalSection, String sourceLoc) {
		super(eventNumber, isWrite, var, threadID, currentCriticalSection, sourceLoc);
		this.fieldName = fieldName;
	}

	@Override
	public String getNodeLabel() {
		StringBuilder sb = new StringBuilder();
		sb.append(isWrite ? "wr(" : "rd(");
		sb.append(fieldName);
		sb.append(") ");
		return sb.toString();
	}
}
