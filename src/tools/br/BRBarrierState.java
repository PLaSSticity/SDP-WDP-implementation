package tools.br;

import rr.state.ShadowLock;
import tools.br.CV;

public class BRBarrierState {
	private CV entering = new CV(8);
	
	
	public synchronized void reset(CV old) {
		if (getEntering() == old) {
			setEntering(new CV(8));
		}
	}
	
	public BRBarrierState(ShadowLock k) {
	}

	public void setEntering(CV entering) {
		this.entering = entering;
	}

	public CV getEntering() {
		return entering;
	}

}
