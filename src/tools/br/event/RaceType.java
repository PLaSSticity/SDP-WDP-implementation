package tools.br.event;

public enum RaceType {
	WBRRace;
	
	boolean isWBRRace() {
		return this.equals(WBRRace);
	}
	@Override
	public String toString() {
		switch (this) {
		case WBRRace: return "WBR-race";
		default: return null;
		}
	}
	String relation() {
		switch (this) {
		case WBRRace: return "WBR";
		default: return null;
		}
	}
}
