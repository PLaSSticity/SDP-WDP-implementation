package tools.br.sourceinfo;

import acme.util.Assert;
import acme.util.Util;
import acme.util.count.ThreadLocalCounter;
import generateSDG.ReachabilityEngine;
import generateSDG.ReadSDG;
import rr.tool.RR;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class SDG implements SDGI {
    private final String loadedClassName;
    private final Map<String, Integer> mapSourceLocToId;
    private final ReachabilityEngine reachSDGEngine;
    private static Set<String> missing = new HashSet<>();

    public SDG(String className, String projectPath) {
        loadedClassName = className;
        reachSDGEngine = ReadSDG.ConstructReachability(ReadSDG.readSDG(className, projectPath));
        mapSourceLocToId = ReadSDG.NodeLocSrcToId(ReadSDG.NodeToId(className, projectPath));
    }

    @Override
    public boolean dependsOn(String readLocation, String branchLocation,ThreadLocalCounter missbranch,ThreadLocalCounter missread, int Tid) {
    	int readId, branchId;
        
        if (mapSourceLocToId.containsKey(readLocation) && mapSourceLocToId.containsKey(branchLocation)) {
        	readId = mapSourceLocToId.get(readLocation);
        	branchId = mapSourceLocToId.get(branchLocation);
        	return reachSDGEngine.canReach(branchId, readId);
        }
        else {
        	if(mapSourceLocToId.containsKey(branchLocation)) {
        		//miss branch location
        		String rl = readLocation;//extractSigniture(readLocation);
        		if(!missing.contains(rl)) {
        			//write2(rl, filePath);
            		missing.add(rl);
        		}
        		missbranch.inc(Tid);
        		//Util.printf("Unable to match source location %s in loaded SDG." , branchLocation);
        		
        	}
        	else if(mapSourceLocToId.containsKey(readLocation)) {
        		//miss read location
        		String bl = branchLocation;//extractSigniture(branchLocation);
        		if (!missing.contains(bl)) {
        			//write2(bl, filePath);
            		missing.add(bl);
        		}
        		missread.inc(Tid);
        		//Util.printf("Unable to match source location %s in loaded SDG." ,readLocation);
        	}
        	else {
        		//miss both
        		String rl = readLocation;//extractSigniture(readLocation);
        		String bl = branchLocation;//extractSigniture(branchLocation);
        		
        		if(!missing.contains(rl)) {
        			//write2(rl, filePath);
            		missing.add(rl);
        		}
        		if(!missing.contains(bl)) {
        			//write2(bl, filePath);
            		missing.add(bl);
        		}
        		missbranch.inc(Tid);
        		missread.inc(Tid);
        		//Util.printf("Unable to match source location %s and %s in loaded SDG." ,readLocation, branchLocation);
        	}
        	
        	return true;
        }
    }
}
