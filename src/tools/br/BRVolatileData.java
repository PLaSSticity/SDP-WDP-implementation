package tools.br;

import rr.state.ShadowVar;
import rr.state.ShadowVolatile;
import tools.br.event.EventClock;
import tools.br.event.EventNode;
import tools.br.event.ShadowID;
import tools.br.event.VolatileRdWrNode;

public class BRVolatileData implements ShadowVar {
	public final ShadowVolatile peer;
	private final ShadowID id;
	public CV br;
	public CV hb;

	// TODO: There may be more than one conflicting write we have to be ordered to
	public VolatileRdWrNode lastWriter;

	public BRVolatileData(ShadowVolatile ld) {
		id = new ShadowID();
		this.peer = ld;
		this.br = new CV(BRToolBase.INIT_CV_SIZE);
	}

	@Override
	public String toString() {
		return String.format("[BR=%s] ", br);
	}

	public ShadowID getID() {
		return id;
	}
}
