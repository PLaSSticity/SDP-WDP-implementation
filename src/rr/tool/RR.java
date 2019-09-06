/******************************************************************************

Copyright (c) 2010, Cormac Flanagan (University of California, Santa Cruz)
                    and Stephen Freund (Williams College) 

All rights reserved.  

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

 * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.

 * Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      disclaimer in the documentation and/or other materials provided
      with the distribution.

 * Neither the names of the University of California, Santa Cruz
      and Williams College nor the names of its contributors may be
      used to endorse or promote products derived from this software
      without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

 ******************************************************************************/

package rr.tool;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.Thread.UncaughtExceptionHandler;
import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.net.URL;
import java.util.Date;
import java.util.List;

import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;

import rr.RRMain;
import rr.error.ErrorMessage;
import rr.error.ErrorMessages;
import rr.instrument.Instrumentor;
import rr.loader.Loader;
import rr.meta.MetaDataInfoMaps;
import rr.simple.LastTool;
import rr.state.ShadowThread;
import rr.tool.tasks.CountTask;
import rr.tool.tasks.GCRunner;
import rr.tool.tasks.MemoryStatsTask;
import rr.tool.tasks.ThreadStacksTask;
import rr.tool.tasks.TimeOutTask;
import tools.gcp.GCPEvents;
import tools.gcp.GCPTool;
import acme.util.Assert;
import acme.util.StringMatchResult;
import acme.util.StringMatcher;
import acme.util.Util;
import acme.util.Yikes;
import acme.util.count.Counter;
import acme.util.io.SplitOutputWriter;
import acme.util.io.URLUtils;
import acme.util.io.XMLWriter;
import acme.util.option.CommandLine;
import acme.util.option.CommandLineOption;
import acme.util.time.TimedStmt;

public class RR {


	public static final CommandLineOption<String> toolPathOption = CommandLine.makeString("toolpath", Util.getenv("RR_TOOLPATH",""), CommandLineOption.Kind.STABLE, "The class path used to find RoadRunner tools specified.");
	public static final CommandLineOption<String> classPathOption = CommandLine.makeString("classpath", ".", CommandLineOption.Kind.STABLE, "The class path used to load classes from the target program.");

	public static CommandLineOption<String> toolOption = 
			CommandLine.makeString("tool", "rrtools.simple.EmptyTool", CommandLineOption.Kind.STABLE, "The tool chain to use.  Can be single tool, sequentially composed tools, parallel composed tools, or parenthesized chain.  " + 
					"Specified with full class names or abbrevations in rr.props files on the toolpath.  " +
					"Examples: \n   -tool=FT\n   -tool=TL:V\n  -tool=rrtools.fastrack.FastTrack\n  -tool=FT|HB\n  -tool=FT:(P|V)", 
					new Runnable() { public void run() { RR.createTool(); } } );

	public static CommandLineOption<Boolean> printToolsOption = 
			CommandLine.makeBoolean("tools", false, CommandLineOption.Kind.STABLE, "Print all known tools", 
					new Runnable() { public void run() { RR.printAbbrevs(); } } );

	public static CommandLineOption<Boolean> noxmlOption = 
			CommandLine.makeBoolean("noxml", false, CommandLineOption.Kind.STABLE, "Turn off printing the xml summary at the end of the run.");

	public static CommandLineOption<Boolean> forceGCOption = 
			CommandLine.makeBoolean("constantGC", false, CommandLineOption.Kind.EXPERIMENTAL, "Turn on constant garbage collection.",
					new GCRunner());

	public static CommandLineOption<Boolean> nofastPathOption = 
			CommandLine.makeBoolean("noFP", false, CommandLineOption.Kind.STABLE, "Do not use in-lined tool fastpath code for reads/writes.");

	public static CommandLineOption<Boolean> noShutdownHookOption = 
			CommandLine.makeBoolean("noShutdownHook", false, CommandLineOption.Kind.EXPERIMENTAL, "Don't register shutdown hook.");

	public static CommandLineOption<Boolean> noEnterOption = 
			CommandLine.makeBoolean("noEnter", false, CommandLineOption.Kind.STABLE, "Do not generate Enter and Exit events.");

	public static CommandLineOption<Boolean> noBranchOption =
			CommandLine.makeBoolean("noBranch", false, CommandLineOption.Kind.STABLE, "Do not generate Branch events.");

	public static CommandLineOption<String> xmlFileOption = 
			CommandLine.makeString("xml", "log.xml", CommandLineOption.Kind.STABLE, "Log file name for the xml summary printed at the end of the run.");

	public static CommandLineOption<String> pulseOption =
			CommandLine.makeString("pulse", "", CommandLineOption.Kind.EXPERIMENTAL, "Install periodic tasks (stacks,stats,counts).  Example: -pulse=stacks:counts", new Runnable() { public void run() { RR.createTasks(); } } );

	public static CommandLineOption<Integer> timeOutOption =
			CommandLine.makeInteger("maxTime", 0, CommandLineOption.Kind.STABLE, "Maximum execution time in seconds.",
					new Runnable() { public void run() { Util.addToPeriodicTasks(new TimeOutTask()); } } );

