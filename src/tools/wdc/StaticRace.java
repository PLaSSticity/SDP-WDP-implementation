package tools.wdc;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import acme.util.Util;
import rr.meta.MethodInfo;
import rr.meta.SourceLocation;
import tools.wdc.event.RdWrNode;

class StaticRace {
	HashSet<SourceLocation> locations; // Might contain just one location, which means the location races with itself
	//RdWrNodes needed for identifying conflicting event nodes for vindication
	final RdWrNode firstNode;
	final RdWrNode secondNode;
	final RaceType raceType;
	final MethodInfo firstNodeMI;
	final MethodInfo secondNodeMI;
	
	StaticRace(SourceLocation one, SourceLocation another) {
		locations = new HashSet<SourceLocation>();
		locations.add(one);
		locations.add(another);
		this.firstNode = null;
		this.secondNode = null;
		this.raceType = null;
		this.firstNodeMI = null;
		this.secondNodeMI = null;
	}
	
	StaticRace(SourceLocation one, SourceLocation another, RdWrNode firstNode, RdWrNode secondNode, RaceType raceType, MethodInfo firstNodeMI, MethodInfo secondNodeMI) {
		locations = new HashSet<SourceLocation>();
		locations.add(one);
		locations.add(another);
		this.firstNode = firstNode;
		this.secondNode = secondNode;
		this.raceType = raceType;
		this.firstNodeMI = firstNodeMI;
		this.secondNodeMI = secondNodeMI;
	}
	
	@Override
	public boolean equals(Object o) {
		return this.locations.equals(((StaticRace)o).locations);
	}
	
	@Override
	public int hashCode() {
		return locations.hashCode();
	}
	
	@Override
	public String toString() {
		Iterator<SourceLocation> iter = locations.iterator();
		SourceLocation first = iter.next();
		return first.getFriendlySourceLoc() + " -> " + (iter.hasNext() ? iter.next().getFriendlySourceLoc() : first.getFriendlySourceLoc()); // Handle possibility of location racing with itself
	}
	
	public String description() {
		Iterator<SourceLocation> iter = locations.iterator();
		SourceLocation first = iter.next();
		String firstClass = (firstNodeMI==null ? "null methodInfo" : firstNodeMI.getOwner().getName());
		String firstMethod = (firstNodeMI==null ? "null methodInfo" : firstNodeMI.getName());
		SourceLocation second = (iter.hasNext() ? iter.next() : first);
		String secondClass = (secondNodeMI==null ? "null methodInfo" : secondNodeMI.getOwner().getName());
		String secondMethod = (secondNodeMI==null ? "null methodInfo" : secondNodeMI.getName());
		return "(" + firstClass + ":" + firstMethod + ":" + first + " -> " + secondClass + ":" + secondMethod + ":" + second + ")";
	}
	
	static ConcurrentLinkedQueue<StaticRace> races = new ConcurrentLinkedQueue<StaticRace>();
	static HashMap<RaceType,HashMap<StaticRace,Integer>> staticRaceMap = new HashMap<RaceType,HashMap<StaticRace,Integer>>();

	static void addRace(StaticRace staticRace, RaceType type) {
		HashMap<StaticRace,Integer> counts = staticRaceMap.get(type);
		if (counts == null) {
			counts = new HashMap<StaticRace,Integer>();
			staticRaceMap.put(type, counts);
		}
		Integer count = counts.get(staticRace);
		if (count == null) {
			counts.put(staticRace, 1);
		} else {
			counts.put(staticRace, count.intValue() + 1);
		}
	}

	static boolean raceTypeEnabled(RaceType type) {
		switch (type) {
			case HBRace: return WDCTool.hasHB;
			case WCPRace: return WDCTool.hasWCP;
			case NWCRace: return WDCTool.hasNWC;
			case WDCRace: return WDCTool.hasDC;
			case uDPRace: return WDCTool.hasUDP;
			case WBRRace: return WDCTool.hasWBR;
			case LSHERace: return WDCTool.hasLSHE;
			case WBROrdered: return true;
			default: return false;
		}
	}

