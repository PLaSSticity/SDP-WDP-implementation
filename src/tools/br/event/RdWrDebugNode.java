package tools.br.event;

import rr.state.ShadowVar;
import tools.br.BRGuardBase;

import java.util.Vector;

/* RdWrNode with debug info attached */
public class RdWrDebugNode extends RdWrNode {
	private String fieldName;

	public RdWrDebugNode(long eventNumber, long exampleNumber, boolean isWrite, String fieldName, BRGuardBase var, int threadID, AcqRelNode currentCriticalSection) {
		super(eventNumber, exampleNumber, isWrite, var, threadID, currentCriticalSection);
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
