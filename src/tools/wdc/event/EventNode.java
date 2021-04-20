package tools.wdc.event;

import acme.util.Assert;
import acme.util.Util;
import acme.util.collections.MutableReference;
import acme.util.collections.PerThread;
import acme.util.collections.Tuple2;
import acme.util.count.Counter;
import acme.util.count.TimerCounter;
import rr.state.ShadowLock;
import rr.state.ShadowThread;
import rr.state.ShadowVar;
import rr.tool.RR;
import tools.wdc.WDCTool;

import java.io.*;
import java.util.*;
import java.util.Map.Entry;

import static tools.wdc.WDCTool.NO_SDG;
import static tools.wdc.WDCTool.hasWBR;


public class EventNode implements Iterable<EventNode> {
	public static final Boolean WINDOW_ONLY = RR.brVindicateWindowOnly.get();
	public static final boolean DONT_BUILD_TRACE = !RR.dontBuildTrace.get();
	public int eventNumber;
	public final AcqRelNode inCS;
	public final short threadID; // TODO: change to ShadowThread? Also, could be combined with inCS; only AcqRelNode needs a threadID then
	public static final boolean VERBOSE_GRAPH = RR.dcVerboseGraph.get();

	Iterable<EventNode> sinkOrSinks = EMPTY_NODES;
	public Iterable<EventNode> sourceOrSources = EMPTY_NODES;

	static final Iterable<EventNode> EMPTY_NODES = Collections::emptyIterator;

	short myLatestTraversal;

	public static HashMap<Integer, EventNode> threadToFirstEventMap = new HashMap<Integer, EventNode>();
	static short nextTraversal = 1;

	/* Adds field names and some extra debug information to read/write events */
	public static final boolean DEBUG_ACCESS_INFO = (RR.reportingLevel.get() & 1) != 0;
	/* Adds source locations to ALL events. */
	public static final boolean DEBUG_SOURCE_LOCS = (RR.reportingLevel.get() & 2) != 0;
	/* Adds node labels to events */
	public static final boolean DEBUG_LABELS = (RR.reportingLevel.get() & 4) != 0;


	/* Perform a cycle check on every iteration of addconstraints. */
	static final boolean CYCLE_CHECK_EVERY_ITERATION = true;

	static final boolean PRINT_FAILED_TRACES = false;

	/* When the option is used, no edges are added for LW constraints. */
	private static final boolean ADD_LW_CONSTRAINTS = RR.brDisableLWConstraints.get();

	private static TimerCounter reordering = new TimerCounter("WDC", "ReorderingTime");
	private static TimerCounter lwConstraintsTime = new TimerCounter("WDC", "LWConstraintsTime");
	private static Counter coreReads = new Counter("WDC", "CoreReads");
	private static Counter lwConstraintsAdded = new Counter("WDC", "LWConstraintsAdded");
	private static TimerCounter addConstraintsIteration = new TimerCounter("WDC", "AddConstraintsIterationTime");

	private static final PerThread<Map<EventNode,String>> nodeLabelMap;
	private static final PerThread<Map<EventNode,String>> sourceLocMap;
	private static Map<EventNode,String> finalNodeLabelMap;
	private static Map<EventNode,String> finalSourceLocMap;

	static {
		nodeLabelMap = perThreadHashMapOrNull(DEBUG_LABELS);
		sourceLocMap = perThreadHashMapOrNull(DEBUG_SOURCE_LOCS);
	}

	private static PerThread<Map<EventNode, String>> perThreadHashMapOrNull(boolean makeMap) {
		if (!makeMap) return null;
		return new PerThread<>(
				RR.maxTidOption.get(),
				HashMap::new,
				(c, e) -> {
					c.putAll(e);
					return c;
				}
		);
	}

	private static EventNode firstEventInExecution = null;
	private static EventNode getFirstEvent(EventNode someEvent) {
		if (firstEventInExecution == null) {
			EventNode e = someEvent;
			while (true) {
				Iterator<EventNode> prior = e.sourceOrSources.iterator();
				if (!prior.hasNext()) break;
				e = prior.next();
			}
			firstEventInExecution = e;
		}
		return firstEventInExecution;
	}

	public EventNode(long eventNumber, int threadID, AcqRelNode inCS, String sourceLocation) {
		this(eventNumber, threadID, inCS, sourceLocation, "");
	}
	
	public EventNode(long eventNumber, int threadId, AcqRelNode inCS, String sourceLocation, String nodeLabel) {
		this.eventNumber = (int) eventNumber;
		this.inCS = inCS;
		this.threadID = (short) threadId;
		
		if (DEBUG_LABELS) {
			nodeLabelMap.getLocal(threadId).put(this, nodeLabel);
		}
		if (DEBUG_SOURCE_LOCS) {
			sourceLocMap.getLocal(threadId).put(this, sourceLocation);
		}
	}

	public static boolean edgeExists(EventNode sourceNode, EventNode sinkNode) {
		boolean exists = containsNode(sourceNode.sinkOrSinks, sinkNode);
		if (VERBOSE_GRAPH) Assert.assertTrue(containsNode(sinkNode.sourceOrSources, sourceNode) == exists);
		return exists;
	}
	
	public static void addEdge(EventNode sourceNode, EventNode sinkNode) {
		if (VERBOSE_GRAPH) {
			// Avoid cycles
			Assert.assertTrue(sourceNode != sinkNode);
			Assert.assertFalse(containsNode(sourceNode.sourceOrSources, sinkNode));
			Assert.assertFalse(containsNode(sinkNode.sinkOrSinks, sourceNode));
			// TODO: Double edges happen, but they are not breaking bugs
			//Assert.assertFalse(containsNode(sourceNode.sinkOrSinks, sinkNode));
			//Assert.assertFalse(containsNode(sinkNode.sourceOrSources, sourceNode));
		}
		synchronized(sourceNode) {
			sourceNode.sinkOrSinks = addNode(sourceNode.sinkOrSinks, sinkNode);
		}
		sinkNode.sourceOrSources = addNode(sinkNode.sourceOrSources, sourceNode);
		//Only update sinkNode's eventNumber if it has no sources (as in, before fini() is called)
		if (sinkNode.sinkOrSinks.equals(EMPTY_NODES) && sourceNode.eventNumber >= sinkNode.eventNumber) {
			sinkNode.eventNumber = sourceNode.eventNumber + 1;
			//if (VERBOSE_GRAPH) addEventToThreadToItsFirstEventsMap(sinkNode);
		} else {
			if (VERBOSE_GRAPH && WDCTool.isRunning()) {
//			if (VERBOSE_GRAPH) Assert.assertTrue(sinkNode.eventNumber >= 0);
				// If we are still running, we shouldn't be adding any backward edges
				Assert.assertTrue(sinkNode.sinkOrSinks.equals(EMPTY_NODES),
								  "Sink node of edge being added has outgoing edges: " + sinkNode.sinkOrSinks);
			}
		}
		if (VERBOSE_GRAPH) {
			Assert.assertFalse(sourceNode.eventNumber == sinkNode.eventNumber && sourceNode.getThreadId() == sinkNode.getThreadId(),
					"PO-ordered source and sink nodes have same event number!");
		}
	}

	/** addEdge, but without synchronization. Only to be used after execution. */
	private static void fastAddEdge(EventNode sourceNode, EventNode sinkNode) {
		if (VERBOSE_GRAPH) {
			Assert.assertTrue(sourceNode != sinkNode);
			Assert.assertFalse(WDCTool.isRunning());
			Assert.assertFalse(sourceNode == sinkNode, "Edge source and sink are same, creates cycle on the same node!");
			Assert.assertFalse(sourceNode.eventNumber == sinkNode.eventNumber && sourceNode.getThreadId() == sinkNode.getThreadId(),
					"PO-ordered source and sink nodes have same event number!");
		}

		sourceNode.sinkOrSinks = addNode(sourceNode.sinkOrSinks, sinkNode);
		sinkNode.sourceOrSources = addNode(sinkNode.sourceOrSources, sourceNode);
	}
	
	static Iterable<EventNode> addNode(Iterable<EventNode> nodeOrNodes, EventNode newNode) {
		if (VERBOSE_GRAPH) Assert.assertTrue(!containsNode(nodeOrNodes, newNode));
		if (nodeOrNodes == EMPTY_NODES) {
			return newNode;
		} else if (nodeOrNodes instanceof EventNode) {
			LinkedList<EventNode> nodes = new LinkedList<EventNode>();
			nodes.add((EventNode)nodeOrNodes);
			nodes.add(newNode);
			return nodes;
		} else {
			((LinkedList<EventNode>)nodeOrNodes).add(newNode);
			return nodeOrNodes;
		}
	}
	
	public static void removeEdge(EventNode sourceNode, EventNode sinkNode) {
		synchronized(sourceNode) {
			sourceNode.sinkOrSinks = removeNode(sourceNode.sinkOrSinks, sinkNode);
		}
		sinkNode.sourceOrSources = removeNode(sinkNode.sourceOrSources, sourceNode);
	}

	/** removeEdge, but without synchronization. Only to be used after execution. */
	private static void fastRemoveEdge(EventNode sourceNode, EventNode sinkNode) {
		sourceNode.sinkOrSinks = removeNode(sourceNode.sinkOrSinks, sinkNode);
		sinkNode.sourceOrSources = removeNode(sinkNode.sourceOrSources, sourceNode);
	}
	
	static Iterable<EventNode> removeNode(Iterable<EventNode> nodeOrNodes, EventNode nodeToRemove) {
		if (VERBOSE_GRAPH) Assert.assertTrue(containsNode(nodeOrNodes, nodeToRemove));
		if (VERBOSE_GRAPH) Assert.assertTrue(nodeOrNodes != EMPTY_NODES);
		if (nodeOrNodes instanceof EventNode) {
			if (VERBOSE_GRAPH) Assert.assertTrue(nodeOrNodes == nodeToRemove);
			return EMPTY_NODES;
		} else {
			LinkedList<EventNode> nodes = (LinkedList<EventNode>)nodeOrNodes;
			boolean removed = ((LinkedList<EventNode>)nodeOrNodes).remove(nodeToRemove);
			if (VERBOSE_GRAPH) Assert.assertTrue(removed);
			if (nodes.size() > 1) {
				return nodes;
			} else {
				return nodes.getFirst();
			}
		}
	}

	// Support iterating over just this one EventNode
	@Override
	public Iterator<EventNode> iterator() {
		return new Iterator<EventNode>() {
			boolean nextCalled;

			@Override
			public boolean hasNext() {
				return !nextCalled;
			}

			@Override
			public EventNode next() {
				if (nextCalled) {
					throw new NoSuchElementException();
				}
				nextCalled = true;
				return EventNode.this;
			}

			@Override
			public void remove() {
				throw new UnsupportedOperationException();
			}
		};
	}

	static boolean containsNode(Iterable<EventNode> nodes, EventNode nodeToFind) {
		for (EventNode node : nodes) {
			if (node == nodeToFind) {
				return true;
			}
		}
		return false;
	}

	static boolean containsNode(EventNode[] nodes, EventNode nodeToFind) {
		for (EventNode node : nodes) {
			if (node == nodeToFind) {
				return true;
			}
		}
		return false;
	}

	public static int prepUniqueTraversal() {
		return prepUniqueTraversal(1);
	}

	public synchronized static int prepUniqueTraversal(int inc) {
		return nextTraversal += inc;
	}

	public static void traverse(EventNode startNode, int traversal) {
		ArrayDeque<EventNode> grayNodes = new ArrayDeque<EventNode>();
		grayNodes.addLast(startNode);
		while (!grayNodes.isEmpty()) {
			EventNode node = grayNodes.removeFirst();
			if (node.myLatestTraversal != traversal) {
				node.myLatestTraversal = (short) traversal;
				for (EventNode sink : node.sinkOrSinks) {
					grayNodes.add(sink);
				}
			}
		}
	}

	static class Edge {
		Edge(EventNode source, EventNode sink) {
			this.source = source;
			this.sink = sink;
		}
		EventNode source;
		EventNode sink;
	}
	