	public static CommandLineOption<Long> memMaxOption = CommandLine.makeLong("maxMem", (10 * 1024), CommandLineOption.Kind.STABLE, "Maximum memory in MB.");

	public static CommandLineOption<Integer> maxTidOption = 
	    CommandLine.makeInteger("maxTid", Math.max(16,Runtime.getRuntime().availableProcessors()), CommandLineOption.Kind.STABLE, "Maximum number of active threads.");

	public static CommandLineOption<Boolean> stackOption = 
			CommandLine.makeBoolean("stacks", false, CommandLineOption.Kind.STABLE, "Record stack traces for printing in erros messages.  Stacks are expensive to compute, so by default RoadRunner doesn't (See ShadowThread.java).");

	public static CommandLineOption<Boolean> valuesOption = 
			CommandLine.makeBoolean("values", false, CommandLineOption.Kind.EXPERIMENTAL, "Pass tools.internal/new values for writes to tools.  Tools can then change the new value to be written.  You MUST run java with -noverify if you use -values in conjunction with array instrumentation.");

	public static final CommandLineOption<Boolean> noTidGCOption = 
			CommandLine.makeBoolean("noTidGC", false, CommandLineOption.Kind.EXPERIMENTAL, "Do not reuse the tid for a thread that has completed.");

	public static final CommandLineOption<Boolean> noEventReuseOption = 
			CommandLine.makeBoolean("noEventReuse", false, CommandLineOption.Kind.EXPERIMENTAL, "Turn of Event Reuse.");

	public static final CommandLineOption<Boolean> trackMemoryUsageOption = 
			CommandLine.makeBoolean("trackMemoryUsage", false, CommandLineOption.Kind.EXPERIMENTAL, "Install monitors on GC to track Memory Usage",
					new Runnable() { 
				public void run() { 
					final MemoryMXBean bean = ManagementFactory.getMemoryMXBean();

					NotificationListener listener = new javax.management.NotificationListener() {
						public void handleNotification(Notification notification, Object handback)  {
							updateMemoryUsage(bean);
						}
					};

					for (GarbageCollectorMXBean gc: ManagementFactory.getGarbageCollectorMXBeans()) {
						Util.log("Adding Memory Tracking Listener to GC " + gc.getName());
						NotificationEmitter emitter = (NotificationEmitter) gc;
						emitter.addNotificationListener(listener, null, null);
					}
				} } );
	
	public static final CommandLineOption<Boolean> printEventOption =
			CommandLine.makeBoolean("printEvent", false, CommandLineOption.Kind.EXPERIMENTAL, "Eligible Tools: GPCTool, WDCTool | Print each event as it is processed for the tool.");
	
	public static final CommandLineOption<Boolean> countEventOption =
			CommandLine.makeBoolean("countEvent", false, CommandLineOption.Kind.EXPERIMENTAL, "Counts the number of events encountered for each type.");

	public static final CommandLineOption<Boolean> testingConfigOption = 
			CommandLine.makeBoolean("testConfig", false, CommandLineOption.Kind.EXPERIMENTAL, "Enable testing configuration");
	
	public static final CommandLineOption<Boolean> raptorPrintLatexOption =
			CommandLine.makeBoolean("raptorPrintLatex", false, CommandLineOption.Kind.EXPERIMENTAL, "Print trace in Latex format of execution from the Raptor tool.");
	
	public static final CommandLineOption<Boolean> raptorPrintDatalogOption =
			CommandLine.makeBoolean("raptorPrintDatalog", false, CommandLineOption.Kind.EXPERIMENTAL, "Print trace *.input files to run program in Datalog from Raptor tool.");
	
	public static final CommandLineOption<Boolean> raptorPrintDatalogConcurrentlyOption =
			CommandLine.makeBoolean("raptorPrintDatalogConcurrently", false, CommandLineOption.Kind.EXPERIMENTAL, "Print trace *.input files while Raptor executes.");
	
	public static final CommandLineOption<Integer> raptorSetDatalogEventLimitOption =
			CommandLine.makeInteger("raptorSetDatalogEventLimit", -1, CommandLineOption.Kind.EXPERIMENTAL, "Set trace event limit per *.input file for Datalog traces. Default: -1 [no limit]");
	
	public static final CommandLineOption<Boolean> raptorPrintLocksetOption =
			CommandLine.makeBoolean("raptorPrintLockset", false, CommandLineOption.Kind.EXPERIMENTAL, "Print a lockset once it is updated within each algorithm from Raptor tool.");
	
	public static final CommandLineOption<Boolean> raptorHBModeOption =
			CommandLine.makeBoolean("raptorHBMode", false, CommandLineOption.Kind.EXPERIMENTAL, "Raptor implementation will only track and report HB races using variable and lock locksets");
	
	public static final CommandLineOption<Boolean> raptorHBPureOption =
			CommandLine.makeBoolean("raptorHBPure", false, CommandLineOption.Kind.EXPERIMENTAL, "Raptor implementation will only track and report HB races");
	
	public static final CommandLineOption<Boolean> raptorStaticInitializerOption = 
			CommandLine.makeBoolean("raptorStaticInit", false, CommandLineOption.Kind.EXPERIMENTAL, "Raptor static initializer");
	
