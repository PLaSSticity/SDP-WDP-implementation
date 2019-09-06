package tools.br.event;

import acme.util.Assert;
import acme.util.Util;
import acme.util.collections.HashSetVector;
import acme.util.collections.PerThread;
import acme.util.collections.Tuple2;
import rr.state.ShadowLock;
import rr.state.ShadowThread;
import rr.state.ShadowVar;
import rr.tool.RR;
import tools.br.BRToolBase;

import java.io.*;
import java.util.*;
import java.util.Map.Entry;

public class EventNode implements Iterable<EventNode> {

	public long eventNumber;
	public final AcqRelNode inCS;
	final int threadID; // TODO: change to ShadowThread? Also, could be combined with inCS; only AcqRelNode needs a threadID then
	public static final boolean VERBOSE_GRAPH = false;

	Iterable<EventNode> sinkOrSinks = EMPTY_NODES;
	public Iterable<EventNode> sourceOrSources = EMPTY_NODES;

	static final Iterable<EventNode> EMPTY_NODES = Collections::emptyIterator;

	int myLatestTraversal;

	static int nextTraversal = 1;
	static HashMap<Integer, EventNode> threadToFirstEventMap = new HashMap<Integer, EventNode>();

	/* Adds example numbers and node labels to events */
	public static final boolean DEBUG_EXNUM_LABEL = false;
	/* Adds field names and some extra debug information to read/write events */
	public static final boolean DEBUG_ACCESS_INFO = true; // TODO: Make this false by default
	/* Adds source locations to ALL events. */
	public static final boolean DEBUG_SOURCE_LOCS = true;

	/* Perform a cycle check on every iteration of addconstraints. */
	static final boolean CYCLE_CHECK_EVERY_ITERATION = true;
	/* Print out the partial reordering if the reordering algorithm fails */
	static final boolean PRINT_FAILED_REORDERING = false;
	/* The number of dynamic instances of static races to vindicate before giving up (if they all fail). */
	public static final int STATIC_RACE_FAIL_THRESHOLD = 3;

	static final HashMap<EventNode,Long> exampleNumberMap;
	static final HashMap<EventNode,String> nodeLabelMap;
	static final PerThread<Map<EventNode,String>> sourceLocMap;

	static {
		if (DEBUG_EXNUM_LABEL) {
			exampleNumberMap = new HashMap<EventNode,Long>();
			nodeLabelMap = new HashMap<EventNode,String>();
		} else {
			exampleNumberMap = null;
			nodeLabelMap = null;
		}
		if (DEBUG_SOURCE_LOCS) {
			sourceLocMap = new PerThread<>(RR.maxTidOption.get(), HashMap::new, (l, r) -> {l.putAll(r); return l;});
		} else {
			sourceLocMap = null;
		}
	}

	public EventNode(long eventNumber, long exampleNumber, int threadID, AcqRelNode inCS) {
		this(eventNumber, exampleNumber, threadID, inCS, "");
	}
	
	public EventNode(long eventNumber, long exampleNumber, int threadId, AcqRelNode inCS, String nodeLabel) {
		this.eventNumber = eventNumber;
		this.inCS = inCS;
		this.threadID = threadId;
		
		if (DEBUG_EXNUM_LABEL) {
			exampleNumberMap.put(this, exampleNumber);
			nodeLabelMap.put(this, nodeLabel);
		}
	}

	public static boolean edgeExists(EventNode sourceNode, EventNode sinkNode) {
		boolean exists = containsNode(sourceNode.sinkOrSinks, sinkNode);
		if (VERBOSE_GRAPH) Assert.assertTrue(containsNode(sinkNode.sourceOrSources, sourceNode) == exists);
		return exists;
	}
	