	//Add Constraints (+ conflicting accesses)
	public static Tuple2<Integer, Set<RdWrNode>> addConstraints(RdWrNode firstNode, RdWrNode secondNode, long[] windowRange, LinkedList<Edge> initialEdges, LinkedList<Edge> initialEdgesToRemove, LinkedList<Edge> additionalBackEdges, LinkedList<Edge> additionalForwardEdges, LinkedList<Edge> additionalConflictingEdges) throws ReorderingTimeout {
		// Create edges from one node's predecessors to the other node
		for (EventNode source : secondNode.sourceOrSources) {
			Edge backEdge = new Edge(source, firstNode);
			if ((! edgeExists(source, firstNode)) && (! firstNode.equals(source))) { // This edge might already exist
				// TODO: is it reasonable for this edge to already exist? it's happening with aggressive merging enabled
				initialEdges.add(backEdge);
				initialEdgesToRemove.add(backEdge);
				fastAddEdge(source, firstNode);
			}
		}
		for (EventNode source : firstNode.sourceOrSources) {
			Edge forwardEdge = new Edge(source, secondNode);
			initialEdges.add(forwardEdge);
			if ((!edgeExists(source, secondNode)) && (! secondNode.equals(source))) { // This edge might already exist
				initialEdgesToRemove.add(forwardEdge);
				fastAddEdge(source, secondNode);
			}
		}

		final MutableReference<Set<RdWrNode>> refCoreReads = new MutableReference<>(new HashSet<>());

		int i = addConstraintsIteration.timeEx(iteration -> {
			Set<RdWrNode> coreReads = refCoreReads.t;
			boolean addedBackEdge = false; // back edge added on current iteration?
			boolean addedForwardEdge = false; // forward edge added on current iteration?
//		boolean addedConstraintEdge; // constraint edge added on current iteration? May create a new path for critical sections to need LS constraints added

			Util.println("Iteration = " + iteration);

			LinkedList<Edge> separateInitNodes = new LinkedList<Edge>();
			for (Edge initialEdge : initialEdges) {
				separateInitNodes.add(initialEdge);
			}
			for (Edge backEdge : additionalBackEdges) {
				separateInitNodes.add(backEdge);
			}
			for (Edge forwardEdge : additionalForwardEdges) {
				separateInitNodes.add(forwardEdge);
			}

			// Add last-writer constraints for CORE reads (only required for WBR)
			if (WDCTool.graphHasBranches) {
				Set<RdWrNode> newCoreReads = findCoreReads(firstNode, secondNode, windowRange, coreReads);  // Assumes that the race edge (or consecutive event edges) exist, otherwise repeat for first node
				if (newCoreReads == null) {
					refCoreReads.t = null;
					return false;
				}
				if (ADD_LW_CONSTRAINTS) {
					EventNode.coreReads.add(newCoreReads.size());
					lwConstraintsTime.start();
					for (RdWrNode read : newCoreReads) {
						if (read.hasNoLastWriter()) continue;
						RdWrNode write = read.lastWriter();
						if (!edgeExists(write, read) && write.getThreadId() != read.getThreadId()) {
							lwConstraintsAdded.inc();
							fastAddEdge(write, read);
							additionalConflictingEdges.add(new Edge(write, read));
						}
					}
					lwConstraintsTime.stop();
				}

				if (CYCLE_CHECK_EVERY_ITERATION && detectCycle(firstNode, secondNode, windowRange, iteration)) {
					Util.log("Found cycle after adding LW edges");
					refCoreReads.t = null;
					return false;
				}
			}

			for (Edge edge : separateInitNodes) {
				//Add LS Constraints
				// First do a reverse traversal from the second access and possibly from other edge sources
				HashMap<ShadowLock, HashMap<ShadowThread, AcqRelNode>> reachableAcqNodes = new HashMap<ShadowLock, HashMap<ShadowThread, AcqRelNode>>();
				ArrayDeque<EventNode> grayNodes = new ArrayDeque<EventNode>();
				int traversal = prepUniqueTraversal();
				grayNodes.add(edge.source);
				while (!grayNodes.isEmpty()) {
					ReorderingTimeout.checkTimeout();
					EventNode node = grayNodes.removeFirst();
					if (node.myLatestTraversal != traversal) {
						// We don't care about nodes outside the window
						if (node.eventNumber >= windowRange[0]/*windowMin*/) {
							// If this is an acquire, let's record it,
							// to figure out if it can reach an earlier (in total order) release of the same lock
							if (node instanceof AcqRelNode) {
								AcqRelNode acqRelNode = (AcqRelNode) node;
								if (acqRelNode.isAcquire()) {
									HashMap<ShadowThread, AcqRelNode> acqNodesForLock = reachableAcqNodes.get(acqRelNode.shadowLock);
									if (acqNodesForLock == null) {
										acqNodesForLock = new HashMap<ShadowThread, AcqRelNode>();
										reachableAcqNodes.put(acqRelNode.shadowLock, acqNodesForLock);
									}
									// We want the acq node that's latest in total order,
									// since earlier acq nodes on the same thread will be WDC ordered by definition.
									AcqRelNode currentAcqNodeForThread = acqNodesForLock.get(acqRelNode.getShadowThread());
									if (currentAcqNodeForThread == null || acqRelNode.eventNumber > currentAcqNodeForThread.eventNumber) {
										acqNodesForLock.put(acqRelNode.getShadowThread(), acqRelNode);
									}
								}
							} else if (node.inCS != null) {
								AcqRelNode surroundingAcq = node.inCS;
								while (surroundingAcq != null) {
									HashMap<ShadowThread, AcqRelNode> acqNodesForLock = reachableAcqNodes.get(surroundingAcq.shadowLock);
									if (acqNodesForLock == null) {
										acqNodesForLock = new HashMap<ShadowThread, AcqRelNode>();
										reachableAcqNodes.put(surroundingAcq.shadowLock, acqNodesForLock);
									}
									// We want the acq node that's latest in total order,
									// since earlier acq nodes on the same thread will be WDC ordered by definition.
									AcqRelNode currentAcqNodeForThread = acqNodesForLock.get(surroundingAcq.getShadowThread());
									if (currentAcqNodeForThread == null || surroundingAcq.eventNumber > currentAcqNodeForThread.eventNumber) {
										acqNodesForLock.put(surroundingAcq.getShadowThread(), surroundingAcq);
									}
									surroundingAcq = surroundingAcq.inCS;
								}
							}
							node.myLatestTraversal = (short) traversal;
							for (EventNode source : node.sourceOrSources) {
								grayNodes.add(source);
							}
						}
					}
				}

				// Second do a forward traversal from the first access and possibly from other edge sinks
				HashMap<ShadowLock, HashMap<ShadowThread, AcqRelNode>> reachableRelNodes = new HashMap<ShadowLock, HashMap<ShadowThread, AcqRelNode>>();
				traversal = prepUniqueTraversal();
				grayNodes.add(edge.sink);
				while (!grayNodes.isEmpty()) {
					ReorderingTimeout.checkTimeout();
					EventNode node = grayNodes.removeFirst();
					if (node.myLatestTraversal != traversal) {
						// We don't care about nodes outside the window
						if (node.eventNumber <= windowRange[1]/*windowMax*/) {
							// If this is a release, let's record it,
							// to figure out if it it's reached by a later (in total order) acquire of the same lock
							if (node instanceof AcqRelNode) {
								AcqRelNode acqRelNode = (AcqRelNode) node;
								if (!acqRelNode.isAcquire()) {
									HashMap<ShadowThread, AcqRelNode> relNodesForLock = reachableRelNodes.get(acqRelNode.shadowLock);
									if (relNodesForLock == null) {
										relNodesForLock = new HashMap<ShadowThread, AcqRelNode>();
										reachableRelNodes.put(acqRelNode.shadowLock, relNodesForLock);
									}
									// We want the rel node that's earliest in total order,
									// since later rel nodes on the same thread will be WDC ordered by definition.
									AcqRelNode currentRelNodeForThread = relNodesForLock.get(acqRelNode.getShadowThread());
									if (currentRelNodeForThread == null || acqRelNode.eventNumber < currentRelNodeForThread.eventNumber) {
										relNodesForLock.put(acqRelNode.getShadowThread(), acqRelNode);
									}
								}
							} else if (node.inCS != null) {
								AcqRelNode surroundingAcq = node.inCS;
								AcqRelNode surroundingRel = null;
								while (surroundingAcq != null) {
									surroundingRel = surroundingAcq.otherCriticalSectionNode;
									HashMap<ShadowThread, AcqRelNode> relNodesForLock = reachableRelNodes.get(surroundingRel.shadowLock);
									if (relNodesForLock == null) {
										relNodesForLock = new HashMap<ShadowThread, AcqRelNode>();
										reachableRelNodes.put(surroundingRel.shadowLock, relNodesForLock);
									}
									// We want the rel node that's earliest in total order,
									// since later rel nodes on the same thread will be WDC ordered by definition.
									AcqRelNode currentRelNodeForThread = relNodesForLock.get(surroundingRel.getShadowThread());
									if (currentRelNodeForThread == null || surroundingRel.eventNumber < currentRelNodeForThread.eventNumber) {
										relNodesForLock.put(surroundingRel.getShadowThread(), surroundingRel);
									}
									surroundingAcq = surroundingAcq.inCS;
								}
							}
							node.myLatestTraversal = (short) traversal;
							for (EventNode sink : node.sinkOrSinks) {
								grayNodes.add(sink);
							}
						}
					}
				}
				// Now check for edges that indicate a back edge w.r.t. total order
				for (ShadowLock shadowLock : reachableAcqNodes.keySet()) {
					ReorderingTimeout.checkTimeout();
					HashMap<ShadowThread, AcqRelNode> acqNodesForLock = reachableAcqNodes.get(shadowLock);
					HashMap<ShadowThread, AcqRelNode> relNodesForLock = reachableRelNodes.get(shadowLock);
					if (relNodesForLock != null) {
						for (AcqRelNode acqNode : acqNodesForLock.values()) {
							for (AcqRelNode relNode : relNodesForLock.values()) {
								//Back Edges
								if (acqNode.eventNumber > relNode.eventNumber &&
										!containsNode(acqNode.otherCriticalSectionNode.sinkOrSinks, relNode.otherCriticalSectionNode)) {
									// Might have to look outside of current window when validating backedges
									long tempWindowMin = Math.min(windowRange[0]/*windowMin*/, relNode.otherCriticalSectionNode.eventNumber);
									tempWindowMin = Math.min(tempWindowMin, relNode.eventNumber);
									long tempWindowMax = Math.max(windowRange[1]/*windowMax*/, acqNode.otherCriticalSectionNode.eventNumber);
									tempWindowMax = Math.max(tempWindowMax, acqNode.eventNumber);
									// Back edge found, but the acquire of both critical sections of the backedge need to reach either conflicting access
									if (bfsTraversal(relNode.otherCriticalSectionNode, firstNode, secondNode, tempWindowMin, tempWindowMax)
											&& bfsTraversal(acqNode, firstNode, secondNode, tempWindowMin, tempWindowMax)) {
										if (VERBOSE_GRAPH) {
											Assert.assertTrue(acqNode.otherCriticalSectionNode != relNode
													&& relNode.otherCriticalSectionNode != acqNode, "Back edge being added within release/acquire of the same critical section");
											Assert.assertTrue(bfsTraversal(acqNode, relNode, null, tempWindowMin, tempWindowMax), // Assert a path actually exists from acqNode -> relNode
													"tempWindowMin: " + tempWindowMin + " | tempWindowMax: " + tempWindowMax + " | acqNode: " + acqNode + " | relNode: " + relNode);
										}
										// Add back edge and signal we should repeat this whole process
										Util.println("Found acq->rel that needs back edge: " + shadowLock + ", " + acqNode + " -> " + relNode);
										fastAddEdge(acqNode.otherCriticalSectionNode, relNode.otherCriticalSectionNode);
										additionalBackEdges.add(new Edge(acqNode.otherCriticalSectionNode, relNode.otherCriticalSectionNode));
										windowRange[0] = Math.min(windowRange[0], relNode.otherCriticalSectionNode.eventNumber);
										windowRange[1] = Math.max(windowRange[1], acqNode.otherCriticalSectionNode.eventNumber);
										windowRange[0] = Math.min(windowRange[0], relNode.eventNumber);
										windowRange[1] = Math.max(windowRange[1], acqNode.eventNumber);
										addedBackEdge = true;
										// Add the acq/rel nodes of a new backedge to the set of starting accesses for new backedges
										// Mike says: I think acqNode.otherCriticalSectionNode isn't right
										/*
										acqSink.add(acqNode.otherCriticalSectionNode);
										relSource.add(relNode);
										*/
									}
								}
								//Forward Edges
								if (relNode.eventNumber > acqNode.eventNumber &&
										!relNode.otherCriticalSectionNode.equals(acqNode) && //make sure the relNode and acqNode are not the same critical section
										!containsNode(acqNode.otherCriticalSectionNode.sinkOrSinks, relNode.otherCriticalSectionNode)) { //don't add a forward edge if one has already been added
									long tempWindowMin = Math.min(windowRange[0]/*windowMin*/, acqNode.otherCriticalSectionNode.eventNumber);
									tempWindowMin = Math.min(tempWindowMin, acqNode.eventNumber);
									long tempWindowMax = Math.max(windowRange[1]/*windowMax*/, relNode.otherCriticalSectionNode.eventNumber);
									tempWindowMax = Math.max(tempWindowMax, relNode.eventNumber);
									// Forward edge found, but the acquire of both critical sections of the forwardedge need to reach either conflicting access
									if (bfsTraversal(relNode.otherCriticalSectionNode, firstNode, secondNode, tempWindowMin, tempWindowMax) && bfsTraversal(acqNode, firstNode, secondNode, tempWindowMin, tempWindowMax)) {
										if (VERBOSE_GRAPH)
											Assert.assertTrue(bfsTraversal(acqNode, relNode, null, tempWindowMin, tempWindowMax));//, "tempWindowMin: " + tempWindowMin + " | tempWindowMax: " + tempWindowMax + " | acqNode: " + acqNode.getNodeLabel() + ", eventNumber: " + acqNode.eventNumber + " | relNode: " + relNode.getNodeLabel() + ", eventNumber: " + relNode.eventNumber); // Assert a path actually exists from acqNode -> relNode
										// Add forward edge and signal we should repeat this whole process
										Util.println("Found rel->acq that needs forward edge: " + shadowLock + ", " + acqNode.otherCriticalSectionNode + " -> " + relNode.otherCriticalSectionNode);
										fastAddEdge(acqNode.otherCriticalSectionNode, relNode.otherCriticalSectionNode);
										additionalForwardEdges.add(new Edge(acqNode.otherCriticalSectionNode, relNode.otherCriticalSectionNode));
										//Window Size should not have to be modified.
										//Since release nodes are found traversing forward and acquire nodes are found traversing backward
										//and release execution later than acquire means an added forward edge from release's acquire to acquire's release will already be within the window
										windowRange[0] = Math.min(windowRange[0], acqNode.otherCriticalSectionNode.eventNumber);
										windowRange[1] = Math.max(windowRange[1], relNode.otherCriticalSectionNode.eventNumber);
										windowRange[0] = Math.min(windowRange[0], acqNode.eventNumber);
										windowRange[1] = Math.max(windowRange[1], relNode.eventNumber);
										addedForwardEdge = true;
									}
								}
							}
						}
					}
				}
			}
			return addedBackEdge || addedForwardEdge; // || addedConstraintEdge;
		});
		return new Tuple2<>(i, refCoreReads.t);
	}
	
