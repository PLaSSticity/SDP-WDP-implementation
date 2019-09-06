package tools.br.br;

import rr.state.ShadowThread;
import rr.state.ShadowVar;
import tools.br.BRGuardBase;
import tools.br.CV;

import java.util.HashMap;

public class BRGuardState extends BRGuardBase implements ShadowVar {
	// B^w_{t,x}, the delayed write information
	// TODO: Make this a thread-local variable, each thread only has to access their own
	public final HashMap<ShadowThread,CV> brDelayedWr;

	BRGuardState() {
		brDelayedWr = new HashMap<>();
	}
}
