package rr.event;

import rr.meta.JumpInfo;
import rr.state.ShadowThread;

public class BranchEvent extends Event {
    
	protected JumpInfo info;
	
    public BranchEvent(ShadowThread td) {
        super(td);
    }
    
    @Override
	public String toString() {
		return String.format("Branch(%d)", getThread().getTid());
	}
    
    public void setInfo(JumpInfo jumpInfo) {
		this.info = jumpInfo;
	}
    
	public JumpInfo getInfo() {
		return info;
	}
    
    
}