	public static boolean detectCycle(RdWrNode firstNode, RdWrNode secondNode, long[] windowRange, int iterations) {
		final boolean useIterativeCycleDetection = true;
		Map<EventNode, List<EventNode>> firstCycleEdges = null;
		Map<EventNode, List<EventNode>> secondCycleEdges = null;
		if ( RR.wdcbGenerateFileForDetectedCycleOption.get() ) {
			firstCycleEdges = new HashMap<>();
			secondCycleEdges = new HashMap<>();
		}
		
		int black = EventNode.prepUniqueTraversal(2);
		int gray = black - 1;
		boolean secondCycleDetected;
		if (useIterativeCycleDetection) {
			if ( RR.wdcbGenerateFileForDetectedCycleOption.get() ) {
				secondCycleDetected = iterativeDfsDetectCycle(secondNode, false, gray, black, windowRange[0]/*windowMin*/, windowRange[1]/*windowMax*/, secondCycleEdges);
			} else {
				secondCycleDetected = simplerIterativeDetectCycle(secondNode, false, gray, black, windowRange[0]/*windowMin*/, windowRange[1]/*windowMax*/);
			}
		} else {
			secondCycleDetected = dfsDetectCycle(secondNode, false, gray, black, windowRange[0], windowRange[1]);
		}
		if (secondCycleDetected) Util.println("Cycle reaches second node : " + secondCycleDetected + ". After " + iterations + " iterations.");

		black = EventNode.prepUniqueTraversal(2);
		gray = black - 1;
		boolean firstCycleDetected;
		if (useIterativeCycleDetection) {
			if ( RR.wdcbGenerateFileForDetectedCycleOption.get() ) {
				firstCycleDetected = iterativeDfsDetectCycle(firstNode, false, gray, black, windowRange[0]/*windowMin*/, windowRange[1]/*windowMax*/, firstCycleEdges);
			} else {
				firstCycleDetected = simplerIterativeDetectCycle(firstNode, false, gray, black, windowRange[0]/*windowMin*/, windowRange[1]/*windowMax*/);
			}
		} else {
			firstCycleDetected = dfsDetectCycle(firstNode, false, gray, black, windowRange[0], windowRange[1]);
		}
		if (firstCycleDetected) Util.println("Cycle reaches first node : " + firstCycleDetected + ". After " + iterations + " iterations.");
				
		if ( RR.wdcbGenerateFileForDetectedCycleOption.get() ) {
			if ( firstCycleDetected ) {
				generateInputFileForGraphviz(firstCycleEdges, true, true, true);
			}	
			if ( secondCycleDetected ) {
				generateInputFileForGraphviz(secondCycleEdges, false, true, true);
			}
		}
		
		return firstCycleDetected || secondCycleDetected;
	}

	private static long reorderStartTime;

	/**
	 * @param firstNode First event in the race pair.
	 * @param secondNode Second event in the race pair.
	 * @param coreReads Set of read events that are CORE for the current reordering.
	 * @param commandDir
	 * @return False if reordering is successful, otherwise true.
	 */
	public static List<EventNode> constructReorderedTrace(RdWrNode firstNode, RdWrNode secondNode, Set<RdWrNode> coreReads, File commandDir, long[] windowRange) throws ReorderingTimeout {
		// Backward Reordering if no cycle was found for the WBR race
		Vector<EventNode> trPrime = null;

		int total_random_reorders = RR.wdcRandomReorderings.get();
		Assert.assertTrue(total_random_reorders >= 0);
		if (total_random_reorders > 0) {Util.log("Doing " + (total_random_reorders + 1) + " reorderings");}

		for (int reorders = 0; reorders <= total_random_reorders; reorders++) {
			trPrime = new Vector<>();
			String reorderName = reorderName(reorders);
			reorderStartTime = System.currentTimeMillis();

			HashSet<EventNode> missingEvents = new HashSet<>();
			int white = nextTraversal;
			Map<ShadowID, Map<Integer, RdWrNode>> thrToFirstWrPerVar = buildR(firstNode, secondNode, missingEvents, coreReads, windowRange);
			
			while (trPrime.isEmpty()) {
				trPrime = backReorderTrace(firstNode, secondNode, trPrime, white, reorders, missingEvents, thrToFirstWrPerVar, coreReads, windowRange);
				if (trPrime.isEmpty()) {
					Util.log("Backward Reordering " + reorders + " failed when doing " + reorderName + " reordering!");
//					Assert.assertTrue(false, "Backwards reordering got stuck!"); // DO NOT ENABLE, we can fail reordering in examples!
					// Checking for cycles every time reordering gets stuck is very costly for some reason
//					Map<EventNode, List<EventNode>> bfsCycleEdges = new HashMap<>();
//					black = EventNode.prepUniqueTraversal(2);
//					gray = black - 1;
//					iterativeDfsDetectCycle(firstNode, false, gray, black, windowRange[0]/*windowMin*/, windowRange[1]/*windowMax*/, bfsCycleEdges);
//					generateInputFileForGraphviz(bfsCycleEdges, true, true, true);
					break;
				} else if (trPrime.size() == 1) {
					Util.log("Needed missing event. Attempt Construct Reordered Trace again.");
					//Update R with missing release event and all reachable events
					missingEvents.add(trPrime.remove(trPrime.size() - 1));
					// TODO: Should we keep the old white, or get new one when rebuilding R?
					white = nextTraversal;
					thrToFirstWrPerVar = buildR(firstNode, secondNode, missingEvents, coreReads, windowRange);
				}
			}

			if (trPrime.size() == 0) {
				trPrime = null;
				Util.log("Unable to reorder trace.");
				continue;
			}
			// Reordering was successful
			if (commandDir != null){
				printReordering(trPrime, firstNode, secondNode, commandDir);
			}
			Collections.reverse(trPrime);
			boolean verified = !RR.brForwardCheck.get() || forwardVerifyReorderedTrace(trPrime, firstNode, secondNode, coreReads);
			if (!verified) {
				Util.println("============== Reordered trace is invalid! ================");
				printTrace(trPrime);
				trPrime = null;
				Util.println("============== End of invalid trace! ================");
			}
			if (verified && RR.printReorderings.get()) {
				Util.println("============ verified trace ==========");
				printTrace(trPrime);
				Util.println("============ end of verified trace =============");
			}
			Assert.assertTrue(verified && reorders == 0, "Reordered trace is invalid."); // only latest-first is guaranteed to not fail
			break;
		}
		return trPrime;
	}

	private static String reorderName(int reorders) {
		if (reorders == 0) return "latest-first";
		else if (reorders == 1) return "earliest-first";
		else return "random";
	}

	private static void printTrace(List<EventNode> trPrime) {
		for (EventNode node : trPrime) {
			if (node instanceof RdWrNode && ((RdWrNode) node).isRead()) {
				RdWrNode rnode = (RdWrNode) node;
				Util.log(node + (rnode.hasLastWriter() ? " lastwr: " + rnode.lastWriter() : " no lastwr"));
			} else {
				Util.log(node);
			}
		}
	}

	public static boolean crazyNewEdges(RdWrNode firstNode, RdWrNode secondNode, File commandDir) throws ReorderingTimeout {
		ReorderingTimeout.reorderStarted();
		LinkedList<Edge> initialEdges = new LinkedList<Edge>();
		LinkedList<Edge> initialEdgesToRemove = new LinkedList<Edge>(); // We don't add or remove initial edges that already exist
		LinkedList<Edge> additionalBackEdges = new LinkedList<Edge>();
		LinkedList<Edge> additionalForwardEdges = new LinkedList<Edge>();
		LinkedList<Edge> additionalConflictingEdges = new LinkedList<Edge>();
		HashMap<ShadowVar, HashMap<Integer/*thread id*/, RdWrNode>> thrToFirstWrPerVar = new HashMap<ShadowVar, HashMap<Integer, RdWrNode>>();
		
		// TODO: here are some sanity checks that should probably be removed later
		if (VERBOSE_GRAPH) {
			if (RR.wdcRemoveRaceEdge.get()) {
				Util.println("Validating no edge between first and second node");
				Assert.assertTrue(!edgeExists(firstNode, secondNode));
				Util.println("Validating no edge between second and first node");
				Assert.assertTrue(!edgeExists(secondNode, firstNode));
				Util.println("Validating path first to second node");
				Assert.assertTrue(!bfsTraversal(firstNode, secondNode, null, Long.MIN_VALUE, Long.MAX_VALUE));
				Util.println("Validating path second to first node");
				Assert.assertTrue(!bfsTraversal(secondNode, firstNode, null, Long.MIN_VALUE, Long.MAX_VALUE));
			}
			
			int black;// = prepUniqueTraversal(2);
			int gray;// = black - 1;
			//Util.println("Cycle detection forward from start");
			//Assert.assertTrue(!simplerIterativeDetectCycle(threadToFirstEventMap.get(0), true, gray, black, Long.MIN_VALUE, Long.MAX_VALUE));
			
			black = prepUniqueTraversal(2);
			gray = black - 1;
			Util.println("Cycle detection backward from first node");
			Assert.assertTrue(!simplerIterativeDetectCycle(firstNode, false, gray, black, Long.MIN_VALUE, Long.MAX_VALUE));
			
			black = prepUniqueTraversal(2);
			gray = black - 1;
			Util.println("Cycle detection backward from second node");
			Assert.assertTrue(!simplerIterativeDetectCycle(secondNode, false, gray, black, Long.MIN_VALUE, Long.MAX_VALUE));
		}
		
		// The window of events we need to be concerned with. It can grow as we add back edges.
//		windowMin = firstNode.eventNumber;//.lastEventNumber; // everything WDC-after the first node must be totally ordered after the first node's access(es)
//		windowMax = secondNode.eventNumber; // everything WDC-before the second node must be totally ordered before the second node's access(es)
		long[] windowRange = {firstNode.eventNumber /*windowMin*/, secondNode.eventNumber /*windowMax*/};


		long start = System.currentTimeMillis();
		reordering.start();
		Tuple2<Integer, Set<RdWrNode>> t = addConstraints(firstNode, secondNode, windowRange, initialEdges, initialEdgesToRemove, additionalBackEdges, additionalForwardEdges, additionalConflictingEdges);
		int iterations = t.first();
		boolean failed;
		Set<RdWrNode> coreReads = t.second();
		failed = coreReads == null;

		if (!failed) failed = detectCycle(firstNode, secondNode, windowRange, iterations);

		List<EventNode> trPrime = null;
		if (!failed) {
			trPrime = constructReorderedTrace(firstNode, secondNode, coreReads, commandDir, windowRange);
			failed = trPrime == null || trPrime.isEmpty();
		}
		
		// Finally remove all of the added edges
		for (Edge e : initialEdgesToRemove) {
			fastRemoveEdge(e.source, e.sink);
		}
		for (Edge e : additionalBackEdges) {
			fastRemoveEdge(e.source, e.sink);
		}
		for (Edge e : additionalForwardEdges) {
			fastRemoveEdge(e.source, e.sink);
		}
		for (Edge e : additionalConflictingEdges) {
			fastRemoveEdge(e.source, e.sink);
		}
		reordering.stop();
		Util.printf("Race%s verified, distance %s events, %s events in reordered trace, in %s ms",
				trPrime == null ? " NOT" : "",
				countEventsInWindow(getFirstEvent(firstNode), firstNode.eventNumber, secondNode.eventNumber),
				countBackwardsReachable(secondNode),
				System.currentTimeMillis() - start
		);
		return failed;
	}
	
	static Map<ShadowID, Map<Integer, RdWrNode>> buildR (EventNode firstNode, EventNode secondNode, HashSet<EventNode> missingEvents, Set<RdWrNode> coreReads, long[] windowRange) throws ReorderingTimeout {
        Vector<EventNode> start = new Vector<>();
        start.add(firstNode);
        start.add(secondNode);
        start.addAll(missingEvents);
        return findFirstWriteOfVarsPerThread(start, coreReads, windowRange);
	}
	