	static void reportRaces() {
		for (RaceType type : RaceType.values()) {
			if (type.isLSHERace()) { // ignore RaceType.WBROrdered
				//Static Count
				int race_count = getStaticRaceCount(RaceType.HBRace);
				if (type != RaceType.HBRace) {
					race_count += getStaticRaceCount(RaceType.WCPRace);
					if (type != RaceType.WCPRace) {
						race_count += getStaticRaceCount(RaceType.NWCRace);
						if (type != RaceType.NWCRace) {
							race_count += getStaticRaceCount(RaceType.WDCRace);
							if (type != RaceType.WDCRace) {
								race_count += getStaticRaceCount(RaceType.uDPRace);
								if (type != RaceType.uDPRace) {
									race_count += getStaticRaceCount(RaceType.WBRRace);
									if (type != RaceType.WBRRace) {
										race_count += getStaticRaceCount(RaceType.LSHERace);
									}
								}
							}
						}
					}
				}
				if (raceTypeEnabled(type))
					Util.println(race_count + " statically unique " + type.toString() + "(s)");
				//Dynamic Count
				race_count = getDynamicRaceCount(RaceType.HBRace);
				if (type != RaceType.HBRace) {
					race_count += getDynamicRaceCount(RaceType.WCPRace);
					if (type != RaceType.WCPRace) {
						race_count += getDynamicRaceCount(RaceType.NWCRace);
						if (type != RaceType.NWCRace) {
							race_count += getDynamicRaceCount(RaceType.WDCRace);
							if (type != RaceType.WDCRace) {
								race_count += getDynamicRaceCount(RaceType.uDPRace);
								if (type != RaceType.uDPRace) {
									race_count += getDynamicRaceCount(RaceType.WBRRace);
									if (type != RaceType.WBRRace) {
										race_count += getDynamicRaceCount(RaceType.LSHERace);
									}
								}
							}
						}
					}
				}
				if (raceTypeEnabled(type))
					Util.println(race_count + " dynamic " + type.toString() + "(s)");
			}
			//reportRaces(staticRaceMap.get(type));
		}

		staticRaceMap.forEach(
				(type, races) -> races.forEach(
						(race, count) -> Util.printf("%s: %s instances of %s\n", type, count, race)));
	}
	
	static void reportRaces(HashMap<StaticRace,Integer> staticRaces) {
		for (StaticRace staticRace : staticRaces.keySet()) {
			Util.println(staticRace + " (count = " + staticRaces.get(staticRace) + ")");
		}
	}

	/** Returns true if the given race type is the smallest set of races that this race is a member of.
	 *
	 * i.e. if a race is an WCP-race, SDP-race and a WDP-race but not an HB-race, this will return true only for WCP.
	 */
	public boolean isMinRace(RaceType type) {
		Map<StaticRace, Integer> races = staticRaceMap.get(type);
		if (races == null) return false;
		return races.containsKey(this);
	}

