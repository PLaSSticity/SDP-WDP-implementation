package tools.wdc.event;

import acme.util.Assert;
import tools.br.BRGuardBase;
import tools.br.BRVolatileData;
import tools.wdc.WDCGuardState;
import tools.wdc.WDCVolatileData;

/** An instance of RdWr Node can represent multiple consecutive read-write events that aren't interrupted by incoming (outgoing are impossible) WDC edges */
public class RdWrNode extends EventNode {
	// The last writer for this read (if !isWrite)
	private RdWrNode lastWriter;
	boolean isWrite;

	public final ShadowID var;
	public RdWrNode(long eventNumber, long exampleNumber, boolean isWrite, WDCGuardState var, int threadID, AcqRelNode currentCriticalSection) {
		super(eventNumber, exampleNumber, threadID, currentCriticalSection);
		this.var = var.getID();
		this.isWrite = isWrite;
	}
	public RdWrNode(long eventNumber, long exampleNumber, boolean isWrite, WDCVolatileData var, int threadID, AcqRelNode currentCriticalSection) {
		super(eventNumber, exampleNumber, threadID, currentCriticalSection);
		this.var = var.getID();
		this.isWrite = isWrite;
	}
	
	boolean isWrite() {
		return isWrite;
	}

	boolean isRead() {
		return !isWrite;
	}

	String getFieldName() {
		return "?";
	}


	public RdWrNode tryToMergeWithPrior() {
		// We don't support event merging for WBR
		return this;
	}


	@Override
	public String getNodeLabel() {
		StringBuilder sb = new StringBuilder();
		sb.append(isWrite ? "wr(" : "rd(");
		sb.append(var.hashCode());
		sb.append(") ");
		return sb.toString();
	}

	final public RdWrNode lastWriter() {
		if (VERBOSE_GRAPH) Assert.assertTrue(lastWriter != null);
		return lastWriter;
	}

	final public void setLastWriter(RdWrNode lastWriter) {
		if (VERBOSE_GRAPH) {
			Assert.assertTrue(this.lastWriter == null);
			Assert.assertTrue(lastWriter.isWrite());
			Assert.assertTrue(isRead());
		}
		this.lastWriter = lastWriter;
	}

	final public boolean hasNoLastWriter() {
		return lastWriter == null;
	}

	final public boolean hasLastWriter() {
		return lastWriter != null;
	}

	public void setHasNoLastWriter() {
		if (VERBOSE_GRAPH) {
			Assert.assertTrue(isRead());
			Assert.assertTrue(lastWriter == null);
		}
	}

	final public boolean sameVar(RdWrNode other) {
		return var.equals(other.var);
	}
}