	static Vector<EventNode> backReorderTrace(RdWrNode firstNode, RdWrNode secondNode, Vector<EventNode> trPrime, int white, int reorderingAttempt, HashSet<EventNode> missingEvents, Map<ShadowID, Map<Integer/*thread id*/, RdWrNode>> thrToFirstWrPerVar, Set<RdWrNode> coreReads, long[] windowRange) throws ReorderingTimeout {
		int black = prepUniqueTraversal();
		HashMap<Integer, EventNode> traverse = new HashMap<>();
		for (EventNode missingRel : missingEvents) {
			if (!traverse.containsKey(missingRel.getThreadId()) || traverse.get(missingRel.getThreadId()).eventNumber < missingRel.eventNumber) {
				traverse.put(missingRel.getThreadId(), missingRel);
			}
		}
		trPrime.add(secondNode);
		secondNode.myLatestTraversal = (short) black;
		trPrime.add(firstNode);
		firstNode.myLatestTraversal = (short) black;
		
		for (EventNode firstSource : firstNode.sourceOrSources) {
			if (firstSource.myLatestTraversal >= white && firstSource.myLatestTraversal != black) {
				if (!traverse.containsKey(firstSource.getThreadId()) || traverse.get(firstSource.getThreadId()).eventNumber < firstSource.eventNumber) {
					traverse.put(firstSource.getThreadId(), firstSource);
				}
			}
		}
		for (EventNode secondSource : secondNode.sourceOrSources) {
			if (secondSource.myLatestTraversal >= white && secondSource.myLatestTraversal != black) {
				if (!traverse.containsKey(secondSource.getThreadId()) || traverse.get(secondSource.getThreadId()).eventNumber < secondSource.eventNumber) {
					traverse.put(secondSource.getThreadId(), secondSource);
				}
			}
		}
		//openReadsMap keeps track of all reads per thread whos latest write has not been added yet.
		//Assuming the latest write is constrained to execute before the read, then no other write
		//to the same shadowVar can be added to the trPrime until the latest write is added
		HashMap<ShadowID, Vector<RdWrNode>> openReadsMap = new HashMap<>();


		// Commenting this out, because if the second node is a read, then the first node must be a write, and the second node must have a last writer (which is the first node).
		// As a result, we will store the second node as an open read then immediately remove it since it is closed by the first node.
		// The same is not necessarily true if the first node is a read, so keeping that part.
//		if (secondNode.isRead() && secondNode.hasLastWriter()) {
//			// secondNode must have a latest write or is a write. so no need to account for dummy node
//			RdWrNode lastWrite = secondNode.lastWriter();
//			collectOpenRead(secondNode, openReadsMap);
//		}
//		if (firstNode.isWrite()) {
//			// If second node was a read (
//			if (openReadsMap.containsKey(firstNode.var)) {
//				openReadsMap.get(secondNode.var).removeIf(read -> read.lastWriter().equals(firstNode));
//			}
//		} else
		if (firstNode.isRead() && firstNode.hasLastWriter()) {
			collectOpenRead(firstNode, openReadsMap, coreReads, windowRange);
		}
		
		//heldLocks keeps track of the inner most active critical section per thread
		//Each acquire EventNode of a critical sections points to the acquire surrounding it, if any
		//Traversal through these acquires will determine if a lock is currently held by a thread
		HashSet<AcqRelNode> heldLocks = new HashSet<>();
		HashSet<ShadowLock> onceHeldLocks = new HashSet<>();
		AcqRelNode surroundingCS = firstNode.inCS;
		while (surroundingCS != null) {
			heldLocks.add(surroundingCS);
			surroundingCS = surroundingCS.inCS;
		}
		surroundingCS = secondNode.inCS;
		while (surroundingCS != null) {
			heldLocks.add(surroundingCS);
			surroundingCS = surroundingCS.inCS;
		}
		EventNode latestCheck = null;
		HashSet<Integer> attemptedEvents = new HashSet<>(); // Keys for events that we attempted to add to trace but failed
		while (!traverse.isEmpty()) {
			ReorderingTimeout.checkTimeout();

			EventNode e;
			if (reorderingAttempt == 0
					// Non-latest-first reordering must use latest-first outside the window due to LW constraint optimizations
					|| (latestCheck != null && latestCheck.eventNumber < windowRange[0]))
				e = reorderLatestFirst(traverse, latestCheck);
			else if (reorderingAttempt == 1) e = reorderEarliestFirst(traverse, latestCheck, attemptedEvents);
			else e = reorderRandom(traverse, attemptedEvents);

			if (e == null) {
				if (PRINT_FAILED_TRACES) backReorderStuck(trPrime, white, black, traverse, heldLocks, latestCheck, attemptedEvents);
				return new Vector<>();
			}

			if (WINDOW_ONLY && latestCheck != null && latestCheck.eventNumber < windowRange[0]) {
				// Out of the window, we can stop now.
				break;
			}

			// Check for missing release events
			if (e.inCS != null) {
				AcqRelNode checkMissingRel = e.inCS.getOtherCriticalSectionNode();
				if (e instanceof AcqRelNode && !((AcqRelNode)e).isAcquire() && ((AcqRelNode)e).getOtherCriticalSectionNode().inCS != null) {
					checkMissingRel = ((AcqRelNode)e).getOtherCriticalSectionNode().inCS.getOtherCriticalSectionNode();
				}
				if (onceHeldLocks.contains(checkMissingRel.shadowLock) && checkMissingRel.myLatestTraversal < white /*missing release is not in R*/) {
					trPrime.clear();
					trPrime.add(checkMissingRel);
					return trPrime;
				}
			}

			// Commenting this out, already handled in buildR
//			// Check for missing last writers for CORE reads
//			if (e instanceof RdWrNode) {
//				RdWrNode ev = (RdWrNode) e;
//				// The read that we are trying to add
//				if (ev.isRead()
//						// has a last writer that is not backwards reachable from anything
//						&& ev.hasLastWriter() && ev.lastWriter().myLatestTraversal < white
//						// and that last writer is not PO ordered after the first event (if it was PO ordered before it, it would be backward reachable. Anything in the same thread as first event and not backwards reachable must be after the first event. We can't add these, because the reordering can't contain any events that must be after either racing event. Sorry for the long line.)
//						&& ev.lastWriter().threadID != firstNode.threadID
//						// and it is a CORE read, which means there is no reordering where its last writer is not included
//						//&& coreReads.contains(ev) // coreReads only contains events within the window, but this can happen to earlier CORE events, seen it happen in tomcat
//						) {
//					// Then, restart reordering with the last writer included.
//					trPrime.clear();
//					trPrime.add(ev.lastWriter());
//					return trPrime;
//				}
//			}

			
			// Add event e to the reordered trace tr' if it satisfies all checks
			if (checkConstraints(white, black, e) && checkLS(heldLocks, e) && checkLW(thrToFirstWrPerVar, coreReads, openReadsMap, e, windowRange, black)) {
				if (DONT_BUILD_TRACE) trPrime.add(e);
				e.myLatestTraversal = (short) black;
				attemptedEvents = new HashSet<>();

				if (e instanceof RdWrNode) {
					RdWrNode access = (RdWrNode) e;
					if (access.isWrite()) {
						Vector<RdWrNode> openReads = openReadsMap.get(access.var);
						if (openReads != null && !openReads.isEmpty()) {
							// There may be multiple open reads to close
							openReads.removeIf(read -> read.hasLastWriter() && read.lastWriter().equals(access));
						}
					} else if (/*access is read && */ access.hasLastWriter()) {
						if (coreReads.contains(access) || (!WDCTool.graphHasBranches && access.eventNumber >= windowRange[0])) {
							Vector<RdWrNode> openReads = openReadsMap.computeIfAbsent(access.var, (var) -> new Vector<>());
							openReads.add(access);
						}
					}
				}
				if (e instanceof AcqRelNode && ((AcqRelNode) e).isAcquire()) {
					onceHeldLocks.add(((AcqRelNode)e).shadowLock);
					heldLocks.remove(e);
				} else {
					surroundingCS = e.inCS;
					while (surroundingCS != null) {
						if (!ongoingCriticalSection(heldLocks, surroundingCS.shadowLock)) {
							heldLocks.add(surroundingCS);
						}
						surroundingCS = surroundingCS.inCS;
					}
				}
				latestCheck = null;
				traverse.remove(e.getThreadId());
				for (EventNode eSource : e.sourceOrSources) {
					if (eSource.myLatestTraversal >= white && eSource.myLatestTraversal != black) {
						if (!traverse.containsKey(eSource.getThreadId()) || traverse.get(eSource.getThreadId()).eventNumber < eSource.eventNumber) {
							traverse.put(eSource.getThreadId(), eSource);
						}
					}
				}
				if (e instanceof RdWrNode) {
					RdWrNode e_ = (RdWrNode) e;
					// We don't have LW edges for reads before the window, so act like they exist
					if (e_.isRead() && e_.hasLastWriter() && e_.eventNumber < windowRange[0]) {
						RdWrNode elw = e_.lastWriter();
						if (!traverse.containsKey(elw.getThreadId()) || traverse.get(elw.getThreadId()).eventNumber < elw.eventNumber) {
							traverse.put(elw.getThreadId(), elw);
						}
					}
				}
			} else {
				latestCheck = e;
			}
		}
		return trPrime;
	}

	private static boolean checkConstraints(int white, int black, EventNode e) {
		for (EventNode ePrime : e.sinkOrSinks) {
//			if (VERBOSE_GRAPH) {
//				if (e instanceof RdWrNode && ePrime instanceOf RdWrNode && ((RdWrNode)ePrime).hasLastWriter() && e.equals(((RdWrNode)ePrime).lastWriter())) {
//					Assert.assertTrue(ePrime.myLatestTraversal >= white);
//				}
//			}
			if (ePrime.myLatestTraversal >= white && ePrime.myLatestTraversal != black) {
				return false;
			}
		}
		return true;
	}

	private static boolean checkLS(HashSet<AcqRelNode> heldLocks, EventNode e) {
		AcqRelNode surroundingCS;
		surroundingCS = e.inCS;
		while (surroundingCS != null) {
			if (!heldLocks.contains(surroundingCS) && ongoingCriticalSection(heldLocks, surroundingCS.shadowLock)) {
				return false;
			}
			if (surroundingCS.inCS != null && VERBOSE_GRAPH) Assert.assertTrue(surroundingCS.shadowLock != surroundingCS.inCS.shadowLock);
			surroundingCS = surroundingCS.inCS;
		}
		if (e instanceof AcqRelNode) {
			AcqRelNode eAcq = (AcqRelNode) e;
			if (eAcq.isAcquire() && !heldLocks.contains(eAcq) && ongoingCriticalSection(heldLocks, eAcq.shadowLock)) {
				return false;
			}
		}
		return true;
	}

	private static boolean checkLW(Map<ShadowID, Map<Integer, RdWrNode>> thrToFirstWrPerVar, Set<RdWrNode> coreReads, HashMap<ShadowID, Vector<RdWrNode>> openReadsMap, EventNode event, long[] windowRange, int black) {
		if (! (event instanceof  RdWrNode)) return true; // Anything that is not a read or write can't violate LW
		RdWrNode access = (RdWrNode) event;

		if (access.isWrite()) {
			Vector<RdWrNode> openReads = openReadsMap.get(access.var);
			if (openReads == null || openReads.isEmpty()) {
				return true; // There are no open reads, adding this write can't violate LW
			}

			for (RdWrNode read : openReads) {
				if (VERBOSE_GRAPH) {
					Assert.assertTrue(read.hasLastWriter(), "If a conflicting write exists, we can't have an open read already in trPrime");
					Assert.assertTrue(access.sameVar(read), "A read was placed in the wrong key of openReadsMap");
				}
				// If there is an open CORE read and this is not its last writer, adding this would change reads last writer
				if (!access.equals(read.lastWriter())) {
					if (VERBOSE_GRAPH) Assert.assertTrue(coreReads.contains(read) || (!WDCTool.graphHasBranches && access.eventNumber >= windowRange[0]), "Non-CORE read in openReadsMap");
					return false;
				}
			}
		} else { // access is read
			if (! (coreReads.contains(access) || (!WDCTool.graphHasBranches && access.eventNumber >= windowRange[0]))) return true; // A non-CORE read can't violate LW

			// If a CORE read has no last writer, it must be added after all conflicting writes have been added.
			if (access.hasNoLastWriter()) {
				Map<Integer, RdWrNode> thrPerVar = thrToFirstWrPerVar.get(access.var);
				if (thrPerVar != null) {
					for (RdWrNode firstWrite : thrPerVar.values()) {
						if (firstWrite.myLatestTraversal != black) {
							return false;
						}
					}
				}
			} else { // access does have a last writer
				EventNode lastWrite = access.lastWriter();
				// The last writer of the current read can not be in trPrime, otherwise we somehow missed a write-read edge
				if (VERBOSE_GRAPH) Assert.assertFalse(lastWrite.myLatestTraversal == black);

				Vector<RdWrNode> openReads = openReadsMap.get(access.var);
				if (openReads == null || openReads.isEmpty()) {
					return true; // There are no open reads, adding this read can't violate LW
				}

				// If we have an open read and we are trying to add a new read to trPrime (and they both access the same variable),
				// both reads must have the same last writer. Otherwise, one of the reads will have a wrong last writer.
				for (RdWrNode read : openReads) {
					if (VERBOSE_GRAPH) {
						Assert.assertFalse(read.lastWriter().myLatestTraversal == black, "Open read already had its last writer added to trPrime");
						Assert.assertTrue(access.sameVar(read), "A read was placed in the wrong key of openReadsMap");
					}
					if (!access.lastWriter().equals(read.lastWriter())) {
						return false;
					}
				}
			}
		}
		return true;
	}

	private static void collectOpenRead(RdWrNode read, HashMap<ShadowID, Vector<RdWrNode>> openReadsMap, Set<RdWrNode> coreReads, long[] windowRange) {
		if (coreReads.contains(read) || (!WDCTool.graphHasBranches && read.eventNumber >= windowRange[0])) {
			Vector<RdWrNode> readsPerThread = openReadsMap.computeIfAbsent(read.var, (var) -> new Vector<>());
			readsPerThread.add(read);
		}
	}