	public static final CommandLineOption<Boolean> raptorPrintStats = 
			CommandLine.makeBoolean("raptorPrintStats", false, CommandLineOption.Kind.EXPERIMENTAL, "Print statistics for events and lockset objects");
	
	public static final CommandLineOption<Integer> raptorVerbose =
			CommandLine.makeInteger("raptorVerbose", -1, CommandLineOption.Kind.EXPERIMENTAL, "Turn on various levels of debugging information printout.");

	public static final CommandLineOption<Boolean> wdcBuildEventGraph =
			CommandLine.makeBoolean("wdcBuildEventGraph", false, CommandLineOption.Kind.EXPERIMENTAL, "The Event Graph must be built in order to run WDC-B");
	
	public static final CommandLineOption<Boolean> wdcbGenerateFileForDetectedCycleOption =
			CommandLine.makeBoolean("wdcGenerateDetectedCycleFile", false, CommandLineOption.Kind.EXPERIMENTAL, "Generates a file for Graphviz to visualize the detected cycle");
	
	public static final CommandLineOption<Boolean> wdcbPrintReordering =
			CommandLine.makeBoolean("wdcPrintReordering", false, CommandLineOption.Kind.EXPERIMENTAL, "Print the reordered trace of all WDC races without cycles.");
	
	public static final CommandLineOption<Boolean> wdcHBWCPOnlyOption =
			CommandLine.makeBoolean("wdcHBWCPOnly", false, CommandLineOption.Kind.EXPERIMENTAL, "Enable only analysis of HB and WCP relation.");
	
	public static final CommandLineOption<Boolean> CAPOOption =
			CommandLine.makeBoolean("CAPO", false, CommandLineOption.Kind.EXPERIMENTAL, "DC analysis without instrumenting Rule b of the DC relation.");

	public static final CommandLineOption<Integer> wdcRandomReorderings =
			CommandLine.makeInteger("wdcRandomReorderings", 0, CommandLineOption.Kind.EXPERIMENTAL, "If reordering fails, try again (latest-first, earliest-first, then random).");
	
	public static final CommandLineOption<Boolean> wdcHBOnlyOption =
			CommandLine.makeBoolean("wdcHBOnly", false, CommandLineOption.Kind.EXPERIMENTAL, "Enable only analysis of HB relation.");

	public static final CommandLineOption<Boolean> wdcRemoveRaceEdge =
			CommandLine.makeBoolean("wdcRemoveRaceEdge", false, CommandLineOption.Kind.DEPRECATED, "Remove the edge between racing accesses before vindication.");
	
	public static final CommandLineOption<Boolean> dcHBOption =
			CommandLine.makeBoolean("dcHB", false, CommandLineOption.Kind.EXPERIMENTAL, "Enable HB analysis." );
	
	public static final CommandLineOption<Boolean> dcWCPOption =
			CommandLine.makeBoolean("dcWCP", false, CommandLineOption.Kind.EXPERIMENTAL, "Enable WCP analysis." );
	
	public static final CommandLineOption<Boolean> dcDCOption =
			CommandLine.makeBoolean("dcDC", false, CommandLineOption.Kind.EXPERIMENTAL, "Enable DC analysis." );
	
	public static final CommandLineOption<Boolean> dcWCP_DCOption =
			CommandLine.makeBoolean("dcWCP_DC", false, CommandLineOption.Kind.EXPERIMENTAL, "Enable WCP analysis and DC analysis.");

	public static final CommandLineOption<Boolean> dcNWCOption =
			CommandLine.makeBoolean("dcNWC", false, CommandLineOption.Kind.EXPERIMENTAL, "Enable NWC analysis: WCP w/o write-write conflicts." );

	public static final CommandLineOption<Boolean> dcWCP_NWCOption =
			CommandLine.makeBoolean("dcWCP_NWC", false, CommandLineOption.Kind.EXPERIMENTAL, "Enable WCP analysis and NWC analysis." );
	
	public static final CommandLineOption<Boolean> dcWBROption =
			CommandLine.makeBoolean("dcWBR", false, CommandLineOption.Kind.EXPERIMENTAL, "Enable DP analysis.");

	public static final CommandLineOption<Boolean> dcNWC_DCOption =
			CommandLine.makeBoolean("dcNWC_DC", false, CommandLineOption.Kind.EXPERIMENTAL, "Enable NWC analysis and DC analysis." );

	public static final CommandLineOption<Boolean> dcNWC_WBROption =
			CommandLine.makeBoolean("dcNWC_WBR", false, CommandLineOption.Kind.EXPERIMENTAL, "Enable NWC analysis and WBR analysis." );

	public static final CommandLineOption<Boolean> dcuDPOption =
			CommandLine.makeBoolean("dcuDP", false, CommandLineOption.Kind.EXPERIMENTAL, "Enable uDP analysis: DP w/o branches.");

	public static final CommandLineOption<Boolean> dcWCP_uDPOption =
			CommandLine.makeBoolean("dcWCP_uDP", false, CommandLineOption.Kind.EXPERIMENTAL, "Enable WCP analysis and uDP analysis.");
	
