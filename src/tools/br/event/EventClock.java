package tools.br.event;


import tools.br.BRToolBase;
import tools.br.DynamicSourceLocation;

import java.util.*;
import java.util.stream.Collectors;


/**
 * Similar to a vector clock, maps threads to the latest known events by them.
 *
 * Not thread safe, all concurrent accesses to instances must be well-synchronized.
 */
public class EventClock implements Iterable<EventNode> {
    /** Maps threads to their latest events. */
    private Vector<EventNode> events;

    // Copy constructor, makes a shallow copy
    public EventClock(EventClock other) {
        events = new Vector<>(other.events);
    }

    public EventNode get(int i) {
        return events.get(i);
    }

    public void set(int i, EventNode e) {
        if (i >= events.size()) events.setSize(i + 1);
        events.set(i, e);
    }

    public int size() {
        return events.size();
    }


    public EventClock(int initSize) {
        events = new Vector<>(initSize);
    }

    public EventClock() {
        this(BRToolBase.INIT_CV_SIZE);
    }


    public void max(EventClock other) {
        max(other.events);
    }

    public void max(DynamicSourceLocation[] other) {
        // BRGuardBase creates an array of size max tid for last read locations.
        int lastNonNull = 0;
        for (int i = 0; i < other.length; i++) {
            if(other[i] != null) lastNonNull = i;
        }
        max(Arrays.stream(other).limit(lastNonNull).map(loc -> loc != null ? loc.eventNode : null).collect(Collectors.toList()));
    }

    private void max(List<EventNode> other) {
        if (other.size() > size()) {
            this.events.setSize(other.size());
        }
        // Looping up to other.size because we are as big as other or bigger
        for (int i = 0; i < other.size(); i++) {
            EventNode our = get(i);
            EventNode their = other.get(i);
            if (their == null) continue;
            if (our == null || our.eventNumber < their.eventNumber) {
                set(i, their);
            }
        }
    }

    /** Iterates over non-null EventNodes in EventClock. */
    @Override
    public Iterator<EventNode> iterator() {
        return events.stream().filter(Objects::nonNull).iterator();
    }
}