	private static EventNode reorderRandom(HashMap<Integer, EventNode> traverse, HashSet<Integer> attemptedEvents) {
		// Find the keys for all events we haven't attempted yet
		Set<Integer> events = new HashSet<>(traverse.keySet());
		events.removeAll(attemptedEvents);
		// Randomly pick an event
		Vector<Integer> possibleTids = new Vector<Integer>(events);
		if (possibleTids.size() != 0) {
			Integer tid = possibleTids.elementAt(new Random().nextInt(possibleTids.size()));
			attemptedEvents.add(tid);
            return traverse.get(tid);
        }
		return null;
	}

	// Could refactor reorderRandom and reorderLatestFirst into a single method
	private static EventNode reorderLatestFirst(HashMap<Integer, EventNode> traverse, EventNode latestCheck) {
		return traverse.entrySet().stream()
				// If there was a last attempt, we must now attempt an earlier event than that one
				.filter(x -> latestCheck == null || x.getValue().eventNumber <= latestCheck.eventNumber && !x.getValue().equals(latestCheck))
				// Find the "maximum" event in regards to their event numbers
				.max(Comparator.comparingInt(x -> x.getValue().eventNumber))
				// Return any valid entry, otherwise return null
				.map(Entry::getValue).orElse(null);
	}
	
	private static EventNode reorderEarliestFirst(HashMap<Integer, EventNode> traverse, EventNode latestCheck, HashSet<Integer> attemptedEvents) {
		// Find the keys for all events we haven't attempted yet
		Set<Integer> events = new HashSet<>(traverse.keySet());
		events.removeAll(attemptedEvents);
		EventNode e = null;
		int eTid = -1;
		for (int tid : events) {
            if (e == null) {
                if (latestCheck == null) {
                    e = traverse.get(tid);
                    eTid = tid;
                } else if (traverse.get(tid).eventNumber >= latestCheck.eventNumber) {
                    e = traverse.get(tid);
                    eTid = tid;
                }
            } else if (latestCheck == null) {
                if (e.eventNumber >= traverse.get(tid).eventNumber) {
                    e = traverse.get(tid);
                    eTid = tid;
                }
            } else if (e.eventNumber >= traverse.get(tid).eventNumber && traverse.get(tid).eventNumber >= latestCheck.eventNumber) {
                e = traverse.get(tid);
                eTid = tid;
            }
        }
		if (e != null) attemptedEvents.add(eTid);		
		return e;
	}

	private static boolean backReorderStuck(List<EventNode> trPrime, int white, int black, HashMap<Integer, EventNode> traverse, HashSet<AcqRelNode> heldLocks, EventNode latestCheck, HashSet<Integer> attemptedEvents) {
		Util.log("black: " + black + " , white: " + white);
		Util.log("trPrime so far: ");
		for (EventNode node : trPrime) {
            Util.log(node + " | surroundingCS: " + node.inCS + " | myLatestTraversal: " + node.myLatestTraversal);
        }
		Util.log("BackReorder Set Getting Stuck: latestCheck: " + latestCheck + " | eventNumber: " + latestCheck.eventNumber + " | surroundingCS: " + latestCheck.inCS);
		Util.log("attempted events at this point:");
		for (Integer tid : attemptedEvents) {
			EventNode e = traverse.get(tid);
			Util.log("attempted " + e + " | surroundingCS: " + e.inCS + " | myLatestTraversal: " + e.myLatestTraversal);
		}
		for (int eTid : traverse.keySet()) {
            boolean outgoing_edge_check = false;
            EventNode eCheck = traverse.get(eTid);
            for (EventNode ePrime : eCheck.sinkOrSinks) {
                if (ePrime.myLatestTraversal >= white && ePrime.myLatestTraversal != black) {
                    outgoing_edge_check = true; //true is bad
                }
            }
            Util.log(traverse.get(eTid) + " | surroundingCS: " + traverse.get(eTid).inCS + " | myLatestTraversal: " + traverse.get(eTid).myLatestTraversal + " | could not be added due to sink node: " + outgoing_edge_check);
            if (outgoing_edge_check) {
                for (EventNode ePrime : eCheck.sinkOrSinks) {
                    if (ePrime.myLatestTraversal >= white) {
                        Util.log("--sink--> " + ePrime + " | surroundingCS: " + ePrime.inCS + " | myLatestTraversal: " + ePrime.myLatestTraversal);
                        for (EventNode ePrimePrime : ePrime.sinkOrSinks) {
                            if (ePrimePrime.myLatestTraversal >= white) {
                                Util.log("--sink--of sink--> " + ePrimePrime + " | surroundingCS: " + ePrimePrime.inCS + " | myLatestTraversal: " + ePrimePrime.myLatestTraversal);
                            }
                        }
                    }
                }
            }
        }
		Util.log("heldLocks:");
		for (AcqRelNode eAcq : heldLocks) {
            Util.log(eAcq);
        }
		return false;
	}

	static boolean ongoingCriticalSection(HashSet<AcqRelNode> heldLocks, ShadowLock e) {
		for (AcqRelNode lock : heldLocks) {
			if (e == lock.shadowLock) {
				return true;
			}
		}
		return false;
	}
	
	public static boolean addRuleB(RdWrNode firstNode, RdWrNode secondNode, boolean traverseFromAllEdges, boolean precision, File commandDir) {
		LinkedList<Edge> initialEdges = new LinkedList<Edge>();
		LinkedList<Edge> initialEdgesToRemove = new LinkedList<Edge>(); // We don't add or remove initial edges that already exist
		LinkedList<Edge> additionalRuleBEdges = new LinkedList<Edge>();
		
		// Create edges from one node's predecessors to the other node
		for (EventNode source : secondNode.sourceOrSources) {
			Edge backEdge = new Edge(source, firstNode);
			initialEdges.add(backEdge);
			if (!edgeExists(source, firstNode)) { // This edge might already exist
				// TODO: is it reasonable for this edge to already exist? it's happening with aggressive merging enabled
				initialEdgesToRemove.add(backEdge);
				EventNode.addEdge(source, firstNode);
			}
		}
		for (EventNode source : firstNode.sourceOrSources) {
			Edge forwardEdge = new Edge(source, secondNode);
			initialEdges.add(forwardEdge);
			if (!edgeExists(source, secondNode)) { // This edge might already exist
				initialEdgesToRemove.add(forwardEdge);
				EventNode.addEdge(source, secondNode);
			}
		}
		
		boolean addedRuleBEdge; // Rule B edge added on current iteration?
		int iteration = 0;
		// The window of events we need to be concerned with. It can grow as we add back edges.
		long windowMin = firstNode.eventNumber;//.lastEventNumber; // everything WDC-after the first node must be totally ordered after the first node's access(es)
		long windowMax = secondNode.eventNumber; // everything WDC-before the second node must be totally ordered before the second node's access(es)
		
		do {
			
			addedRuleBEdge = false;

			++iteration;
			Util.println("Iteration = " + iteration);

			LinkedList<Edge> separateInitNodes = new LinkedList<Edge>();
			for (Edge initialEdge : initialEdges) {
				separateInitNodes.add(initialEdge);
			}
			if (traverseFromAllEdges) {
				for (Edge RuleBEdge : additionalRuleBEdges) {
					separateInitNodes.add(RuleBEdge);
				}
			}
			for (Edge edge : separateInitNodes) {
				// First do a reverse traversal from the second access and possibly from other edge sources
				HashMap<ShadowLock,HashMap<ShadowThread, AcqRelNode>> reachableAcqNodes = new HashMap<ShadowLock,HashMap<ShadowThread, AcqRelNode>>();
				ArrayDeque<EventNode> grayNodes = new ArrayDeque<EventNode>();
				int traversal = prepUniqueTraversal();
				grayNodes.add(edge.source);
				while (!grayNodes.isEmpty()) {
					EventNode node = grayNodes.removeFirst();
					if (node.myLatestTraversal != traversal) {
						// We don't care about nodes outside the window
						if (node.eventNumber >= windowMin) {
							// If this is an acquire, let's record it,
							// to figure out if it can reach an earlier (in total order) release of the same lock
							if (node instanceof AcqRelNode) {
								AcqRelNode acqRelNode = (AcqRelNode)node;
								if (acqRelNode.isAcquire()) {
									HashMap<ShadowThread, AcqRelNode> acqNodesForLock = reachableAcqNodes.get(acqRelNode.shadowLock);
									if (acqNodesForLock == null) {
										acqNodesForLock = new HashMap<ShadowThread, AcqRelNode>();
										reachableAcqNodes.put(acqRelNode.shadowLock, acqNodesForLock);
									}
									// We want the acq node that's latest in total order,
									// since earlier acq nodes on the same thread will be WDC ordered by definition.
									AcqRelNode currentAcqNodeForThread = acqNodesForLock.get(acqRelNode.getShadowThread());
									if (currentAcqNodeForThread == null || acqRelNode.eventNumber > currentAcqNodeForThread.eventNumber) {
										acqNodesForLock.put(acqRelNode.getShadowThread(), acqRelNode);
									}
								}
							} else if (node.inCS != null) {
								AcqRelNode surroundingAcq = node.inCS;
								while (surroundingAcq != null) {
									HashMap<ShadowThread, AcqRelNode> acqNodesForLock = reachableAcqNodes.get(surroundingAcq.shadowLock);
									if (acqNodesForLock == null) {
										acqNodesForLock = new HashMap<ShadowThread, AcqRelNode>();
										reachableAcqNodes.put(surroundingAcq.shadowLock, acqNodesForLock);
									}
									// We want the acq node that's latest in total order,
									// since earlier acq nodes on the same thread will be WDC ordered by definition.
									AcqRelNode currentAcqNodeForThread = acqNodesForLock.get(surroundingAcq.getShadowThread());
									if (currentAcqNodeForThread == null || surroundingAcq.eventNumber > currentAcqNodeForThread.eventNumber) {
										acqNodesForLock.put(surroundingAcq.getShadowThread(), surroundingAcq);
									}
									surroundingAcq = surroundingAcq.inCS;
								}
							}
							node.myLatestTraversal = (short) traversal;
							for (EventNode source : node.sourceOrSources) {
								grayNodes.add(source);
							}
						}
					}
				}
				
				// Second do a forward traversal from the first access and possibly from other edge sinks
				HashMap<ShadowLock,HashMap<ShadowThread, AcqRelNode>> reachableRelNodes = new HashMap<ShadowLock,HashMap<ShadowThread, AcqRelNode>>();
				traversal = prepUniqueTraversal();
				grayNodes.add(edge.sink);
				while (!grayNodes.isEmpty()) {
					EventNode node = grayNodes.removeFirst();
					if (node.myLatestTraversal != traversal) {
						// We don't care about nodes outside the window
						if (node.eventNumber <= windowMax) {
							// If this is a release, let's record it,
							// to figure out if it it's reached by a later (in total order) acquire of the same lock
							if (node instanceof AcqRelNode) {
								AcqRelNode acqRelNode = (AcqRelNode)node;
								if (!acqRelNode.isAcquire()) {
									HashMap<ShadowThread, AcqRelNode> relNodesForLock = reachableRelNodes.get(acqRelNode.shadowLock);
									if (relNodesForLock == null) {
										relNodesForLock = new HashMap<ShadowThread, AcqRelNode>();
										reachableRelNodes.put(acqRelNode.shadowLock, relNodesForLock);
									}
									// We want the rel node that's earliest in total order,
									// since later rel nodes on the same thread will be WDC ordered by definition.
									AcqRelNode currentRelNodeForThread = relNodesForLock.get(acqRelNode.getShadowThread());
									if (currentRelNodeForThread == null || acqRelNode.eventNumber < currentRelNodeForThread.eventNumber) {
										relNodesForLock.put(acqRelNode.getShadowThread(), acqRelNode);
									}
								}
							} else if (node.inCS != null) {
								AcqRelNode surroundingAcq = node.inCS;
								AcqRelNode surroundingRel = null;
								while (surroundingAcq != null) {
									surroundingRel = surroundingAcq.otherCriticalSectionNode;
									HashMap<ShadowThread, AcqRelNode> relNodesForLock = reachableRelNodes.get(surroundingRel.shadowLock);
									if (relNodesForLock == null) {
										relNodesForLock = new HashMap<ShadowThread, AcqRelNode>();
										reachableRelNodes.put(surroundingRel.shadowLock, relNodesForLock);
									}
									// We want the rel node that's earliest in total order,
									// since later rel nodes on the same thread will be WDC ordered by definition.
									AcqRelNode currentRelNodeForThread = relNodesForLock.get(surroundingRel.getShadowThread());
									if (currentRelNodeForThread == null || surroundingRel.eventNumber < currentRelNodeForThread.eventNumber) {
										relNodesForLock.put(surroundingRel.getShadowThread(), surroundingRel);
									}
									surroundingAcq = surroundingAcq.inCS;
								}
							}
							node.myLatestTraversal = (short) traversal;
							for (EventNode sink : node.sinkOrSinks) {
								grayNodes.add(sink);
							}
						}
					}
				}
	
				//TODO: This is the change to add Rule B edges 
				// Now check for edges that indicate a back edge w.r.t. total order
				for (ShadowLock shadowLock : reachableAcqNodes.keySet()) {
					HashMap<ShadowThread, AcqRelNode> acqNodesForLock = reachableAcqNodes.get(shadowLock);
					HashMap<ShadowThread, AcqRelNode> relNodesForLock = reachableRelNodes.get(shadowLock);
					if (relNodesForLock != null) {
						for (AcqRelNode acqNode : acqNodesForLock.values()) {
							for (AcqRelNode relNode : relNodesForLock.values()) {
								//Rule B Edges
								if (relNode.eventNumber > acqNode.eventNumber &&
									!relNode.otherCriticalSectionNode.equals(acqNode) && //make sure the relNode and acqNode are not the same critical section
									!containsNode(acqNode.otherCriticalSectionNode.sinkOrSinks, relNode.otherCriticalSectionNode)) { //don't add a forward edge if one has already been added
									long tempWindowMin = Math.min(windowMin, acqNode.otherCriticalSectionNode.eventNumber);
									tempWindowMin = Math.min(tempWindowMin, acqNode.eventNumber);
									long tempWindowMax = Math.max(windowMax, relNode.otherCriticalSectionNode.eventNumber);
									tempWindowMax = Math.max(tempWindowMax, relNode.eventNumber);
									// Forward edge found, but the acquire of both critical sections of the forwardedge need to reach either conflicting access
									if (!precision || (bfsTraversal(relNode.otherCriticalSectionNode, firstNode, secondNode, tempWindowMin, tempWindowMax) && bfsTraversal(acqNode, firstNode, secondNode, tempWindowMin, tempWindowMax))) {
										if (precision && VERBOSE_GRAPH) Assert.assertTrue(bfsTraversal(acqNode, relNode, null, tempWindowMin, tempWindowMax));//, "tempWindowMin: " + tempWindowMin + " | tempWindowMax: " + tempWindowMax + " | acqNode: " + acqNode.getNodeLabel() + ", eventNumber: " + acqNode.eventNumber + " | relNode: " + relNode.getNodeLabel() + ", eventNumber: " + relNode.eventNumber); // Assert a path actually exists from acqNode -> relNode
										// Add forward edge and signal we should repeat this whole process
										Util.println("Found rel->rel that needs Rule B edge: " + shadowLock + ", " + acqNode.otherCriticalSectionNode + "->" + relNode);
										EventNode.addEdge(acqNode.otherCriticalSectionNode, relNode);
										additionalRuleBEdges.add(new Edge(acqNode.otherCriticalSectionNode, relNode));
										//Window Size should not have to be modified.
										//Since release nodes are found traversing forward and acquire nodes are found traversing backward
										//and release execution later than acquire means an added forward edge from release's acquire to acquire's release will already be within the window
										windowMin = Math.min(windowMin, acqNode.otherCriticalSectionNode.eventNumber);
										windowMax = Math.max(windowMax, relNode.otherCriticalSectionNode.eventNumber);
										windowMin = Math.min(windowMin, acqNode.eventNumber);
										windowMax = Math.max(windowMax, relNode.eventNumber);
										addedRuleBEdge = true;
									}
								}
							}
						}
					}
				}
			}
		} while (addedRuleBEdge);
		
		//Check if there is a forward DC path between the two conflicting accesses.
		boolean DCOrdered = bfsTraversal(firstNode, secondNode, null, Long.MIN_VALUE, Long.MAX_VALUE);
		
		// Finally remove all of the added edges
		for (Edge e : initialEdgesToRemove) {
			EventNode.removeEdge(e.source, e.sink);
		}
		for (Edge e : additionalRuleBEdges) {
			EventNode.removeEdge(e.source, e.sink);
		}

		return DCOrdered;
	}
	
