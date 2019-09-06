package tools.br.event;

import acme.util.Assert;
import tools.br.BRGuardBase;
import tools.br.BRVolatileData;

/** An instance of RdWr Node can represent multiple consecutive read-write events that aren't interrupted by incoming (outgoing are impossible) WDC edges */
public class RdWrNode extends EventNode {
	// The last writer for this read (if !isWrite)
	private RdWrNode lastWriter;
	private boolean hasNoLastWriter = false;
	boolean isWrite;

	public final ShadowID var;
	public RdWrNode(long eventNumber, long exampleNumber, boolean isWrite, BRGuardBase var, int threadID, AcqRelNode currentCriticalSection) {
		super(eventNumber, exampleNumber, threadID, currentCriticalSection);
		this.var = var.getID();
		this.isWrite = isWrite;
	}
	public RdWrNode(long eventNumber, long exampleNumber, boolean isWrite, BRVolatileData var, int threadID, AcqRelNode currentCriticalSection) {
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
		throw new UnsupportedOperationException();
	}


	@Override
	public String getNodeLabel() {
		StringBuilder sb = new StringBuilder();
		sb.append(isWrite ? "wr(" : "rd(");
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
			Assert.assertFalse(this.hasNoLastWriter);
			Assert.assertTrue(lastWriter.isWrite());
			Assert.assertTrue(isRead());
		}
		this.lastWriter = lastWriter;
	}

	final public boolean hasNoLastWriter() {
		return hasNoLastWriter;
	}

	final public boolean hasLastWriter() {
		return !hasNoLastWriter;
	}

	public void setHasNoLastWriter() {
		if (VERBOSE_GRAPH) {
			Assert.assertTrue(isRead());
			Assert.assertTrue(lastWriter == null);
		}
		hasNoLastWriter = true;
	}

	final public boolean sameVar(RdWrNode other) {
		return var.equals(other.var);
	}
}
