package tools.wdc.event;


public class SourceLocWithNode {
    final public String loc;
    final public EventNode node;

    public SourceLocWithNode(String loc, EventNode node) {
        this.loc = loc;
        this.node = node;
    }
}