	// TODO: Refactor next two methods into a single, more general method. There's a lot of overlap between them.
	// I (Jake) have refactored bfsValidatePath, bfsValidateBackedge, and bfsReachability into the general bfsTraversal method,
	// but there are more traversal methods this might be able to be applied to.
	// Other issues:
	// I (Mike) am not sure if both gray and black are actually needed here, but I've included them both as part of the traversal fix.
	// I don't think it makes logical sense to pass in a grayNodes object. A new ArrayDeque should be created instead.
	// Also: I haven't looked at traversals in methods beyond the following two. There might be opportunities for avoiding traversal bugs
	// (e.g., avoiding adding the same node twice to grayNodes) and/or for refactoring to use the general method that replaces the two methods below.
	
	public static boolean bfsTraversal(EventNode startingNode, EventNode firstNode, EventNode secondNode, long windowMin, long windowMax) {
		ArrayDeque<EventNode> WDCBGraph = new ArrayDeque<>();
		int black = EventNode.prepUniqueTraversal(2);
		int gray = black - 1;
		
		WDCBGraph.add(startingNode);
		
		startingNode.myLatestTraversal = (short) gray;
		
		while (!WDCBGraph.isEmpty()) {
			EventNode node = WDCBGraph.pop();
			if (node.myLatestTraversal != black) {
				if (node.eventNumber >= windowMin && node.eventNumber <= windowMax) {
					if ((firstNode != null && containsNode(node.sinkOrSinks, firstNode)) || (secondNode != null && containsNode(node.sinkOrSinks, secondNode))) {
						return true;
					}
					for (EventNode sinkOrSource : node.sinkOrSinks) {
						if (sinkOrSource.myLatestTraversal < gray) {
							WDCBGraph.add(sinkOrSource);
							sinkOrSource.myLatestTraversal = (short) gray;
						}
					}
				}
				node.myLatestTraversal = (short) black;
				if (VERBOSE_GRAPH) Assert.assertTrue(node.eventNumber > -2);
			}
		}
		return false;
	}


    static Map<ShadowID, Map<Integer/*thread id*/, RdWrNode>> findFirstWriteOfVarsPerThread(Collection<EventNode> start, Set<RdWrNode> coreReads, long[] windowRange) throws ReorderingTimeout {
		int visited = prepUniqueTraversal();
    	Vector<EventNode> unvisited = new Vector<>(start);
		Map<ShadowID, Map<Integer, RdWrNode>> writeOfVarPerThread = new HashMap<>();
		while (!unvisited.isEmpty()) {
			ReorderingTimeout.checkTimeout();
			EventNode current = unvisited.remove(unvisited.size() - 1);
			if (current.myLatestTraversal != visited && (!WINDOW_ONLY || current.eventNumber > windowRange[0])) {
				current.myLatestTraversal = (short) visited;
				Util.addAll(unvisited, current.sourceOrSources);
				if (current instanceof RdWrNode) {
					RdWrNode currentAcc =(RdWrNode) current;
					if (currentAcc.isWrite()) {
						Map<Integer, RdWrNode> firstWritePerThread = writeOfVarPerThread.computeIfAbsent(currentAcc.var, v -> new HashMap<>());
						firstWritePerThread.merge(currentAcc.getThreadId(), currentAcc, (l, r) -> (l.eventNumber < r.eventNumber) ? l : r);
					} else if (hasWBR) { // currentAcc is read
						if (currentAcc.hasLastWriter() &&
								(coreReads.contains(currentAcc) || currentAcc.eventNumber < windowRange[0]) || !WDCTool.graphHasBranches) {
							// The last writer of the read may be unreachable by just using WBR edges.
							// We want the last writer in R, but only if the read is CORE.
							// Or if the read is before the window, we can safely grab the last writer
							// since it should be possible to just keep these in observed order during reordering.
							unvisited.add(currentAcc.lastWriter());
						}
					}
				}
			}
		}
		return writeOfVarPerThread;
	}


	/** Find all reads that are CORE events that are backwards reachable from the starting event.
	 *
	 * At the same time, we check for several things:
	 * - Are there any cycles? If there are, then there is no race.
	 * - Can we find a backwards path from the second event to the first one, (where path may include LW edges)?
	 *   If so, there is no race.
	 *
	 * @param first First event in race pair.
	 * @param second Second event in race pair, the node to starting the backward traversal from.
	 * @return Set of CORE reads. null if we discover early a no-race result.
	 */
	static Set<RdWrNode> findCoreReads(EventNode first, EventNode second, long[] windowRange, Set<RdWrNode> fullCoreReads) {
		int visited = prepUniqueTraversal();
		// Events that have been discovered but not yet visited
		PriorityQueue<EventNode> unvisited = new PriorityQueue<>((left, right) -> {
			// A comparator that orders the events in reverse order based on their event numbers, allowing the
			// priority queue to be used as a max heap.
			long diff = right.eventNumber - left.eventNumber;
			if (diff < 0) {
				return -1;
			} else if (diff > 0) {
				return 1;
			}
			return 0;
		});

		// Set of CORE reads
		Set<RdWrNode> coreReads = new HashSet<>();
		// Set of CORE writes
		Set<RdWrNode> coreWrites = new HashSet<>();
		// Set of threads for which we have seen CORE events
		Set<Integer> isCoreYet = new HashSet<>();
		// Number of non-CORE reads/writes
        int nonCoreAccess = 0;
        // Set of new CORE reads
        Set<RdWrNode> newCoreReads = new HashSet<>();

		unvisited.add(second);
		unvisited.add(first); // With no graph edges, we also need to traverse backwards from the first event.
		while (!unvisited.isEmpty()) {
			EventNode current = unvisited.poll();
			if (current.myLatestTraversal != visited && current.eventNumber >= windowRange[0]/*windowMin*/ && current.eventNumber <= windowRange[1]/*windowMax*/) {
				current.myLatestTraversal = (short) visited;
				Util.addAll(unvisited, current.sourceOrSources);

				if (current instanceof BranchNode) {
					if (NO_SDG) {
						isCoreYet.add(current.getThreadId());
					} else {
						// If we do have SDG information, mark specific reads in thread as CORE
						// rather than marking everything before the branch as CORE
						for (RdWrNode access : ((BranchNode) current).getDeps()) {
							if (access == null) continue;
							coreReads.add(access);
							if (fullCoreReads.add(access)) {
								newCoreReads.add(access);
							}
							if (access.hasLastWriter()) {
								coreWrites.add(access.lastWriter());
								unvisited.add(access.lastWriter());
							}
						}
					}
				}

				if (current instanceof RdWrNode) {
					final RdWrNode access = (RdWrNode) current;
					if (!isCoreYet.contains(access.getThreadId()) && coreWrites.contains(access)) {
						// Mark that we have seen a CORE write by this thread already.
						// Reads don't matter, because only CORE writes make everything PO ordered to them CORE events
						isCoreYet.add(access.getThreadId());
					} else if (isCoreYet.contains(access.getThreadId())) {
						// Any event PO ordered before a CORE event is also a CORE event. We have seen a CORE event for
						// this thread, so this event must be a CORE event as well. We are sure that we have already
						// seen all events PO ordered after the current one, because the PriorityQueue orders events
						// by event number, largest event number first.
						if (access.isWrite()) {
						    coreWrites.add(access);
                        } else {
						    coreReads.add(access);
						    if (fullCoreReads.add(access)) {
								newCoreReads.add(access);
							}
						    if (access.hasLastWriter()) {
						        coreWrites.add(access.lastWriter());
						        unvisited.add(access.lastWriter());
                            }
                        }
					} else {
						nonCoreAccess++;
					}
				}
			}
		}
		Util.logf("Non-CORE reads and writes found: %d", nonCoreAccess);
		return newCoreReads;
	}


	// Finds event node that is PO ordered before c
	static EventNode priorPO(EventNode c) {
		for (EventNode p : c.sourceOrSources) {
			if (p.getThreadId() == c.getThreadId()) {
				return c;
			}
		}
		return null;
	}

	
	@Deprecated
	static boolean bfsValidatePath(ArrayDeque<EventNode> grayNodes, EventNode acqNode, EventNode relNode, long windowMax, long windowMin) {
		int black = EventNode.prepUniqueTraversal(2);
		int gray = black - 1;
		grayNodes.clear();
		
		grayNodes.add(acqNode);
		acqNode.myLatestTraversal = (short) gray;
		
		while (!grayNodes.isEmpty()) {
			EventNode node = grayNodes.removeFirst();
			if (node.myLatestTraversal != black) {
				if (node.eventNumber >= windowMin && node.eventNumber <= windowMax) {
					if (containsNode(node.sinkOrSinks, relNode)) {
						return true;
					}
					for (EventNode sinkNode : node.sinkOrSinks) {
						if (sinkNode.myLatestTraversal < gray) {
							grayNodes.add(sinkNode);
							sinkNode.myLatestTraversal = (short) gray;
						}
					}
				}
				node.myLatestTraversal = (short) black;
			}
		}
		return false;
	}	
	
	@Deprecated
	static boolean bfsValidateBackedge(ArrayDeque<EventNode> grayNodes, AcqRelNode ARNode, RdWrNode firstNode, RdWrNode secondNode, long windowMax, long windowMin) {
		int black = EventNode.prepUniqueTraversal(2);
		int gray = black - 1;
		grayNodes.clear();
		
		if (ARNode.isAcquire()) { //validate from the acquire of the backedge with a later event number  
			grayNodes.add(ARNode);
			ARNode.myLatestTraversal = (short) gray;
		} else { //validate from the acquire of the backedge with an earlier event number
			grayNodes.add(ARNode.otherCriticalSectionNode);
			ARNode.otherCriticalSectionNode.myLatestTraversal = (short) gray;
		}
		
		while (!grayNodes.isEmpty()) {
			EventNode node = grayNodes.removeFirst();
			if (node.myLatestTraversal != black) {
				if (node.eventNumber >= windowMin && node.eventNumber <= windowMax) {
					if (containsNode(node.sinkOrSinks, firstNode) || containsNode(node.sinkOrSinks, secondNode)) {
						return true;
					}
					for (EventNode sinkNode : node.sinkOrSinks) {
						if (sinkNode.myLatestTraversal < gray) {
							grayNodes.add(sinkNode);
							sinkNode.myLatestTraversal = (short) gray;
						}
					}
				}
				node.myLatestTraversal = (short) black;
			}
		}
		return false;
	}
	