	public static final CommandLineOption<Boolean> dcWCP_WBROption =
			CommandLine.makeBoolean("dcWCP_WBR", false, CommandLineOption.Kind.EXPERIMENTAL, "Enable WCP analysis and DP analysis.");

	public static final CommandLineOption<Boolean> dcDC_WBROption =
			CommandLine.makeBoolean("dcDC_WBR", false, CommandLineOption.Kind.EXPERIMENTAL, "Enable DC analysis, and DP analysis.");

	public static final CommandLineOption<Boolean> dcWCP_DC_WBROption =
			CommandLine.makeBoolean("dcWCP_DC_WBR", false, CommandLineOption.Kind.EXPERIMENTAL, "Enable WCP analysis, DC analysis, and DP analysis.");

	public static final CommandLineOption<Boolean> dcWCP_NWC_DC_WBROption =
			CommandLine.makeBoolean("dcWCP_NWC_DC_WBR", false, CommandLineOption.Kind.EXPERIMENTAL, "Enable WCP analysis, NWC analysis, DC analysis, and DP analysis.");

	public static final CommandLineOption<Boolean> dcWCP_NWC_WBROption =
			CommandLine.makeBoolean("dcWCP_NWC_WBR", false, CommandLineOption.Kind.EXPERIMENTAL, "Enable WCP analysis, SDP analysis and WDP analysis.");

	public static final CommandLineOption<Boolean> dcWCP_DC_uDP_WBROption =
			CommandLine.makeBoolean("dcWCP_DC_uDP_WBR", false, CommandLineOption.Kind.EXPERIMENTAL, "Enable WCP analysis, DC analysis, uDP analysis, DP analysis.");
	
	public static final CommandLineOption<Boolean> dcWCP_DC_WBR_LSHEOption =
			CommandLine.makeBoolean("dcWCP_DC_WBR_LSHE", false, CommandLineOption.Kind.EXPERIMENTAL, "Enable WCP analysis, DC analysis, DP analysis, and LSHE analysis.");

	public static final CommandLineOption<Boolean> dcVerbose =
			CommandLine.makeBoolean("dcDebug", false, CommandLineOption.Kind.EXPERIMENTAL, "Perform sanity checks on the analysis.");

	public static final CommandLineOption<Boolean> dcVerboseGraph =
			CommandLine.makeBoolean("dcDebugGraph", false, CommandLineOption.Kind.EXPERIMENTAL, "Perform sanity checks on the event graph.");
	
	public static final CommandLineOption<String> goldilocksTrainingDataDir =
			CommandLine.makeString("goldilocksTrainingDataDir", null, CommandLineOption.Kind.EXPERIMENTAL, "Where to find sites.txt generated by Goldilocks in training mode");

	public static final CommandLineOption<Boolean> brNoSDG =
			CommandLine.makeBoolean("brNoSDG", false, CommandLineOption.Kind.DEPRECATED, "Does nothing, instead not setting brSDGClassName implies brNoSDG.");
	
	public static final CommandLineOption<String> brSDGClassName =
			CommandLine.makeString("brSDGClassName", null, CommandLineOption.Kind.EXPERIMENTAL, "Class name for the input SDG file.");
	
	public static final CommandLineOption<String> brProjectPath =
			CommandLine.makeString("brProjectPath", System.getProperty("user.dir"), CommandLineOption.Kind.EXPERIMENTAL, "Project Path for the input SDG file.");

	public static final CommandLineOption<Boolean> brWrWrEdges =
			CommandLine.makeBoolean("brWrWrEdges", false, CommandLineOption.Kind.EXPERIMENTAL, "Adds wr-wr edges to BR.");

	public static final CommandLineOption<Boolean> brConservative =
			CommandLine.makeBoolean("brConservative", false, CommandLineOption.Kind.EXPERIMENTAL, "Draw wr-rd and rd-wr edges even when there are no dependent branches.");

	public static final CommandLineOption<Boolean> brStrict =
			CommandLine.makeBoolean("brStrict", false, CommandLineOption.Kind.EXPERIMENTAL, "Draw rd-wr and wr-rd (and wr-wr if enabled) edges release-to-access.");

	public static final CommandLineOption<Boolean> brDisableLWConstraints =
			CommandLine.makeBoolean("brDisableLWConstraints", true, CommandLineOption.Kind.EXPERIMENTAL, "Do not add edges for LW constraints.");

	public static final CommandLineOption<Boolean> brAssumeBranches =
			CommandLine.makeBoolean("brAssumeBranches", false, CommandLineOption.Kind.EXPERIMENTAL, "Assume that each read is followed by a branch. Implies noBranch.");

	public static final CommandLineOption<Boolean> brVindicateRandomInstances =
			CommandLine.makeBoolean("brVindicateRandomInstances", false, CommandLineOption.Kind.EXPERIMENTAL, "Pick random instances of static races to vindicate rather than just the first one.");

	public static final CommandLineOption<Integer> brVindicateEarlyThenRandom =
			CommandLine.makeInteger("brVindicateEarlyThenRandom", Integer.MAX_VALUE, CommandLineOption.Kind.EXPERIMENTAL, "After this many earliest instances, start picking random instances.");

