package rr.tool;


/**
 * Holds static final config options.
 *
 * Used to delay the class initialization, otherwise RREventGenerator reads the config option before it is parsed.
 */
class Conf {
    static final boolean ASSUME_BRANCHES = RR.brAssumeBranches.get();
}
