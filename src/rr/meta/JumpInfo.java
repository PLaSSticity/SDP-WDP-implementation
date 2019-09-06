package rr.meta;

public class JumpInfo extends OperationInfo {

	public JumpInfo(int id, SourceLocation loc, MethodInfo enclosing) {
		super(id, loc, "jump", enclosing);
		// TODO Auto-generated constructor stub
	}

	@Override
	protected String computeKey() {
		return MetaDataInfoKeys.getJumpKey(this.getLoc());
	}
	
	@Override
	public void accept(MetaDataInfoVisitor v) {
		v.visit(this);
	}

}