	public static final CommandLineOption<Boolean> brVindicateShortestInstances =
			CommandLine.makeBoolean("brVindicateShortestInstances", false, CommandLineOption.Kind.EXPERIMENTAL, "Pick the shortest instances of static races first to vindicate rather than just the first one.");

	public static final CommandLineOption<Integer> brVindicateRetries =
			CommandLine.makeInteger("brVindicateRetries", 0, CommandLineOption.Kind.EXPERIMENTAL, "Retry this many dynamic instances of static races.");

	public static final CommandLineOption<Boolean> brRetryVerifiedRaces =
			CommandLine.makeBoolean("brRetryVerifiedRaces", false, CommandLineOption.Kind.EXPERIMENTAL, "With brVindicateRetries, also retry verified races.");

	public static final CommandLineOption<Boolean> brVindicateDCRaces =
			CommandLine.makeBoolean("brVindicateDCRaces", true, CommandLineOption.Kind.EXPERIMENTAL, "Vindicate DC races.");

	public static final CommandLineOption<Boolean> brVindicateNWCRaces =
			CommandLine.makeBoolean("brVindicateNWCRaces", true, CommandLineOption.Kind.EXPERIMENTAL, "Vindicate SDP races.");

	public static final CommandLineOption<Boolean> brForwardCheck =
			CommandLine.makeBoolean("brForwardCheck", false, CommandLineOption.Kind.EXPERIMENTAL, "Perform a forward check to verify correctness of built traces.");

	public static final CommandLineOption<Boolean> brVindicateWindowOnly =
			CommandLine.makeBoolean("brVindicateWindowOnly", false, CommandLineOption.Kind.EXPERIMENTAL, "Only build a reordered trace for the events within the reordering window. Implies no forward checking.");

	public static final CommandLineOption<Long> brReorderTimeout =
			CommandLine.makeLong("brReorderTimeout", 0, CommandLineOption.Kind.EXPERIMENTAL, "Stop reordering after this many milliseconds.");

	public static final CommandLineOption<Integer> reportingLevel =
			CommandLine.makeInteger("reportingLevel", 0, CommandLineOption.Kind.EXPERIMENTAL, "0: access source locations only. 1: show field names. 2: source locations of all events. 4: labels. Do OR of these options to enable multiple options.");

	public static final CommandLineOption<Boolean> printReorderings =
			CommandLine.makeBoolean("printReorderings", false, CommandLineOption.Kind.EXPERIMENTAL, "Print verified reorderings.");

	public static final CommandLineOption<Boolean> dontBuildTrace =
			CommandLine.makeBoolean("dontBuildTrace", false, CommandLineOption.Kind.EXPERIMENTAL, "Do perform the reordering, but don't construct the actual reordered trace.");

	public static final CommandLineOption<Boolean> pipHBOption =
			CommandLine.makeBoolean("pipHB", false, CommandLineOption.Kind.EXPERIMENTAL, "Use the HB configuration.");

	public static final CommandLineOption<Boolean> pipWCPOption =
			CommandLine.makeBoolean("pipWCP", false, CommandLineOption.Kind.EXPERIMENTAL, "Use the WCP configuration.");

	public static final CommandLineOption<Boolean> pipDCOption =
			CommandLine.makeBoolean("pipDC", false, CommandLineOption.Kind.EXPERIMENTAL, "Use the DC configuration.");

	public static final CommandLineOption<Boolean> pipWDPOption =
			CommandLine.makeBoolean("pipWDP", false, CommandLineOption.Kind.EXPERIMENTAL, "Use the WDP configuration.");

	public static final CommandLineOption<Boolean> pipCAPOOption =
			CommandLine.makeBoolean("pipCAPO", false, CommandLineOption.Kind.EXPERIMENTAL, "Use the CAPO configuration.");

	public static final CommandLineOption<Boolean> pipAGGOption =
			CommandLine.makeBoolean("pipAGG", false, CommandLineOption.Kind.EXPERIMENTAL, "Use the CAPO w/ FTO + RE optimization including aggressively weak establishment of Rule(a)");

	public static final CommandLineOption<Boolean> pipFTOOption =
			CommandLine.makeBoolean("pipFTO", false, CommandLineOption.Kind.EXPERIMENTAL, "Enable Ownership Analysis Logic.");

	public static final CommandLineOption<Boolean> pipREOption =
			CommandLine.makeBoolean("pipRE", false, CommandLineOption.Kind.EXPERIMENTAL, "Enable Rule(a) optimization.");

	public static final CommandLineOption<Boolean> verboseOption =
			CommandLine.makeBoolean("VERBOSE", false, CommandLineOption.Kind.EXPERIMENTAL, "Enables VERBOSE output.");

	public static final CommandLineOption<Boolean> debugOption =
			CommandLine.makeBoolean("DEBUG", false, CommandLineOption.Kind.EXPERIMENTAL, "Enables expensive DEBUG checks.");

	public static final CommandLineOption<Boolean> countRaceOption =
			CommandLine.makeBoolean("countRace", true, CommandLineOption.Kind.EXPERIMENTAL, "Enable synchronized counting of dataraces detected.");