	public static int getStaticRaceCount(RaceType type) {
		if (staticRaceMap.get(type) == null) return 0;
		if (type == RaceType.HBRace) {
			return staticRaceMap.get(RaceType.HBRace).keySet().size();
		} else if (type == RaceType.WCPRace) {
			return (int)staticRaceMap.get(RaceType.WCPRace).keySet().stream().filter(
					r -> !(r.isMinRace(RaceType.HBRace))
			).count();
		} else if (type == RaceType.NWCRace) {
			return (int)staticRaceMap.get(RaceType.NWCRace).keySet().stream().filter(
					r -> !(r.isMinRace(RaceType.HBRace) || r.isMinRace(RaceType.WCPRace))
			).count();
		} else if (type == RaceType.WDCRace){
			return (int)staticRaceMap.get(RaceType.WDCRace).keySet().stream().filter(
					r -> !(r.isMinRace(RaceType.HBRace) || r.isMinRace(RaceType.WCPRace) || r.isMinRace(RaceType.NWCRace))
			).count();
		} else if (type == RaceType.uDPRace) {
			return (int)staticRaceMap.get(RaceType.uDPRace).keySet().stream().filter(
					r -> !(r.isMinRace(RaceType.HBRace) || r.isMinRace(RaceType.WCPRace) || r.isMinRace(RaceType.NWCRace) || r.isMinRace(RaceType.WDCRace))
			).count();
		} else if (type == RaceType.WBRRace) {
			return (int)staticRaceMap.get(RaceType.WBRRace).keySet().stream().filter(
					r -> !(r.isMinRace(RaceType.HBRace) || r.isMinRace(RaceType.WCPRace) || r.isMinRace(RaceType.NWCRace) || r.isMinRace(RaceType.WDCRace) || r.isMinRace(RaceType.uDPRace))
			).count();
		} else if (type == RaceType.LSHERace) {
			return (int)staticRaceMap.get(RaceType.LSHERace).keySet().stream().filter(
					r -> !(r.isMinRace(RaceType.HBRace) || r.isMinRace(RaceType.WCPRace) || r.isMinRace(RaceType.NWCRace) || r.isMinRace(RaceType.WDCRace) || r.isMinRace(RaceType.uDPRace) || r.isMinRace(RaceType.WBRRace))
			).count();
		}
		return -1;
	}
	
	static int getDynamicRaceCount(RaceType type) {
		int race_count = 0;
		if (staticRaceMap.get(type) != null) {
			for (StaticRace race : staticRaceMap.get(type).keySet()) {
				race_count += staticRaceMap.get(type).get(race);
			}
		}
		return race_count;
	}

	public final int raceDistance() {
		return secondNode.eventNumber - firstNode.eventNumber;
	}

	boolean isLSHERace() {
		return raceType.isLSHERace();
	}

	boolean isWBRRace() {
		return raceType.isWBRRace();
	}

	boolean isuDPRace() {
		return raceType.isuDPRace();
	}

	boolean isWDCRace() {
		return raceType.isWDCRace();
	}

	boolean isNWCRace() {
		return raceType.isNWCRace();
	}

	boolean isWCPRace() {
		return raceType.isWCPRace();
	}

	boolean isHBRace() {
		return raceType.isHBRace();
	}
}

enum RaceType {
	HBRace,
	WCPRace, // but HB ordered
	NWCRace, // but WCP ordered
	WDCRace, // but NWC ordered
	uDPRace, // but WDC ordered
	WBRRace, // but uDP ordered
	LSHERace, // but WBR ordered
	WBROrdered;

	boolean isLSHERace() {
		return this.isWBRRace() || this.equals(LSHERace);
	}
	
	boolean isWBRRace() {
		return this.isuDPRace() || this.equals(WBRRace);
	}

	boolean isuDPRace() {
		return this.isWDCRace() || this.equals(uDPRace);
	}

	boolean isWDCRace() {
		return this.isNWCRace() || this.equals(WDCRace);
	}

	boolean isNWCRace() {
		return this.isWCPRace() || this.equals(NWCRace);
	}

	boolean isWCPRace() {
		return this.isHBRace() || this.equals(WCPRace);
	}
	
	boolean isHBRace() {
		return this.equals(HBRace);
	}
	@Override
	public String toString() {
		switch (this) {
		case HBRace: return "HB-race";
		case WCPRace: return "WCP-race";
		case NWCRace: return "NWC-race";
		case WDCRace: return "WDC-race";
		case uDPRace: return "uDP-race";
		case WBRRace: return "WBR-race";
		case LSHERace: return "LSHE-race";
		case WBROrdered: return "WBR-ordered";
		default: return null;
		}
	}
	String relation() {
		switch (this) {
		case HBRace: return "HB";
		case WCPRace: return "WCP";
		case NWCRace: return "NWC";
		case WDCRace: return "WDC";
		case uDPRace: return "uDP";
		case WBRRace: return "WBR";
		case LSHERace: return "LSHE";
		default: return null;
		}
	}
}
