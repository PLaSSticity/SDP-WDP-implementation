package tools.br.event;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import acme.util.Util;
import rr.meta.MethodInfo;
import rr.meta.SourceLocation;

public class StaticRace {
	HashSet<SourceLocation> locations; // Might contain just one location, which means the location races with itself
	//RdWrNodes needed for identifying conflicting event nodes for vindication
	public final RdWrNode firstNode;
	public final RdWrNode secondNode;
	public final RaceType raceType;
	final MethodInfo firstNodeMI;
	final MethodInfo secondNodeMI;
	
	public StaticRace(SourceLocation one, SourceLocation another) {
		locations = new HashSet<SourceLocation>();
		locations.add(one);
		locations.add(another);
		this.firstNode = null;
		this.secondNode = null;
		this.raceType = null;
		this.firstNodeMI = null;
		this.secondNodeMI = null;
	}
	
	public StaticRace(SourceLocation one, SourceLocation another, RdWrNode firstNode, RdWrNode secondNode, RaceType raceType, MethodInfo firstNodeMI, MethodInfo secondNodeMI) {
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
		return first + " -> " + (iter.hasNext() ? iter.next() : first); // Handle possibility of location racing with itself
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
	
	public static final ConcurrentLinkedQueue<StaticRace> races = new ConcurrentLinkedQueue<>();
	public static final HashMap<RaceType,HashMap<StaticRace,Vector<StaticRace>>> staticRaceMap = new HashMap<>();
	
	public static void addRace(StaticRace staticRace, RaceType type) {
		HashMap<StaticRace, Vector<StaticRace>> dynamicInstanceMap;
		Vector<StaticRace> dynamicInstances;
		synchronized (staticRaceMap) {
			dynamicInstanceMap = staticRaceMap.computeIfAbsent(type, k -> new HashMap<>());
			dynamicInstances = dynamicInstanceMap.computeIfAbsent(staticRace, k -> new Vector<>());
			dynamicInstances.add(staticRace);
		}
	}
	
	public static void reportRaces() {
		for (RaceType type : RaceType.values()) {
			//Static Count
			int race_count = getStaticRaceCount(RaceType.WBRRace);
			Util.println(race_count + " statically unique " + type.toString() + "(s)");

			//Dynamic Count
			race_count = getDynamicRaceCount(RaceType.WBRRace);
			Util.println(race_count + " dynamic " + type.toString() + "(s)");
		}
	}
	
	static void reportRaces(HashMap<StaticRace,Integer> staticRaces) {
		for (StaticRace staticRace : staticRaces.keySet()) {
			Util.println(staticRace + " (count = " + staticRaces.get(staticRace) + ")");
		}
	}
	
	static int getStaticRaceCount(RaceType type) {
		int race_count = 0;
		if (type == RaceType.WBRRace) {
			race_count = staticRaceMap.get(type) == null ? 0 : staticRaceMap.get(type).size();
		}
		return race_count;
	}
	
	static int getDynamicRaceCount(RaceType type) {
		int race_count = 0;
		if (staticRaceMap.get(type) != null) {
			for (StaticRace race : staticRaceMap.get(type).keySet()) {
				race_count += staticRaceMap.get(type).get(race).size();
			}
		}
		return race_count;
	}
}