	public static final CommandLineOption<Boolean> dontCaptureShortestRace =
			CommandLine.makeBoolean("countRace", false, CommandLineOption.Kind.EXPERIMENTAL, "If multiple accesses are racing, capture an arbitrary one, not the shortest one.");


	public static final StringMatcher toolCode = new StringMatcher(StringMatchResult.REJECT, "+acme..*", "+rr..*", "+java..*");

	private static volatile boolean shuttingDown = false;
	private static boolean timeOut = false;

	private static long startTime;
	private static long endTime;

	private static Tool tool;

	protected static Tool firstEnter, firstExit, firstAcquire, firstRelease, firstAccess, firstBranch; //, nextArrayAccess;

	private static ToolLoader toolLoader;

	public static void createDefaultToolIfNecessary() {
		// default values, in case no tool change is supplied.
		if (getTool() == null) {
			setTool(new LastTool("Last Tool", null));
			firstEnter = firstExit = firstAcquire = firstRelease = firstAccess = firstBranch = getTool(); // nextArrayAccess = tool;
		}
	}

	private static void printAbbrevs() {
		try {
			URL[] path = URLUtils.getURLArrayFromString(System.getProperty("user.dir"), toolPathOption.get());
			Util.logf("Creating tool loader with path %s", java.util.Arrays.toString(path));
			toolLoader = new ToolLoader(path);
			Util.log(toolLoader.toString());
			Util.exit(0);
		} catch (Exception e) {
			Assert.panic(e);
		}

	}

	public static void initToolLoader() {
		if (toolLoader == null) {
			final URL[] urls = URLUtils.getURLArrayFromString(System.getProperty("user.dir"), toolPathOption.get());
			Util.logf("Creating tool loader with path %s", java.util.Arrays.toString(urls));
			toolLoader = new ToolLoader(urls);
		}
	}

	private static void createTool() { 
		try {
			Util.log(new TimedStmt("Creating Tool Chain") {

				protected Tool createChain(String methodName) {
					Tool t = getTool().findFirstImplementor(methodName);
					Util.logf("  %10s chain: %s", methodName, getTool().findAllImplementors(methodName));
					return t;
				}

				@Override
				public void run() throws Exception {
					initToolLoader();
					setTool(rr.tool.parser.parser.build(toolLoader, toolOption.get(), toolOption.getCommandLine()));
					Util.logf("    complete chain: %s", getTool().toChainString());

					firstEnter = createChain("enter");
					firstExit = createChain("exit");
					firstAcquire = createChain("acquire");
					firstRelease = createChain("release");
					firstAccess = createChain("access");
					firstBranch = createChain("branch");

					if (!nofastPathOption.get()) {
						List<Tool> readFP = getTool().findAllImplementors("readFastPath");
						List<Tool> writeFP = getTool().findAllImplementors("writeFastPath");
						List<Tool> arrayReadFP = getTool().findAllImplementors("arrayReadFastPath");
						List<Tool> arrayWriteFP = getTool().findAllImplementors("arrayWriteFastPath");
						List<Tool> fieldReadFP = getTool().findAllImplementors("fieldReadFastPath");
						List<Tool> fieldWriteFP = getTool().findAllImplementors("fieldWriteFastPath");
						if (readFP.size() + writeFP.size() + arrayReadFP.size() + arrayWriteFP.size() + fieldReadFP.size() + fieldWriteFP.size() == 0) {
							Util.log("No fastpath code found");
							nofastPathOption.set(true);
						} else {
							Util.log("Read Fastpath code found in " + readFP);						
							Util.log("Write Fastpath code found in " + writeFP);						
							Util.log("Array Read Fastpath code found in " + arrayReadFP);						
							Util.log("Array Write Fastpath code found in " + arrayWriteFP);				
							Util.log("Field Read Fastpath code found in " + fieldReadFP);						
							Util.log("Field Write Fastpath code found in " + fieldWriteFP);						
						}
					} else {
						Util.log("User has disabled fastpath instrumentation.");
					}
					noEnterOption.set(noEnterOption.get() || (firstEnter.getClass() == LastTool.class && firstExit.getClass() == LastTool.class));
					if (noEnterOption.get()) {
						Util.log("No Tools implement enter/exit hooks.");
					}
					noBranchOption.set(noBranchOption.get() || firstBranch.getClass() == LastTool.class);
					if (noBranchOption.get()) {
						Util.log("No Tools implement branch hooks.");
					}
					if (brAssumeBranches.get()) {
						noBranchOption.set(true);
						Util.log("Not instrumenting branches, instead simulating a branch after each read.");
					}
					if (brVindicateWindowOnly.get() && brForwardCheck.get()) {
						Util.error("brVindicateWindowOnly and brForwardCheck are mutually exclusive, please disable either option.");
					}
				}
			}
					); 
		} catch (Exception e) {
			Assert.panic(e);
		}
	}

	public static void applyToTools(ToolVisitor tv) {
		getTool().accept(tv);
	}