	@Deprecated
	static void bfsReachability(EventNode startingNode, int gray, int black, long windowMin, long windowMax) {
		ArrayDeque<EventNode> WDCBGraph = new ArrayDeque<EventNode>();
		WDCBGraph.add(startingNode);
		while(!WDCBGraph.isEmpty()) {
			EventNode node = WDCBGraph.pop();
			if (node.eventNumber >= windowMin && node.eventNumber <= windowMax) {
				if (node.myLatestTraversal != black) {
					node.myLatestTraversal = (short) gray;
					for (EventNode sourceNode : node.sourceOrSources) {
						if (sourceNode.myLatestTraversal != black) {
							WDCBGraph.add(sourceNode);
						}
					}
					node.myLatestTraversal = (short) black;
				}
			}
		}
	}
	
	static boolean dfsDetectCycle(EventNode node, boolean isForward, int gray, int black, long windowMin, long windowMax) {
		if (node.eventNumber >= windowMin && node.eventNumber <= windowMax) {
			if (node.myLatestTraversal != black) {
				if (node.myLatestTraversal == gray) {
					return true;
				}
				node.myLatestTraversal = (short) gray;
				for (EventNode predOrSucc : (isForward ? node.sinkOrSinks : node.sourceOrSources)) {
					boolean cycleDetected = dfsDetectCycle(predOrSucc, isForward, gray, black, windowMin, windowMax);
					if (cycleDetected) {
						return true;
					}
				}
				node.myLatestTraversal = (short) black;
			}
		}
		return false;
	}
	
	static boolean simplerIterativeDetectCycle(EventNode node, boolean isForward, int gray, int black, long windowMin, long windowMax) {
		Stack<EventNode> nodeStack = new Stack<EventNode>();
		Stack<Iterator<EventNode>> iterStack = new Stack<Iterator<EventNode>>();
		
		start:
		while (true) {
			Iterator<EventNode> iter;
			if (node.myLatestTraversal != black) {
				if (node.myLatestTraversal == gray) {
					return true;
				}
				node.myLatestTraversal = (short) gray;
				iter = (isForward ? node.sinkOrSinks : node.sourceOrSources).iterator();
			} else {
				if (nodeStack.isEmpty()) {
					return false;
				}
				node = nodeStack.pop();
				iter = iterStack.pop();
			}
			while (iter.hasNext()) {
				EventNode predOrSucc = iter.next();
				if (predOrSucc.eventNumber >= windowMin && predOrSucc.eventNumber <= windowMax) {
					nodeStack.push(node);
					iterStack.push(iter);
					node = predOrSucc;
					continue start;
				}
			}
			node.myLatestTraversal = (short) black;
		}
	}
	
	// Used by iterative DFS-based cycle detection
	static class EventNodeDepth {
		final EventNode node;
		final int depth;
		EventNodeDepth(EventNode node, int depth) {
			this.node = node;
			this.depth = depth;
		}
	}
	
	static boolean iterativeDfsDetectCycle(EventNode node, boolean isForward, int gray, int black, long windowMin,
                                           long windowMax, Map<EventNode, List<EventNode>> cycleEdges) {

		Stack<EventNodeDepth> stack = new Stack<EventNodeDepth>();
		List<EventNodeDepth> currentTrace = new ArrayList<EventNodeDepth>();
		int currentDepth = 0;
		stack.push(new EventNodeDepth(node, 1));
		while (!stack.isEmpty()) {
			EventNodeDepth currentNodeDepth = stack.pop();
			EventNode currentNode = currentNodeDepth.node;
			
			if (currentNode.eventNumber >= windowMin && currentNode.eventNumber <= windowMax) {
				if (currentNode.myLatestTraversal != black) {
					if (currentNodeDepth.depth <= currentDepth) {
						while (currentTrace.size() > 0 && (currentTrace.get(currentTrace.size() - 1)).depth >= currentNodeDepth.depth) {
							currentTrace.get(currentTrace.size() - 1).node.myLatestTraversal = (short) black;
							currentTrace.remove(currentTrace.size() - 1);
						}
					}
					
					// TODO: This is expensive for long paths!
					for (EventNodeDepth eventNodeDepth : currentTrace) {
						if (eventNodeDepth.node == currentNode) {
							return true;
						}
					}
					currentTrace.add(currentNodeDepth);
					currentDepth = currentNodeDepth.depth;
					
					for (EventNode predOrSucc : (isForward ? currentNode.sinkOrSinks : currentNode.sourceOrSources)) {
						// TODO: Mike says: Are the the following two lines correct? I modified the functionality somewhat from the original. 
						//currentNodeDepth.depth = currentDepth + 1;
						stack.push(new EventNodeDepth(predOrSucc, currentDepth + 1));
						
						if (cycleEdges != null) {
							if (!cycleEdges.containsKey(predOrSucc)) {
								cycleEdges.put(predOrSucc, new ArrayList<EventNode>());
							}
							cycleEdges.get(predOrSucc).add(currentNode);
						}
					}
				}
			}
		}
		return false;
	}
	
