package tools.wdc.sourceinfo;

import generateSDG.ReachabilityEngine;
import generateSDG.ReadSDG;
import tools.wdc.WDCTool;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class SDG implements SDGI {
	private final Map<String, Integer> mapSourceLocToId;
    private final ReachabilityEngine reachSDGEngine;
    // In the case that the source location of the read or the branch is missing from the SDG,
	// if this is false, we'll assume that the branch depends on all prior reads (sound but not complete).
	// If this is true, we'll assume that the branch depends on nothing (complete but not sound).
    private static final boolean assumeDependsOnMiss = false;

    public SDG(String className, String projectPath) {
		reachSDGEngine = ReadSDG.ConstructReachability(ReadSDG.readSDG(className, projectPath));
        mapSourceLocToId = ReadSDG.NodeLocSrcToId(ReadSDG.NodeToId(className, projectPath));
    }

    @Override
    public boolean dependsOn(String readLocation, String branchLocation, int tid) {
    	int readId, branchId;

    	WDCTool.lookup_sdg.inc(tid);
        
        if (mapSourceLocToId.containsKey(readLocation) && mapSourceLocToId.containsKey(branchLocation)) {
        	readId = mapSourceLocToId.get(readLocation);
        	branchId = mapSourceLocToId.get(branchLocation);
        	return reachSDGEngine.canReach(branchId, readId);
        }
        else {
        	if (mapSourceLocToId.containsKey(branchLocation)) {
        		//miss branch location
        		WDCTool.branch_missing_sdg.inc(tid);
        	}
        	if (mapSourceLocToId.containsKey(readLocation)) {
        		//miss read location
        		WDCTool.read_missing_sdg.inc(tid);
        	}
        	
			return assumeDependsOnMiss;
        }
    }
}