	public static void startUp() {

		/* 
		 * Set a default handler to bail quickly if we run out of memory.
		 * Otherwise, the shutdown routine will run and sometimes hang due to 
		 * lack of memory.
		 */
		Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler() {
			@Override
			public void uncaughtException(Thread t, Throwable e) {
				if (e instanceof OutOfMemoryError) {
					try {
						System.err.println("## Out of Memory");
					} finally {
						Runtime.getRuntime().halt(17);
					}
				} else {
					Assert.panic(e);
				}
			}
		});

		Runtime.getRuntime().addShutdownHook(new Thread("RR Shutdown") {
			@Override
			public void run() {
				shutDown();
			}
		});

		Util.log("Tool Init()");

		applyToTools(new ToolVisitor() {
			public void apply(Tool t) {
				t.init();
			}
		});

	}

	public static void shutDown() {
		if (shuttingDown) return;
		shuttingDown = true;
		if (endTime == 0) {
			endTimer(); // call here in case the target didn't exit cleanly
		}

		// always always always print time
		boolean tmp = Util.quietOption.get();
		Util.quietOption.set(false);
		Util.logf("Total Time: %d", (endTime - startTime));
		Util.quietOption.set(tmp);

		if (RR.noShutdownHookOption.get()) {
			return;
		}

		if (false) {
			shortXML();
			return;
		} else {
			xml();
		}
		Util.quietOption.set(false);
		Util.logf("Time = %d", endTime - startTime);
		final String dump = Instrumentor.dumpClassOption.get();
		if (!dump.equals("")) {
			MetaDataInfoMaps.dump(dump + "/rr.meta");
			try {
				PrintWriter pw = new PrintWriter(new FileWriter(dump + "/rr.meta.txt"));
				MetaDataInfoMaps.print(pw);
				pw.close();
			} catch (IOException e) {
				Assert.panic(e);
			}
		}
	}

	/***********/

	private static void createTasks() {
		String tasks[] = pulseOption.get().split(":");
		for (String t : tasks) {
			if (t.equals("stacks")) {
				Util.addToPeriodicTasks(new ThreadStacksTask());
			} else if (t.equals("stats")) {
				Util.addToPeriodicTasks(new MemoryStatsTask());
			} else if (t.equals("counts")) {
				Util.addToPeriodicTasks(new CountTask());
			} else {
				Assert.panic("Bad Task: " + t);
			}
		}
	}

	/******************/


	static volatile long maxMemCommitted = 0;
	static volatile long maxMemUsed = 0;
	static volatile long maxTotalMemory = 0;

	private static final double M = 1024 * 1024;

	protected static synchronized void updateMemoryUsage(final MemoryMXBean bean) {
		java.lang.management.MemoryUsage m = bean.getHeapMemoryUsage();
		maxMemCommitted = Math.max(m.getCommitted(), maxMemCommitted);
		maxMemUsed = Math.max(m.getUsed(), maxMemUsed);
		maxTotalMemory = Math.max(maxTotalMemory, Runtime.getRuntime().totalMemory());
		
//		Util.logf("   Mem Committed %d", maxMemCommitted / 1000000);
//		
//		long total = 0;
//		for (MemoryPoolMXBean x : ManagementFactory.getMemoryPoolMXBeans()) {
//			long y = x.getUsage().getCommitted() / 1000000;
//			Util.logf("      %s %d",  x.getName(), y);
//			total += y;
//		}
//		Util.logf("   Total %d", total);
	}


	/*** XML ***/

	private static final String[] systemInfo = { 
		"java.vm.version",	
		"java.vm.vendor",
		"java.vm.name",
		"java.class.path",
		"os.name",
		"os.arch",
		"os.version",
		"user.name",
		"user.dir"
	};

	private static volatile boolean inXml = false;
	private static void xml() {
		if (inXml) return;
		inXml = true;

		StringWriter sOut = new StringWriter();
		Writer outputWriter = noxmlOption.get() ? Util.openLogFile(xmlFileOption.get()) :
			new SplitOutputWriter(sOut, Util.openLogFile((xmlFileOption.get())));
		PrintWriter stringOut = new PrintWriter(outputWriter);

		final XMLWriter xml = new XMLWriter(stringOut);


		xml.push("entry");
		xml.print("date", new Date()); 
		xml.print("mode", RRMain.modeName());
		xml.print("timeout", timeOut ? "YES" : "NO");

		CommandLineOption.printAllXML(xml);

		xmlSystem(xml);

		Loader.printXML(xml);
		Counter.printAllXML(xml);
		Yikes.printXML(xml);

		applyToTools(new ToolVisitor() {
			public void apply(Tool t) {
				xml.push("tool");
				xml.print("name", t.toString());
				t.printXML(xml);
				xml.pop();			
			}
		});

		xml.print("threadCount", ShadowThread.numThreads());
		xml.print("threadMaxActive", ShadowThread.maxActiveThreads());
		xml.print("errorTotal", ErrorMessage.getTotalNumberOfErrors());
		xml.print("distinctErrorTotal", ErrorMessage.getTotalNumberOfDistinctErrors());
		ErrorMessages.xmlErrorsByMethod(xml);
		ErrorMessages.xmlErrorsByField(xml);
		ErrorMessages.xmlErrorsByArray(xml);
		ErrorMessages.xmlErrorsByLock(xml);
		ErrorMessages.xmlErrorsByFieldAccess(xml);
		ErrorMessages.xmlErrorsByErrorType(xml);
		xml.print("warningsTotal", Assert.getNumWarnings());
		xml.print("yikesTotal", Yikes.getNumYikes());
		xml.print("failed", Assert.getFailed());
		xml.print("failedReason", Assert.getFailedReason());


		xml.print("time", endTime - startTime);
		xml.pop();
		xml.close();

		Util.printf("%s", sOut.toString());
	}


	private static void shortXML() {
		if (inXml) return;
		inXml = true;

		StringWriter sOut = new StringWriter();
		Writer outputWriter = noxmlOption.get() ? Util.openLogFile(xmlFileOption.get()) :
			new SplitOutputWriter(sOut, Util.openLogFile((xmlFileOption.get())));
		PrintWriter stringOut = new PrintWriter(outputWriter);

		final XMLWriter xml = new XMLWriter(stringOut);

		xml.push("entry");
//		xml.print("date", new Date()); 
//		xml.print("mode", RRMain.modeName());
		xml.print("timeout", timeOut ? "YES" : "NO");

		xml.push("system");

		xml.print("availableProcs", Runtime.getRuntime().availableProcessors());

		MemoryMXBean bean = ManagementFactory.getMemoryMXBean();
		updateMemoryUsage(bean);
		xml.print("memCommitted",(int)Math.ceil(maxMemCommitted/M));
		xml.print("memUsed",(int)Math.ceil(maxMemUsed/M));
		xml.print("memTotal",(int)Math.ceil(maxTotalMemory/M));
		xml.pop();	
//		Loader.printXML(xml);
//		Counter.printAllXML(xml);
//		Yikes.printXML(xml);

//		applyToTools(new ToolVisitor() {
//			public void apply(Tool t) {
//				xml.push("tool");
//				xml.print("name", t.toString());
//				t.printXML(xml);
//				xml.pop();			
//			}
//		});

		xml.print("threadCount", ShadowThread.numThreads());
		xml.print("threadMaxActive", ShadowThread.maxActiveThreads());
		xml.print("errorTotal", ErrorMessage.getTotalNumberOfErrors());
		xml.print("distinctErrorTotal", ErrorMessage.getTotalNumberOfDistinctErrors());
//		ErrorMessages.xmlErrorsByMethod(xml);
//		ErrorMessages.xmlErrorsByField(xml);
//		ErrorMessages.xmlErrorsByArray(xml);
//		ErrorMessages.xmlErrorsByLock(xml);
//		ErrorMessages.xmlErrorsByFieldAccess(xml);
//		ErrorMessages.xmlErrorsByErrorType(xml);
		xml.print("warningsTotal", Assert.getNumWarnings());
		xml.print("yikesTotal", Yikes.getNumYikes());
		xml.print("failed", Assert.getFailed());
		xml.print("failedReason", Assert.getFailedReason());


		xml.print("time", endTime - startTime);
		xml.pop();
		xml.close();

		Util.printf("%s", sOut.toString());
	}
	
	
	public static void timeOut() {
		if (!shuttingDown) {
			timeOut = true;
			if (tool.toString().equals("tools.gcp.GCPTool")) { 
				tool.fini();
			}
			Util.exit(2);
		}
	}


	private static void xmlSystem(XMLWriter xml) {
		xml.push("system");

		try {
			java.net.InetAddress localMachine =
					java.net.InetAddress.getLocalHost();	
			xml.print("host", localMachine.getHostName());
		} catch(java.net.UnknownHostException uhe) {
			xml.print("host", "unknown");
		}

		for (String s : systemInfo) {
			xml.printWithFixedWidths("name", s, -35, "value", System.getProperty(s), -15);
		}

		xml.print("availableProcs", Runtime.getRuntime().availableProcessors());

		MemoryMXBean bean = ManagementFactory.getMemoryMXBean();
		updateMemoryUsage(bean);

		updateMemoryUsage(bean);
		xml.print("memCommitted",(int)Math.ceil(maxMemCommitted/M));
		xml.print("memUsed",(int)Math.ceil(maxMemUsed/M));
		xml.print("memTotal",(int)Math.ceil(maxTotalMemory/M));

		CompilationMXBean cbean = ManagementFactory.getCompilationMXBean();
		if (cbean!=null) xml.print("compileTime",cbean.getTotalCompilationTime());
		for (GarbageCollectorMXBean gcb : ManagementFactory.getGarbageCollectorMXBeans()) { 
			xml.printInsideScope("gc", "name",gcb.getName(), "time", gcb.getCollectionTime());
		}

		xml.pop();	
	}

	public static void startTimer() {
		startTime = System.currentTimeMillis();
	}

	public static void endTimer() {
		endTime = System.currentTimeMillis();
	}

	private static void setTool(Tool tool) {
		RR.tool = tool;
	}

	public static Tool getTool() {
		return tool;
	}

	public static ToolLoader getToolLoader() {
		initToolLoader();
		return toolLoader;
	}

	/*
	 * Return true if RR has started to shutdown and the target
	 * has officially ended.
	 */
	public static boolean targetFinished() {
		return shuttingDown;
	}
}