	static void printReordering(List<EventNode> trPrime, RdWrNode firstNode, RdWrNode secondNode, File commandDir) {
		try {
			PrintWriter input = new PrintWriter(commandDir+"/wdc_race_" + firstNode.eventNumber + "_" + secondNode.eventNumber);
			for (EventNode trPrimeEvent : trPrime) {
				if (trPrimeEvent instanceof AcqRelNode) {
					AcqRelNode ARPrimeEvent = (AcqRelNode)trPrimeEvent;
					String ARPrimeName = Util.objectToIdentityString(ARPrimeEvent.shadowLock.getLock());
					input.println("T" + ARPrimeEvent.getThreadId() + ":" + (ARPrimeEvent.isAcquire() ? "acq(" : "rel(") + ARPrimeName + "):"
									+ ARPrimeEvent.eventNumber);
				} else if (trPrimeEvent instanceof RdWrNode) {
					RdWrNode RWPrimeEvent = (RdWrNode)trPrimeEvent;
					input.println("T" + trPrimeEvent.getThreadId() + ":" + (RWPrimeEvent.isWrite() ? "wr(" : "rd(") + RWPrimeEvent.getFieldName() + "):"
									+ RWPrimeEvent.eventNumber);
				} else {
					input.println("T" + trPrimeEvent.getThreadId() + ":" + trPrimeEvent.eventNumber);
				}
			}
			input.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	static void generateInputFileForGraphviz(Map<EventNode, List<EventNode>> edges, boolean isFirst, boolean isTraverseFromAllEdges, boolean precision){
		 
		String fileName = (isFirst ? "first" : "second") + "Cycle" + "_TA-" + isTraverseFromAllEdges + "_P-" + precision + ".txt"; 
		try {
			PrintWriter printWriter = new PrintWriter(fileName, "UTF-8");
			
			printWriter.println("digraph G");
			printWriter.println("{");
			printWriter.println("rankdir=TB");
			printWriter.println("newrank=true");
			printWriter.println("node [shape = circle];");
	
		    Map<Integer, SortedSet<EventNode>> threadIdToThreadEvents = new HashMap<>();
		    // Breadth-first search, latter nodes to earlier nodes
			PriorityQueue<EventNode> all_nodes = new PriorityQueue<>((left, right) -> right.eventNumber - left.eventNumber);
			all_nodes.addAll(edges.keySet());
		    edges.values().forEach(all_nodes::addAll);
		    int i = 0;
		    while (! all_nodes.isEmpty()) {
		    	i++;
		    	if (i > 10000) break;
		    	EventNode node = all_nodes.poll();
		    	addEventToItsThreadList(threadIdToThreadEvents, node);
		        node.sourceOrSources.forEach(all_nodes::add);
		    }

		    Iterator<Entry<Integer, SortedSet<EventNode>>> it = threadIdToThreadEvents.entrySet().iterator();
		    while (it.hasNext()) {
		    	Entry<Integer, SortedSet<EventNode>> pair = it.next();
		    	printWriter.println("subgraph Cluster_" + pair.getKey() + " {");
		    	printWriter.println("label = T" + (int)pair.getKey());
		        for (EventNode eventNode : pair.getValue()) {
		        	String nodeLabel = eventNode.getNodeLabel() + "- #" + eventNode.eventNumber;
		        	printWriter.println( "node" + eventNode.eventNumber + " [label = \"" + nodeLabel + "\"];");
		        }
		        printWriter.println("}");
		    }

			it = threadIdToThreadEvents.entrySet().iterator();
		    while (it.hasNext()) {
				Entry<Integer, SortedSet<EventNode>> pair = it.next();
		        for (EventNode from: pair.getValue()) {
					printWriter.println( "node" + from.eventNumber + " [label = \"" + from.toString() + "\"];");
		        	for (EventNode to : from.sinkOrSinks) {
						printWriter.println("\"node" + from.eventNumber + "\" -> \"node" + to.eventNumber + "\"" +
								(from.eventNumber > to.eventNumber ? " [dir=\"back\"]" : "") + ";");
						printWriter.println( "node" + to.eventNumber + " [label = \"" + to.toString() + "\"];");
					}
		        }
		    }

			Iterator<Entry<EventNode, List<EventNode>>> it2 = edges.entrySet().iterator();
			while (it2.hasNext()) {
				Entry<EventNode, List<EventNode>> pair = it2.next();
				for (EventNode child : pair.getValue()) {
					long keyEventNumber = pair.getKey().eventNumber;
					long tail = keyEventNumber;
					long head = child.eventNumber;
					if (keyEventNumber > child.eventNumber) {
						tail = child.eventNumber;
						head = keyEventNumber;
					}
					printWriter.println("\"node" + tail + "\" -> \"node" + head + "\"" +
							(keyEventNumber > child.eventNumber ? " [dir=\"back\"]" : "") + ";");
				}
			}

		    printWriter.println("}");
		    printWriter.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	

	private static void addEventToItsThreadList(Map<Integer, SortedSet<EventNode>> threadIdToThreadEvents, EventNode eventNode) {
		if (!threadIdToThreadEvents.containsKey(eventNode.getThreadId())) {
			threadIdToThreadEvents.put(eventNode.getThreadId(), new TreeSet<>(Comparator.comparing((ev) -> ev.eventNumber)));
		}
		threadIdToThreadEvents.get(eventNode.getThreadId()).add(eventNode);
	}
	
	public String getNodeLabel () {
		if (DEBUG_LABELS && !WDCTool.isRunning()) {
			if (finalNodeLabelMap == null) finalNodeLabelMap = nodeLabelMap.getMerged();
			return finalNodeLabelMap.get(this);
		} else {
			return "?";
		}
	}

	public String getSourceLoc () {
		if (DEBUG_SOURCE_LOCS && !WDCTool.isRunning()) {
			if (finalSourceLocMap == null) finalSourceLocMap = sourceLocMap.getMerged();
			return finalSourceLocMap.get(this);
		} else {
			return "?";
		}
	}
	
	public final int getThreadId(){
		return this.threadID;
	}
	

	// Collect Latex output to examine events included in the cycles leading to at least one conflicting access of a WCP/WDC race.
	@Deprecated
	static void generateLatex(EventNode firstNode, EventNode secondNode, TreeMap<Long, EventNode> unorderedEvents, boolean secondCycleDetected, boolean firstCycleDetected) {
		Assert.assertTrue(DEBUG_ACCESS_INFO);
		if (secondCycleDetected || firstCycleDetected) {
			try {
				BufferedWriter collectLatex = new BufferedWriter(new FileWriter("latex_output/LatexTrace_"+firstNode.eventNumber+"_"+secondNode.eventNumber+".tex"));
				collectLatex.write("\\documentclass{article}\n\\begin{document}\\begin{figure}\n\\centering\n\\begin{tabular}{");
				for (int i = 0; i < ShadowThread.maxActiveThreads(); i++) {
					collectLatex.write("l");
				}
				collectLatex.write("}\n");
				for (int i = 0; i < ShadowThread.maxActiveThreads()-1; i++) {
					collectLatex.write("T" + i + " & ");
				}
				collectLatex.write("T" + (ShadowThread.maxActiveThreads()-1) + " \\\\\\hline\n");

				Iterator<Entry<Long, EventNode>> totalOrder = unorderedEvents.entrySet().iterator();
				while (totalOrder.hasNext()) {
					EventNode orderedEvent = (EventNode) totalOrder.next().getValue();
					if (orderedEvent instanceof RdWrNode) {
						RdWrDebugNode orderedRdWr = (RdWrDebugNode) orderedEvent;
						for (int i = 0; i < orderedRdWr.getThreadId(); i++) {
							collectLatex.write("&");
						}
						// TODO: One event may have more than one write/read, this only makes sense if merging is disabled
//						collectLatex.write("wr(V"+orderedRdWr.getAccesses().firstElement().var.hashCode()+")");
						for (int i = orderedRdWr.getThreadId()+1; i < ShadowThread.maxActiveThreads(); i++) {
							collectLatex.write("&");
						}
						collectLatex.write("\\\\\n");
					} else if (orderedEvent instanceof AcqRelNode) {
						AcqRelNode orderedAcqRel = (AcqRelNode) orderedEvent;
						if (orderedAcqRel.isAcquire) {
							for (int i = 0; i < orderedAcqRel.getThreadId(); i++) {
								collectLatex.write("&");
							}
							collectLatex.write("acq(L"+orderedAcqRel.shadowLock.getLock().hashCode()+")");
							for (int i = orderedAcqRel.getThreadId()+1; i < ShadowThread.maxActiveThreads(); i++) {
								collectLatex.write("&");
							}
							collectLatex.write("\\\\\n");
						} else {
							for (int i = 0; i < orderedAcqRel.getThreadId(); i++) {
								collectLatex.write("&");
							}
							collectLatex.write("rel(L"+orderedAcqRel.shadowLock.getLock().hashCode()+")");
							for (int i = orderedAcqRel.getThreadId()+1; i < ShadowThread.maxActiveThreads(); i++) {
								collectLatex.write("&");
							}
							collectLatex.write("\\\\\n");
						}
					}
				}
				
				collectLatex.write("\\hline\n\\end{tabular}\n\\end{figure}\n\\end{document}");
				collectLatex.close();
			} catch (IOException e) {
				Util.println(e.getMessage());
			}
		}
	}
	
	public int getLatestTraversal() {
		return myLatestTraversal;
	}
	
	@Override
	public String toString() {
		return String.format("%s#%d T%d @ %s", getNodeLabel(), eventNumber, threadID, getSourceLoc());
	}
	
	public static void addEventToThreadToItsFirstEventsMap(EventNode eventNode) {
		if (VERBOSE_GRAPH) Assert.assertTrue(eventNode.eventNumber >= 0 || ShadowThread.get(eventNode.getThreadId()).getThread().getName().equals("Finalizer"));
		if ( !threadToFirstEventMap.containsKey(eventNode.getThreadId()) ) {
			threadToFirstEventMap.put(eventNode.getThreadId(), eventNode);
		} else {
			if ( threadToFirstEventMap.get(eventNode.getThreadId()).eventNumber > eventNode.eventNumber ) {
				threadToFirstEventMap.put(eventNode.getThreadId(), eventNode);
			}
		}
	}
	
	private static EventNode getOriginalTraceNextEvent(Map<Integer, EventNode> threadToItsNextEventMap){
		Iterator<Entry<Integer, EventNode>> it = threadToItsNextEventMap.entrySet().iterator();
	    EventNode nextTraceEvent = null;
		while (it.hasNext()) {
	    	Entry<Integer, EventNode> pair = it.next();
	    	if ( nextTraceEvent == null || ( pair.getValue() != null && (pair.getValue()).eventNumber < nextTraceEvent.eventNumber) ) {
	    		nextTraceEvent = pair.getValue();
	    	}
	    }		
		if ( nextTraceEvent != null ) {
			EventNode nextThreadEvent = null;
			for (EventNode sink : nextTraceEvent.sinkOrSinks) {
				if ( (sink.getThreadId() == nextTraceEvent.getThreadId()) &&
						(nextThreadEvent == null || (nextTraceEvent.eventNumber < sink.eventNumber && sink.eventNumber < nextThreadEvent.eventNumber)) ) {
					nextThreadEvent = sink;
				}
			}
			threadToItsNextEventMap.put(nextTraceEvent.getThreadId(), nextThreadEvent);
		}		
		return nextTraceEvent;
	}


	private static boolean forwardVerifyReorderedTrace(Collection<EventNode> trPrime, EventNode first, EventNode second, Set<RdWrNode> coreReads) {
		Map<Integer, EventNode> lastEvent = new HashMap<>(); // Maps threads to the last event by them
		Map<ShadowLock, Integer> lockHeldBy = new HashMap<>(); // Maps locks to threads holding them
		Map<ShadowID, RdWrNode> lastWrites = new HashMap<>(); // Maps variables to their last writers
		// For every thread, maps variables to the last write by that thread, but only if there are any potential LW violation
		Map<Integer, Map<ShadowID, RdWrNode>> lastWriteByThread = new HashMap<>();
		// Same as lastWriteByThread, but for reads, and only if there is already a corresponding entry in lastWriteByThread
		Map<Integer, Map<ShadowID, RdWrNode>> lastReadByThread = new HashMap<>();
		Map<Integer, Map<RdWrNode, RdWrNode>> potentialLWViolations = new HashMap<>(); // Maps threads to their reads that may violate LW
		
		for (EventNode event : trPrime) {
			// PO
			EventNode last = lastEvent.get(event.getThreadId());
			if (last != null) {
				if (last.eventNumber >= event.eventNumber) {
					if (VERBOSE_GRAPH) {
						Assert.assertFalse(event.equals(last), "Last event " + last + " is same as current event " + event);
						Assert.assertTrue(event.getThreadId() == last.getThreadId(), "Event in wrong last event map for PO check");
					}
					Util.log("PO ordering violated in the reordered trace by events " + last + " and " + event + " by T" + event.getThreadId());
					return false;
				}
				if (!isPreviousEvent(event, last)) {
					return false;
				}
			}
			lastEvent.put(event.getThreadId(), event);

			// LS
			if (event instanceof AcqRelNode) {
			AcqRelNode syncEvent = (AcqRelNode) event;
				ShadowLock sl = syncEvent.shadowLock;
				if (syncEvent.isAcquire) {
					if (lockHeldBy.containsKey(sl)) {
						Util.log("Lock semantics violated in the reordered trace, T" + event.getThreadId()
								+ " is trying to acquire lock " + Util.objectToIdentityString(sl.getLock())
								+ " which is already held by T" + lockHeldBy.get(sl));
						return false;
					}
					lockHeldBy.put(sl, event.getThreadId());
				} else { // syncEvent is release
					if (!lockHeldBy.containsKey(sl)) {
						Util.log("Lock semantics violated in the reordered trace, T" + event.getThreadId()
								+ " is trying to release lock " + Util.objectToIdentityString(sl.getLock())
								+ " which it is not holding!");
						return false;
					}
					lockHeldBy.remove(sl);
				}
			}

			// LW
			if (event instanceof BranchNode && !potentialLWViolations.isEmpty()) {
				Map<RdWrNode,RdWrNode> potLW = potentialLWViolations.get(event.getThreadId());
				// Any read PO ordered before this branch is a CORE read.
				// If we have seen any potential LW violations so far by this thread, they are actual violations.
				if (potLW != null && !potLW.isEmpty()) {
					for (Map.Entry<RdWrNode, RdWrNode> violation : potLW.entrySet()) {
						RdWrNode depRead = violation.getKey();
						// If we do have SDG information, it is only a violation if the branch depends on the read
						if (NO_SDG || containsNode(((BranchNode) event).getDeps(), depRead)) {
							Util.log("Last writer violated in the reordered trace, T" + event.getThreadId()
									+ " has read " + depRead
									+ " has " + (depRead.hasLastWriter() ? " last writer " + depRead.lastWriter() : " no last writer")
									+ " but saw the last write " + violation.getValue()
							);
							Util.log("findCoreReads says " + depRead + " is " + (coreReads.contains(depRead) ? "a CORE read " : "NOT a CORE read") + ".");
							return false;
						}
					}
				}

				// Whenever we see a branch, any read PO ordered before it is a CORE read
				Vector<RdWrNode> coreTraversal = new Vector<>();
				Map<ShadowID,RdWrNode> branchDepReads = lastReadByThread.get(event.getThreadId());
				if (branchDepReads != null)
					Util.addAll(
							coreTraversal,
							branchDepReads.values().stream().filter(read -> NO_SDG || containsNode(((BranchNode) event).getDeps(), read))
					);
				while (!coreTraversal.isEmpty()) {
					RdWrNode current = coreTraversal.remove(coreTraversal.size() - 1);
					if (current.isRead() && current.hasLastWriter()) {
						// Last writer of a CORE read is a CORE write
						coreTraversal.add(current.lastWriter());
					} else if (current.isWrite()) {
						// If a potential LW violation is PO ordered before this write, it is a real LW violation
						Map<RdWrNode,RdWrNode> LWviolations = potentialLWViolations.get(current.getThreadId());
						if (LWviolations != null && !LWviolations.isEmpty()) {
							for (RdWrNode violation : LWviolations.keySet()) {
								if (violation.eventNumber < current.eventNumber) {
									Util.log("Last writer violated in the reordered trace, T" + violation.getThreadId()
											+ "has read " + violation
											+ "has " + (violation.hasLastWriter() ? " last writer " + violation.lastWriter() : " no last writer")
											+ " but saw the last write " + LWviolations.get(violation)
									);
									return false;
								}
							}
						}

						// Any read PO ordered before a CORE write is also a CORE read.
						Map<ShadowID,RdWrNode> writePOReads = lastReadByThread.get(current.getThreadId());
						if (writePOReads != null) {
							for (RdWrNode poRead : writePOReads.values()) {
								if (poRead.eventNumber < current.eventNumber) {
									coreTraversal.add(poRead);
								}
							}
						}
					}
				}
			}
			if (event instanceof RdWrNode) {
				RdWrNode currEvent = (RdWrNode) event;
				if (currEvent.isWrite) {
					lastWrites.put(currEvent.var, currEvent);
					if (!potentialLWViolations.isEmpty()) {
						// If there are potential LW violations, a CORE write may make them real violations.
						// Track writes so that we can establish CORE writes after this point.
						lastWriteByThread.computeIfAbsent(currEvent.getThreadId(), HashMap::new).put(currEvent.var, currEvent);
					}
				} else { // is read
					if (/* If there is a write reordered before a read that has no last writer */
							currEvent.hasNoLastWriter() && lastWrites.containsKey(currEvent.var)
									/* or the last writer for a read has changed */
									|| currEvent.hasLastWriter() && !currEvent.lastWriter().equals(lastWrites.get(currEvent.var))) {
						// This read may be a LW violation if it is a CORE read
						potentialLWViolations.computeIfAbsent(currEvent.getThreadId(), HashMap::new).put(currEvent, lastWrites.get(currEvent.var));
					}
					// If we have seen a write that is PO ordered after a read which might be a potential LW violation,
					// record this read as well so that we can catch any transitive CORE events.
					if (Util.any(lastWriteByThread.values(), (m) -> m.containsKey(currEvent.var))) {
						lastReadByThread.computeIfAbsent(currEvent.getThreadId(), HashMap::new).put(currEvent.var, currEvent);
					}
				}
			}
		}
		return true;
	}

	private static void printSinks(RdWrDebugNode lastAcc) {
		Util.log("Sinks for access " + lastAcc.getNodeLabel() + " | eventNumber: " + lastAcc.eventNumber + " | surroundingCS: " + lastAcc.inCS);
		for (EventNode ePrime : lastAcc.sinkOrSinks) {
            Util.log("--sink--> " + ePrime.getNodeLabel() + " | eventNumber: " + ePrime.eventNumber + " | surroundingCS: " + ePrime.inCS);
            for (EventNode ePrimePrime : ePrime.sinkOrSinks) {
                Util.log("--sink--of sink--> " + ePrimePrime.getNodeLabel() + " | eventNumber: " + ePrimePrime.eventNumber + " | surroundingCS: " + ePrimePrime.inCS);
            }
        }
	}

	// Checks that prev is actually just before event in PO
	private static boolean isPreviousEvent(EventNode event, EventNode prev) {
		EventNode prevPO = null;
		for (EventNode currSource : event.sourceOrSources) {
            if (event.getThreadId() == currSource.getThreadId() && (prevPO == null || prevPO.eventNumber < currSource.eventNumber)) {
                prevPO = currSource;
            }
        }
		if (prevPO == null) {
			Util.log("Event is no the first event by the thread, but doesn't have any PO edges to previous events");
			return false;
		}
		if (prevPO.eventNumber != prev.eventNumber) {
            Util.log(String.format("Event %s was expected to have %s as PO-prior, but found %s instead", event, prevPO, prev));
            return false;
        }
        return true;
	}

	/**
	 * Given two event numbers and the first event within the execution, counts all events by all threads that fall within this window.
	 *
	 * @param firstEventOfEntireTrace The first event within the entire trace. Or, some event that is sufficiently
	 *                                early for all events by all threads in the requested window to be forward reachable.
	 * @param min The earliest event number that should be counted.
	 * @param max The latest event number that should be counted.
	 * @return The number of events by all threads that fall within this window.
	 */
	public static int countEventsInWindow(EventNode firstEventOfEntireTrace, int min, int max) {
		short visited = (short) prepUniqueTraversal();
		int count = 0;
		Vector<EventNode> traversal = new Vector<>();
		traversal.add(firstEventOfEntireTrace);
		while (!traversal.isEmpty()) {
			EventNode current = traversal.remove(traversal.size() - 1);
			// Skip events we already visited, or ones that are after the window.
			if (current.myLatestTraversal == visited || current.eventNumber > max) continue;
			Util.addAll(traversal, current.sinkOrSinks);
			current.myLatestTraversal = visited;
			if (min < current.eventNumber && current.eventNumber < max) {
				++count;
			}
		}
		return count;
	}


	/**
	 * Given an event, count the number of events that could be reached via a backwards traversal.
	 *
	 * @param start The event at which the backward traversal starts.
	 * @return The number of events by all threads that are backwards reachable from the start.
	 */
	public static int countBackwardsReachable(EventNode start) {
		short visited = (short) prepUniqueTraversal();
		int count = 0;
		Vector<EventNode> traversal = new Vector<>();
		traversal.add(start);
		while (!traversal.isEmpty()) {
			EventNode current = traversal.remove(traversal.size() - 1);
			// Skip events we already visited, or ones that are after the window.
			if (current.myLatestTraversal == visited) continue;
			Util.addAll(traversal, current.sourceOrSources);
			current.myLatestTraversal = visited;
			++count;
		}
		return count;
	}
}