	public static void addEdge(EventNode sourceNode, EventNode sinkNode) {
		if (VERBOSE_GRAPH) Assert.assertTrue(sourceNode != sinkNode);
		synchronized(sourceNode) {
			sourceNode.sinkOrSinks = addNode(sourceNode.sinkOrSinks, sinkNode);
		}
		sinkNode.sourceOrSources = addNode(sinkNode.sourceOrSources, sourceNode);
		//Only update sinkNode's eventNumber if it has no sources (as in, before fini() is called)
		if (sinkNode.sinkOrSinks.equals(EMPTY_NODES) && sourceNode.eventNumber >= sinkNode.eventNumber) {
			sinkNode.eventNumber = sourceNode.eventNumber + 1;
			//if (VERBOSE_GRAPH) addEventToThreadToItsFirstEventsMap(sinkNode);
		} else {
			if (VERBOSE_GRAPH && BRToolBase.running) {
//			if (VERBOSE_GRAPH) Assert.assertTrue(sinkNode.eventNumber >= 0);
				// If we are still running, we shouldn't be adding any backward edges
				Assert.assertTrue(sinkNode.sinkOrSinks.equals(EMPTY_NODES),
								  "Sink node of edge being added has outgoing edges: " + sinkNode.sinkOrSinks);
			}
		}
		if (VERBOSE_GRAPH) {
			Assert.assertFalse(sourceNode == sinkNode, "Edge source and sink are same, creates cycle on the same node!");
			Assert.assertFalse(sourceNode.eventNumber == sinkNode.eventNumber && sourceNode.threadID == sinkNode.threadID,
					"PO-ordered source and sink nodes have same event number!");
		}
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
				node.myLatestTraversal = traversal;
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
	
	long getExampleNumber() {
		if (DEBUG_EXNUM_LABEL) {
			return exampleNumberMap.get(this);
		} else {
			return -1;
		}
	}
	
	//Add Constraints (+ conflicting accesses)
	public static Tuple2<Integer, Set<RdWrNode>> addConstraints(RdWrNode firstNode, RdWrNode secondNode, long[] windowRange, LinkedList<Edge> initialEdges, LinkedList<Edge> initialEdgesToRemove, LinkedList<Edge> additionalBackEdges, LinkedList<Edge> additionalForwardEdges, LinkedList<Edge> additionalConflictingEdges) {
		// Create edges from one node's predecessors to the other node
		for (EventNode source : secondNode.sourceOrSources) {
			Edge backEdge = new Edge(source, firstNode);
			if ((! edgeExists(source, firstNode)) && (! firstNode.equals(source))) { // This edge might already exist
				// TODO: is it reasonable for this edge to already exist? it's happening with aggressive merging enabled
				initialEdges.add(backEdge);
				initialEdgesToRemove.add(backEdge);
				EventNode.addEdge(source, firstNode);
			}
		}
		for (EventNode source : firstNode.sourceOrSources) {
			Edge forwardEdge = new Edge(source, secondNode);
			initialEdges.add(forwardEdge);
			if ((!edgeExists(source, secondNode)) && (! secondNode.equals(source))) { // This edge might already exist
				initialEdgesToRemove.add(forwardEdge);
				EventNode.addEdge(source, secondNode);
			}
		}
		
		boolean addedBackEdge; // back edge added on current iteration?
		boolean addedForwardEdge; // forward edge added on current iteration?
		boolean addedConstraintEdge; // constraint edge added on current iteration? May create a new path for critical sections to need LS constraints added
		int iteration = 0;
		Set<RdWrNode> coreReads = null;
				
		do {
			if (CYCLE_CHECK_EVERY_ITERATION && detectCycle(firstNode, secondNode, windowRange, iteration)) {
				Util.log("Found cycle while iterating AddConstraints");
				break;
			}
			addedBackEdge = false;
			addedForwardEdge = false;
			addedConstraintEdge = false;

			++iteration;
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

			// Add last-writer constraints for CORE reads
			coreReads = findCoreReads(firstNode, secondNode);  // Assumes that the race edge (or consecutive event edges) exist, otherwise repeat for first node
			if (coreReads == null) break;
			for (RdWrNode read : coreReads) {
				if (read.hasNoLastWriter()) continue;
				RdWrNode write = read.lastWriter();
				if (!edgeExists(write, read)) {
					EventNode.addEdge(write, read);
					additionalConflictingEdges.add(new Edge(write, read));
				}
			}

			if (CYCLE_CHECK_EVERY_ITERATION && detectCycle(firstNode, secondNode, windowRange, iteration)) {
				Util.log("Found cycle after adding LW edges");
				break;
			}

			for (Edge edge : separateInitNodes) {
				//Add LS Constraints
				// First do a reverse traversal from the second access and possibly from other edge sources
				HashMap<ShadowLock,HashMap<ShadowThread, AcqRelNode>> reachableAcqNodes = new HashMap<ShadowLock,HashMap<ShadowThread, AcqRelNode>>();
				ArrayDeque<EventNode> grayNodes = new ArrayDeque<EventNode>();
				int traversal = prepUniqueTraversal();
				grayNodes.add(edge.source);
				while (!grayNodes.isEmpty()) {
					EventNode node = grayNodes.removeFirst();
					if (node.myLatestTraversal != traversal) {
						// We don't care about nodes outside the window
						if (node.eventNumber >= windowRange[0]/*windowMin*/) {
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
							node.myLatestTraversal = traversal;
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
						if (node.eventNumber <= windowRange[1]/*windowMax*/) {
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
							node.myLatestTraversal = traversal;
							for (EventNode sink : node.sinkOrSinks) {
								grayNodes.add(sink);
							}
						}
					}
				}
				// Now check for edges that indicate a back edge w.r.t. total order
				for (ShadowLock shadowLock : reachableAcqNodes.keySet()) {
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
										Util.println("Found acq->rel that needs back edge: " + shadowLock + ", " + acqNode + " T" + acqNode.getThreadId() + " -> " + relNode + " T" + relNode.getThreadId() + " | " + acqNode.getExampleNumber() + "->" + relNode.getExampleNumber());
										EventNode.addEdge(acqNode.otherCriticalSectionNode, relNode.otherCriticalSectionNode);
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
										if (VERBOSE_GRAPH) Assert.assertTrue(bfsTraversal(acqNode, relNode, null, tempWindowMin, tempWindowMax));//, "tempWindowMin: " + tempWindowMin + " | tempWindowMax: " + tempWindowMax + " | acqNode: " + acqNode.getNodeLabel() + ", eventNumber: " + acqNode.eventNumber + " | relNode: " + relNode.getNodeLabel() + ", eventNumber: " + relNode.eventNumber); // Assert a path actually exists from acqNode -> relNode
										// Add forward edge and signal we should repeat this whole process
										Util.println("Found rel->acq that needs forward edge: " + shadowLock + ", " + acqNode.otherCriticalSectionNode + " T" + acqNode.otherCriticalSectionNode.getThreadId() + " -> " + relNode.otherCriticalSectionNode + " T" + relNode.otherCriticalSectionNode.getThreadId() + " | " + acqNode.otherCriticalSectionNode.getExampleNumber() + "->" + relNode.otherCriticalSectionNode.getExampleNumber());
										EventNode.addEdge(acqNode.otherCriticalSectionNode, relNode.otherCriticalSectionNode);
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
		} while (addedBackEdge || addedForwardEdge || addedConstraintEdge);
		return new Tuple2<>(iteration, coreReads);
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
			secondCycleDetected = simplerIterativeDetectCycle(secondNode, false, gray, black, windowRange[0]/*windowMin*/, windowRange[1]/*windowMax*/);
			//secondCycleDetected = iterativeDfsDetectCycle(secondNode, false, gray, black, windowMin, windowMax, secondCycleEdges);
		} else {
			secondCycleDetected = dfsDetectCycle(secondNode, false, gray, black, windowRange[0], windowRange[1]);
		}
		if (secondCycleDetected) Util.println("Cycle reaches second node : " + secondCycleDetected + ". After " + iterations + " iterations.");

		black = EventNode.prepUniqueTraversal(2);
		gray = black - 1;
		boolean firstCycleDetected;
		if (useIterativeCycleDetection) {
			firstCycleDetected = simplerIterativeDetectCycle(firstNode, false, gray, black, windowRange[0]/*windowMin*/, windowRange[1]/*windowMax*/);
			//firstCycleDetected = iterativeDfsDetectCycle(firstNode, false, gray, black, windowMin, windowMax, firstCycleEdges);
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

	/**
	 * @param firstNode First event in the race pair.
	 * @param secondNode Second event in the race pair.
	 * @param coreReads Set of read events that are CORE for the current reordering.
	 * @param commandDir
	 * @return False if reordering is successful, otherwise true.
	 */
	public static boolean constructReorderedTrace(RdWrNode firstNode, RdWrNode secondNode, Set<RdWrNode> coreReads, File commandDir) {
		// Backward Reordering if no cycle was found for the WBR race
		HashSetVector<EventNode> trPrime = new HashSetVector<>(); // Provides constant time "contains" checks, preserves insertion order


		int total_random_reorders = RR.wdcRandomReorderings.get();
		if (total_random_reorders > 0) {Util.log("Doing " + total_random_reorders + " random reorderings");}
		boolean failed = true;

		for (int reorders = 0; reorders <= total_random_reorders && failed; reorders++) {
			HashSet<AcqRelNode> missingRelease = new HashSet<>();
			int white = nextTraversal;
			Map<ShadowID, Map<Integer, RdWrNode>> thrToFirstWrPerVar = buildR(firstNode, secondNode, missingRelease);
			
			while (trPrime.isEmpty()) {
				trPrime = backReorderTrace(firstNode, secondNode, trPrime, white, reorders, missingRelease, thrToFirstWrPerVar, coreReads);
				if (trPrime.isEmpty()) {
					String reorderName;
					if (reorders == 0) reorderName = "latest-first"; else if (reorders == 1) reorderName = "earliest-first"; else reorderName = "random";
					Util.log("Backward Reordering " + reorders + " got stuck when doing " + reorderName + " reordering!");
					// Checking for cycles every time reordering gets stuck is very costly for some reason
//					Map<EventNode, List<EventNode>> bfsCycleEdges = new HashMap<>();
//					black = EventNode.prepUniqueTraversal(2);
//					gray = black - 1;
//					iterativeDfsDetectCycle(firstNode, false, gray, black, windowRange[0]/*windowMin*/, windowRange[1]/*windowMax*/, bfsCycleEdges);
//					generateInputFileForGraphviz(bfsCycleEdges, true, true, true);
					break;
				} else if (trPrime.size() == 1) {
					//Update R with missing release event and all reachable events
					missingRelease.add((AcqRelNode)trPrime.removeLast());
					// TODO: Should we keep the old white, or get new one when rebuilding R?
//					white = nextTraversal;
					thrToFirstWrPerVar = buildR(firstNode, secondNode, missingRelease);
				}
			}

			if (trPrime.size() == 0) {
				Util.log("Unable to reorder trace.");
				continue;
			}
			failed = false;
			// Reordering was successful
			if (commandDir != null && !trPrime.isEmpty()){
				printReordering(trPrime, firstNode, secondNode, commandDir);
			}
			Collections.reverse(trPrime);
			Assert.assertTrue(forwardVerifyReorderedTrace(trPrime, firstNode, secondNode), "Reordered trace is invalid.");
			trPrime = new HashSetVector<>();
		}
		return failed;
	}

	public static boolean crazyNewEdges(RdWrNode firstNode, RdWrNode secondNode, File commandDir) {
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


		Tuple2<Integer, Set<RdWrNode>> t = addConstraints(firstNode, secondNode, windowRange, initialEdges, initialEdgesToRemove, additionalBackEdges, additionalForwardEdges, additionalConflictingEdges);
		int iterations = t.first();
		boolean failed;
		Set<RdWrNode> coreReads = t.second();
		failed = coreReads == null;

		if (!failed) failed = detectCycle(firstNode, secondNode, windowRange, iterations);
		
		if (!failed) failed = constructReorderedTrace(firstNode, secondNode, coreReads, commandDir);
		
		// Finally remove all of the added edges
		for (Edge e : initialEdgesToRemove) {
			EventNode.removeEdge(e.source, e.sink);
		}
		for (Edge e : additionalBackEdges) {
			EventNode.removeEdge(e.source, e.sink);
		}
		for (Edge e : additionalForwardEdges) {
			EventNode.removeEdge(e.source, e.sink);
		}
		for (Edge e : additionalConflictingEdges) {
			EventNode.removeEdge(e.source, e.sink);
		}
				
		return failed;
	}
	
	static Map<ShadowID, Map<Integer, RdWrNode>> buildR (EventNode firstNode, EventNode secondNode, HashSet<AcqRelNode> missingRelease) {
        Vector<EventNode> start = new Vector<>();
        start.add(firstNode);
        start.add(secondNode);
        start.addAll(missingRelease);
        return findFirstWriteOfVarsPerThread(start);
	}
	
	static HashSetVector<EventNode> backReorderTrace(RdWrNode firstNode, RdWrNode secondNode, HashSetVector<EventNode> trPrime, int white, int reorderingAttempt, HashSet<AcqRelNode> missingRelease, Map<ShadowID, Map<Integer/*thread id*/, RdWrNode>> thrToFirstWrPerVar, Set<RdWrNode> coreReads) {
		int black = prepUniqueTraversal();
		HashMap<Integer, EventNode> traverse = new HashMap<>();
		for (EventNode missingRel : missingRelease) {
			if (!traverse.containsKey(missingRel.threadID) || traverse.get(missingRel.threadID).eventNumber < missingRel.eventNumber) {
				traverse.put(missingRel.threadID, missingRel);
			}
		}
		trPrime.add(secondNode);
		secondNode.myLatestTraversal = black;
		trPrime.add(firstNode);
		firstNode.myLatestTraversal = black;
		
		for (EventNode firstSource : firstNode.sourceOrSources) {
			if (firstSource.myLatestTraversal >= white && firstSource.myLatestTraversal != black) {
				if (!traverse.containsKey(firstSource.threadID) || traverse.get(firstSource.threadID).eventNumber < firstSource.eventNumber) {
					traverse.put(firstSource.threadID, firstSource);
				}
			}
		}
		for (EventNode secondSource : secondNode.sourceOrSources) {
			if (secondSource.myLatestTraversal >= white && secondSource.myLatestTraversal != black) {
				if (!traverse.containsKey(secondSource.threadID) || traverse.get(secondSource.threadID).eventNumber < secondSource.eventNumber) {
					traverse.put(secondSource.threadID, secondSource);
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
			collectOpenRead(firstNode, openReadsMap);
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
			EventNode e;
			if (reorderingAttempt == 0) e = reorderLatestFirst(traverse, latestCheck, attemptedEvents);
			else if (reorderingAttempt == 1) e = reorderEarliestFirst(traverse, latestCheck, attemptedEvents);
			else e = reorderRandom(traverse, attemptedEvents);

			if (e == null) {
				backReorderStuck(trPrime, white, black, traverse, heldLocks, latestCheck, attemptedEvents);
				return new HashSetVector<>();
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

			
			// Add event e to the reordered trace tr' if it satisfies all checks
			if (checkConstraints(white, black, e) && checkLS(heldLocks, e) && checkLW(trPrime, thrToFirstWrPerVar, coreReads, openReadsMap, e)) {
				trPrime.add(e);

				if (e instanceof RdWrNode) {
					RdWrNode access = (RdWrNode) e;
					if (access.isWrite()) {
						Vector<RdWrNode> openReads = openReadsMap.get(access.var);
						if (openReads != null && !openReads.isEmpty()) {
							// There may be multiple open reads to close
							openReads.removeIf(read -> read.hasLastWriter() && read.lastWriter().equals(access));
						}
					} else if (/*access is read && */ access.hasLastWriter()) {
						if (coreReads.contains(access)) {
							Vector<RdWrNode> openReads = openReadsMap.computeIfAbsent(access.var, (var) -> new Vector<>());
							openReads.add(access);
						}
					}
				}
				attemptedEvents = new HashSet<>();
				e.myLatestTraversal = black;
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
				traverse.remove(e.threadID);
				for (EventNode eSource : e.sourceOrSources) {
					if (eSource.myLatestTraversal >= white && eSource.myLatestTraversal != black) {
						if (!traverse.containsKey(eSource.threadID) || traverse.get(eSource.threadID).eventNumber < eSource.eventNumber) {
							traverse.put(eSource.threadID, eSource);
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

	private static boolean checkLW(HashSetVector<EventNode> trPrime, Map<ShadowID, Map<Integer, RdWrNode>> thrToFirstWrPerVar, Set<RdWrNode> coreReads, HashMap<ShadowID, Vector<RdWrNode>> openReadsMap, EventNode event) {
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
					if (VERBOSE_GRAPH) Assert.assertTrue(coreReads.contains(read), "Non-CORE read in openReadsMap");
					return false;
				}
			}
		} else { // access is read
			if (!coreReads.contains(access)) return true; // A non-CORE read can't violate LW

			// If a CORE read has no last writer, it must be added after all conflicting writes have been added.
			if (access.hasNoLastWriter()) {
				Map<Integer, RdWrNode> thrPerVar = thrToFirstWrPerVar.get(access.var);
				if (thrPerVar != null) {
					for (RdWrNode firstWrite : thrPerVar.values()) {
						if (!trPrime.contains(firstWrite)) {
							return false;
						}
					}
				}
			} else { // access does have a last writer
				EventNode lastWrite = access.lastWriter();
				// The last writer of the current read can not be in trPrime, otherwise we somehow missed a write-read edge
				if (VERBOSE_GRAPH) Assert.assertFalse(trPrime.contains(lastWrite));

				Vector<RdWrNode> openReads = openReadsMap.get(access.var);
				if (openReads == null || openReads.isEmpty()) {
					return true; // There are no open reads, adding this read can't violate LW
				}

				// If we have an open read and we are trying to add a new read to trPrime (and they both access the same variable),
				// both reads must have the same last writer. Otherwise, one of the reads will have a wrong last writer.
				for (RdWrNode read : openReads) {
					if (VERBOSE_GRAPH) {
						Assert.assertFalse(trPrime.contains(read.lastWriter()), "Open read already had its last writer added to trPrime");
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

	private static void collectOpenRead(RdWrNode read, HashMap<ShadowID, Vector<RdWrNode>> openReadsMap) {
		RdWrNode lastWriter = read.lastWriter();
		if (edgeExists(lastWriter, read) /* is a CORE read */) {
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
	private static EventNode reorderLatestFirst(HashMap<Integer, EventNode> traverse, EventNode latestCheck, HashSet<Integer> attemptedEvents) {
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
                } else if (traverse.get(tid).eventNumber <= latestCheck.eventNumber) {
                    e = traverse.get(tid);
                    eTid = tid;
                }
            } else if (latestCheck == null) {
                if (e.eventNumber <= traverse.get(tid).eventNumber) {
                    e = traverse.get(tid);
                    eTid = tid;
                }
            } else if (e.eventNumber <= traverse.get(tid).eventNumber && traverse.get(tid).eventNumber <= latestCheck.eventNumber) {
                e = traverse.get(tid);
                eTid = tid;
            }
        }
		if (e != null) attemptedEvents.add(eTid);		
		return e;
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

	private static boolean backReorderStuck(HashSetVector<EventNode> trPrime, int white, int black, HashMap<Integer, EventNode> traverse, HashSet<AcqRelNode> heldLocks, EventNode latestCheck, HashSet<Integer> attemptedEvents) {
	    if (!PRINT_FAILED_REORDERING) return false;
		Util.log("black: " + black + " , white: " + white);
		Util.log("trPrime so far: ");
		for (EventNode node : trPrime) {
            Util.log(node.getNodeLabel() + " | eventNumber: " + node.eventNumber + " | surroundingCS: " + node.inCS + " | myLatestTraversal: " + node.myLatestTraversal);
        }
		Util.log("BackReorder Set Getting Stuck: latestCheck: " + latestCheck + " | eventNumber: " + latestCheck.eventNumber + " | surroundingCS: " + latestCheck.inCS);
		Util.log("attempted events at this point:");
		for (Integer tid : attemptedEvents) {
			EventNode e = traverse.get(tid);
			Util.log("attempted " + e.getNodeLabel() + " | eventNumber: " + e.eventNumber);
		}
		for (int eTid : traverse.keySet()) {
            boolean outgoing_edge_check = false;
            EventNode eCheck = traverse.get(eTid);
            for (EventNode ePrime : eCheck.sinkOrSinks) {
                if (ePrime.myLatestTraversal >= white && ePrime.myLatestTraversal != black) {
                    outgoing_edge_check = true; //true is bad
                }
            }
            Util.log(traverse.get(eTid).getNodeLabel() + " | eventNumber: " + traverse.get(eTid).eventNumber + " | surroundingCS: " + traverse.get(eTid).inCS + " | myLatestTraversal: " + traverse.get(eTid).myLatestTraversal + " | could not be added due to sink node: " + outgoing_edge_check);
            if (outgoing_edge_check) {
                for (EventNode ePrime : eCheck.sinkOrSinks) {
                    if (ePrime.myLatestTraversal >= white) {
                        Util.log("--sink--> " + ePrime.getNodeLabel() + " | eventNumber: " + ePrime.eventNumber + " | surroundingCS: " + ePrime.inCS);
                        for (EventNode ePrimePrime : ePrime.sinkOrSinks) {
                            if (ePrimePrime.myLatestTraversal >= white) {
                                Util.log("--sink--of sink--> " + ePrimePrime.getNodeLabel() + " | eventNumber: " + ePrimePrime.eventNumber + " | surroundingCS: " + ePrimePrime.inCS);
                            }
                        }
                    }
                }
            }
        }
		Util.log("heldLocks:");
		for (AcqRelNode eAcq : heldLocks) {
            Util.log(eAcq.getNodeLabel());
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
							node.myLatestTraversal = traversal;
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
							node.myLatestTraversal = traversal;
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
										Util.println("Found rel->rel that needs Rule B edge: " + shadowLock + ", " + acqNode.otherCriticalSectionNode + "->" + relNode + " | " + acqNode.otherCriticalSectionNode.getExampleNumber() + "->" + relNode.getExampleNumber());
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
	
	static boolean bfsTraversal(EventNode startingNode, EventNode firstNode, EventNode secondNode, long windowMin, long windowMax) {
		ArrayDeque<EventNode> WDCBGraph = new ArrayDeque<>();
		int black = EventNode.prepUniqueTraversal(2);
		int gray = black - 1;
		
		WDCBGraph.add(startingNode);
		
		startingNode.myLatestTraversal = gray;
		
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
							sinkOrSource.myLatestTraversal = gray;
						}
					}
				}
				node.myLatestTraversal = black;
				if (VERBOSE_GRAPH) Assert.assertTrue(node.eventNumber > -2);
			}
		}
		return false;
	}


    static Map<ShadowID, Map<Integer/*thread id*/, RdWrNode>> findFirstWriteOfVarsPerThread(Collection<EventNode> start) {
		int visited = prepUniqueTraversal();
    	Vector<EventNode> unvisited = new Vector<>(start);
		Map<ShadowID, Map<Integer, RdWrNode>> writeOfVarPerThread = new HashMap<>();
		while (!unvisited.isEmpty()) {
			EventNode current = unvisited.remove(unvisited.size() - 1);
			if (current.myLatestTraversal != visited) {
				current.myLatestTraversal = visited;
				Util.addAll(unvisited, current.sourceOrSources);
				if (current instanceof RdWrNode) {
					RdWrNode currentAcc =(RdWrNode) current;
					if (currentAcc.isWrite()) {
						Map<Integer, RdWrNode> firstWritePerThread = writeOfVarPerThread.computeIfAbsent(currentAcc.var, v -> new HashMap<>());
						firstWritePerThread.merge(currentAcc.getThreadId(), currentAcc, (l, r) -> (l.eventNumber < r.eventNumber) ? l : r);
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
	static Set<RdWrNode> findCoreReads(EventNode first, EventNode second) {
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

		unvisited.add(second);
		unvisited.add(first); // With no graph edges, we also need to traverse backwards from the first event.
		while (!unvisited.isEmpty()) {
			EventNode current = unvisited.poll();
			if (current.myLatestTraversal != visited) {
				current.myLatestTraversal = visited;
				Util.addAll(unvisited, current.sourceOrSources);

				if (current instanceof BranchNode) {
					for (RdWrNode read : ((BranchNode) current).dependsOn) {
						// If we see a branch, all dependencies of the branch are CORE reads
						coreReads.add(read);
						// And the dependent writers of those reads are CORE writes
						if (read.hasLastWriter()) {
							RdWrNode w = read.lastWriter();
							coreWrites.add(w);
							unvisited.add(w); // The write may be unreachable via event graph (e.g. transitive CORE write)
						}
					}
				}

				if (current instanceof RdWrNode) {
					final RdWrNode access = (RdWrNode) current;
					if (!isCoreYet.contains(access.threadID) && coreWrites.contains(access)) {
						// Mark that we have seen a CORE write by this thread already.
						// Reads don't matter, because only CORE writes make everything PO ordered to them CORE events
						isCoreYet.add(access.threadID);
					} else if (isCoreYet.contains(access.threadID)) {
						// Any event PO ordered before a CORE event is also a CORE event. We have seen a CORE event for
						// this thread, so this event must be a CORE event as well. We are sure that we have already
						// seen all events PO ordered after the current one, because the PriorityQueue orders events
						// by event number, largest event number first.
						if (access.isWrite()) {
						    coreWrites.add(access);
                        } else {
						    coreReads.add(access);
						    if (access.hasLastWriter()) {
						        coreWrites.add(access.lastWriter());
						        unvisited.add(access.lastWriter());
                            }
                        }

						if (access == first) {
							// We have seen a CORE write PO ordered after first event in race pair,
							// which means there is a chain of CORE wr-rd edges leading from first to second.
							// Any reordering where they race will violate LW.
							Util.log("Found CORE path between race events, guaranteed to not be a race.");
							return null;
						}
					} else {
						nonCoreAccess++;
					}
				}
			}
		}
		Util.logf("Non-CORE reads and writes found: %d", nonCoreAccess);
		return coreReads;
	}


	// Finds event node that is PO ordered before c
	static EventNode priorPO(EventNode c) {
		for (EventNode p : c.sourceOrSources) {
			if (p.threadID == c.threadID) {
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
		acqNode.myLatestTraversal = gray;
		
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
							sinkNode.myLatestTraversal = gray;
						}
					}
				}
				node.myLatestTraversal = black;
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
			ARNode.myLatestTraversal = gray;
		} else { //validate from the acquire of the backedge with an earlier event number
			grayNodes.add(ARNode.otherCriticalSectionNode);
			ARNode.otherCriticalSectionNode.myLatestTraversal = gray;
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
							sinkNode.myLatestTraversal = gray;
						}
					}
				}
				node.myLatestTraversal = black;
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
					node.myLatestTraversal = gray;
					for (EventNode sourceNode : node.sourceOrSources) {
						if (sourceNode.myLatestTraversal != black) {
							WDCBGraph.add(sourceNode);
						}
					}
					node.myLatestTraversal = black;
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
				node.myLatestTraversal = gray;
				for (EventNode predOrSucc : (isForward ? node.sinkOrSinks : node.sourceOrSources)) {
					boolean cycleDetected = dfsDetectCycle(predOrSucc, isForward, gray, black, windowMin, windowMax);
					if (cycleDetected) {
						return true;
					}
				}
				node.myLatestTraversal = black;
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
				node.myLatestTraversal = gray;
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
			node.myLatestTraversal = black;
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
							currentTrace.get(currentTrace.size() - 1).node.myLatestTraversal = black;
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
	
	static void printReordering(HashSetVector<EventNode> trPrime, RdWrNode firstNode, RdWrNode secondNode, File commandDir) {
		try {
			PrintWriter input = new PrintWriter(commandDir+"/wdc_race_" + firstNode.eventNumber + "_" + secondNode.eventNumber);
			for (EventNode trPrimeEvent : trPrime) {
				if (trPrimeEvent instanceof AcqRelNode) {
					AcqRelNode ARPrimeEvent = (AcqRelNode)trPrimeEvent;
					String ARPrimeName = Util.objectToIdentityString(ARPrimeEvent.shadowLock.getLock());
					input.println("T" + ARPrimeEvent.threadID + ":" + (ARPrimeEvent.isAcquire() ? "acq(" : "rel(") + ARPrimeName + "):" 
									+ ARPrimeEvent.eventNumber + "|" + ARPrimeEvent.getExampleNumber());
				} else if (trPrimeEvent instanceof RdWrNode) {
					RdWrNode RWPrimeEvent = (RdWrNode)trPrimeEvent;
					input.println("T" + trPrimeEvent.threadID + ":" + (RWPrimeEvent.isWrite() ? "wr(" : "rd(") + RWPrimeEvent.getFieldName() + "):"
									+ RWPrimeEvent.eventNumber + "|" + RWPrimeEvent.getExampleNumber());
				} else {
					input.println("T" + trPrimeEvent.threadID + ":" + trPrimeEvent.eventNumber + "|" + trPrimeEvent.getExampleNumber());
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
	
		    Map<Integer, List<EventNode>> threadIdToThreadEvents = new HashMap<>();
		    Iterator it = edges.entrySet().iterator();
		    while (it.hasNext()) {
		    	Entry pair = (Entry)it.next();
		    	addEventToItsThreadList(threadIdToThreadEvents, (EventNode)pair.getKey());
		        for (EventNode eventNode : (List<EventNode>)pair.getValue()) {
		        	addEventToItsThreadList(threadIdToThreadEvents, eventNode);
		        }
		    }

		    it = threadIdToThreadEvents.entrySet().iterator();
		    while (it.hasNext()) {
		    	Entry pair = (Entry)it.next();
		    	printWriter.println("subgraph Cluster_" + (int)pair.getKey() + " {");
		    	printWriter.println("label = T" + (int)pair.getKey());
		        for (EventNode eventNode : (List<EventNode>)pair.getValue()) {
		        	String nodeLabel = eventNode.getNodeLabel() + "- #" + eventNode.eventNumber;
		        	printWriter.println( "node" + eventNode.eventNumber + " [label = \"" + nodeLabel + "\"];");
		        }
		        printWriter.println("}");
		    }

		    it = edges.entrySet().iterator();
		    while (it.hasNext()) {
		        Entry pair = (Entry)it.next();
		        for (EventNode child : (List<EventNode>) pair.getValue()) {
		        	long keyEventNumber = ((EventNode)pair.getKey()).eventNumber;
		        	long tail = keyEventNumber;		        
		        	long head = child.eventNumber;
		        	if ( keyEventNumber > child.eventNumber ) {
		        		tail = child.eventNumber;
		        		head = keyEventNumber;
		        	}
			        printWriter.println("\"node" + tail + "\" -> \"node" + head + "\"" + 
		        	(keyEventNumber > child.eventNumber ? " [dir=\"back\"]" : "") + ";");
		        }
		    }
		    
//		    it = threadIdToThreadEvents.entrySet().iterator();
//		    while (it.hasNext()) {
//		    	printWriter.print("{rank=same");
//		    	Map.Entry pair = (Map.Entry)it.next();		        
//		        for (Long eventNumber : (List<Long>)pair.getValue()) {
//		        	printWriter.print(" node" + eventNumber);
//		        }
//		        printWriter.println("}");
//		        
//		    }

		    printWriter.println("}");
		    printWriter.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	

	private static void addEventToItsThreadList(Map<Integer, List<EventNode>> threadIdToThreadEvents, EventNode eventNode) {
		if (!threadIdToThreadEvents.containsKey(eventNode.getThreadId())) {
			threadIdToThreadEvents.put(eventNode.getThreadId(), new ArrayList<EventNode>());
		}
		threadIdToThreadEvents.get(eventNode.getThreadId()).add(eventNode);
	}
	
	public String getNodeLabel () {
		if (DEBUG_EXNUM_LABEL) {
			return nodeLabelMap.get(this);
		} else {
			return "?";
		}
	}
	
	public int getThreadId(){
		return this.threadID;
	}
	

	// Collect Latex output to examine events included in the cycles leading to at least one conflicting access of a WCP/WDC race.
	@Deprecated
	static void generateLatex(EventNode firstNode, EventNode secondNode, TreeMap<Long, EventNode> unorderedEvents, boolean secondCycleDetected, boolean firstCycleDetected) {
		Assert.assertTrue(DEBUG_ACCESS_INFO);
		if (secondCycleDetected || firstCycleDetected) {
			try {
				BufferedWriter collectLatex = new BufferedWriter(new FileWriter("latex_output/LatexTrace_"+firstNode.getExampleNumber()+"_"+secondNode.getExampleNumber()+".tex"));
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
						for (int i = 0; i < orderedRdWr.threadID; i++) {
							collectLatex.write("&");
						}
						// TODO: One event may have more than one write/read, this only makes sense if merging is disabled
//						collectLatex.write("wr(V"+orderedRdWr.getAccesses().firstElement().var.hashCode()+")");
						for (int i = orderedRdWr.threadID+1; i < ShadowThread.maxActiveThreads(); i++) {
							collectLatex.write("&");
						}
						collectLatex.write("\\\\\n");
					} else if (orderedEvent instanceof AcqRelNode) {
						AcqRelNode orderedAcqRel = (AcqRelNode) orderedEvent;
						if (orderedAcqRel.isAcquire) {
							for (int i = 0; i < orderedAcqRel.threadID; i++) {
								collectLatex.write("&");
							}
							collectLatex.write("acq(L"+orderedAcqRel.shadowLock.getLock().hashCode()+")");
							for (int i = orderedAcqRel.threadID+1; i < ShadowThread.maxActiveThreads(); i++) {
								collectLatex.write("&");
							}
							collectLatex.write("\\\\\n");
						} else {
							for (int i = 0; i < orderedAcqRel.threadID; i++) {
								collectLatex.write("&");
							}
							collectLatex.write("rel(L"+orderedAcqRel.shadowLock.getLock().hashCode()+")");
							for (int i = orderedAcqRel.threadID+1; i < ShadowThread.maxActiveThreads(); i++) {
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
		return String.format("%s#%d", getNodeLabel(), eventNumber);
	}
	
	static void addEventToThreadToItsFirstEventsMap(EventNode eventNode) {
		if (VERBOSE_GRAPH) Assert.assertTrue(eventNode.eventNumber >= 0 || ShadowThread.get(eventNode.threadID).getThread().getName().equals("Finalizer"));
		if ( !threadToFirstEventMap.containsKey(eventNode.threadID) ) {
			threadToFirstEventMap.put(eventNode.threadID, eventNode);
		} else {
			if ( threadToFirstEventMap.get(eventNode.threadID).eventNumber > eventNode.eventNumber ) {
				threadToFirstEventMap.put(eventNode.threadID, eventNode);
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
				if ( (sink.threadID == nextTraceEvent.threadID) && 
						(nextThreadEvent == null || (nextTraceEvent.eventNumber < sink.eventNumber && sink.eventNumber < nextThreadEvent.eventNumber)) ) {
					nextThreadEvent = sink;
				}
			}
			threadToItsNextEventMap.put(nextTraceEvent.threadID, nextThreadEvent);
		}		
		return nextTraceEvent;
	}


	private static boolean forwardVerifyReorderedTrace(Collection<EventNode> trPrime, EventNode first, EventNode second) {
		Map<Integer, EventNode> lastEvent = new HashMap<>(); // Maps threads to the last event by them
		Map<ShadowLock, Integer> lockHeldBy = new HashMap<>(); // Maps locks to threads holding them
		Map<ShadowID, RdWrNode> lastWrites = new HashMap<>(); // Maps variables to their last writers
		Map<Integer, Map<RdWrNode, RdWrNode>> potentialLWViolations = new HashMap<>(); // Maps threads to their reads that may violate LW
		
		for (EventNode event : trPrime) {
			// PO
			EventNode last = lastEvent.get(event.threadID);
			if (last != null) {
				if (last.eventNumber >= event.eventNumber) {
					if (VERBOSE_GRAPH) {
						Assert.assertFalse(event.equals(last), "Last event " + last + " is same as current event " + event);
						Assert.assertTrue(event.threadID == last.threadID, "Event in wrong last event map for PO check");
					}
					Util.log("PO ordering violated in the reordered trace by events " + last + " and " + event + " by T" + event.threadID);
					return false;
				}
				if (!isPreviousEvent(event, last)) {
					return false;
				}
			}
			lastEvent.put(event.threadID, event);

			// LS
			if (event instanceof AcqRelNode) {
			AcqRelNode syncEvent = (AcqRelNode) event;
				ShadowLock sl = syncEvent.shadowLock;
				if (syncEvent.isAcquire) {
					if (lockHeldBy.containsKey(sl)) {
						Util.log("Lock semantics violated in the reordered trace, T" + event.threadID
								+ " is trying to acquire lock " + Util.objectToIdentityString(sl.getLock())
								+ " which is already held by T" + lockHeldBy.get(sl));
						return false;
					}
					lockHeldBy.put(sl, event.threadID);
				} else { // syncEvent is release
					if (!lockHeldBy.containsKey(sl)) {
						Util.log("Lock semantics violated in the reordered trace, T" + event.threadID
								+ " is trying to release lock " + Util.objectToIdentityString(sl.getLock())
								+ " which it is not holding!");
						return false;
					}
					lockHeldBy.remove(sl);
				}
			}

			// LW
			if (event instanceof BranchNode) {
				BranchNode brEvent = (BranchNode) event;
				for (RdWrNode depRead : brEvent.dependsOn) {
					// Any read this branch depends on is a CORE read, check them for LW violations
					Map<RdWrNode,RdWrNode> potLW = potentialLWViolations.get(depRead.getThreadId());
					if (potLW != null && potLW.containsKey(depRead)) {
						Util.log("Last writer violated in the reordered trace, T" + event.threadID
								+ " has read " + depRead
								+ " has " + (depRead.hasLastWriter() ? " last writer " + depRead.lastWriter() : " no last writer")
								+ " but saw the last write " + potLW.get(depRead)
						);
						return false;
					} else if (potLW != null) {
						potLW.remove(depRead);
					}
					// The last writer of a CORE read is a CORE write
					if (depRead.hasLastWriter()) {
						RdWrNode depReadLastWriter = depRead.lastWriter();
						Map<RdWrNode,RdWrNode> POpotLW = potentialLWViolations.get(depReadLastWriter.getThreadId());
						if (POpotLW != null) {
							for (RdWrNode poRead : POpotLW.keySet()) {
								//  Any read PO ordered before a CORE write is a CORE read
								if (poRead.eventNumber < depReadLastWriter.eventNumber) {
									Util.log("Last writer violated in the reordered trace for transitive CORE read, T" + event.threadID
											 + " has read " + poRead + " with traversal " + poRead.myLatestTraversal + " "
											 + " and it has " + (poRead.hasLastWriter() ? " last writer " + poRead.lastWriter() + " with traversal " + poRead.lastWriter().myLatestTraversal : " no last writer")
											 + " but saw the last write " + potLW.get(poRead)
									);
									if (VERBOSE_GRAPH) {
										boolean coreEdge = containsNode(poRead.lastWriter().sinkOrSinks, poRead);
										if (coreEdge) {
											Util.log("Somehow ignored CORE LW edge during reordering?! to " + poRead + " from " + poRead.lastWriter());
											if (!trPrime.contains(poRead.lastWriter())) {
												Util.log("Somehow last writer never got added to the trace");
											}
										}
										if (first.equals(poRead.lastWriter()) || second.equals(poRead))  {
										   Util.log("We are checking last writer for race event???");
										}
									}
									return false;
								}
							}
						}
					}
				}
			}
			if (event instanceof RdWrNode) {
				// Due to merging, each event contains multiple accesses
				RdWrDebugNode currEvent = (RdWrDebugNode) event;
				if (currEvent.isWrite) {
					lastWrites.put(currEvent.var, currEvent);
				} else { // is read
					if (/* If there is a write reordered before a read that has no last writer */
							currEvent.hasNoLastWriter() && lastWrites.containsKey(currEvent.var)
									/* or the last writer for a read has changed */
									|| currEvent.hasLastWriter() && !currEvent.lastWriter().equals(lastWrites.get(currEvent.var))) {
						// This read may be a LW violation if it is a CORE read
						potentialLWViolations.computeIfAbsent(currEvent.getThreadId(), (tid) -> new HashMap<>()).put(currEvent, lastWrites.get(currEvent.var));
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
            if (event.threadID == currSource.threadID) {
                prevPO = currSource;
                break;
            }
        }
		if (prevPO == null) {
			Util.log("Event is no the first event by the thread, but doesn't have any PO edges to previous events");
			return false;
		}
		if (prevPO.eventNumber != prev.eventNumber) {
            Util.log("Event " + prevPO.eventNumber + " by T" + prevPO.threadID + " is missing from reordered trace");
            return false;
        }
        return true;
	}
}


