/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2022 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2022 Yue Li <yueli@nju.edu.cn>
 *
 * This file is part of Tai-e.
 *
 * Tai-e is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Tai-e is distributed in the hope that it will be useful,but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Tai-e. If not, see <https://www.gnu.org/licenses/>.
 */

package pascal.taie.analysis.pta.toolkit.zipper;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.graph.flowgraph.InstanceNode;
import pascal.taie.analysis.graph.flowgraph.Node;
import pascal.taie.analysis.graph.flowgraph.ObjectFlowGraph;
import pascal.taie.analysis.graph.flowgraph.VarNode;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.toolkit.PointerAnalysisResultEx;
import pascal.taie.analysis.pta.toolkit.PointerAnalysisResultExImpl;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.New;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.Type;
import pascal.taie.util.MutableInt;
import pascal.taie.util.Timer;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.Pair;
import pascal.taie.util.collection.Sets;
import pascal.taie.util.graph.Graph;
import pascal.taie.util.graph.SimpleGraph;

import guru.nidi.graphviz.attribute.*;
import guru.nidi.graphviz.engine.*;
import guru.nidi.graphviz.model.*;
import static guru.nidi.graphviz.model.Factory.*;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class Zipper {

    private static final Logger logger = LogManager.getLogger(Zipper.class);

    private static final float DEFAULT_PV = 0.05f;

    private final PointerAnalysisResultEx pta;

    private final boolean isExpress;

    /**
     * Percentage value, i.e., the threshold for Zipper-e.
     */
    private final float pv;

    private final ObjectAllocationGraph oag;

    private final PotentialContextElement pce;

    private final ObjectFlowGraph ofg;

    private AtomicInteger totalPFGNodes;

    private AtomicInteger totalPFGEdges;

    private Map<Type, Collection<JMethod>> pcmMap;

    private int pcmThreshold;

    private Map<JMethod, MutableInt> methodPts;

    private final boolean isLLM;

    private static final String ECM_IR = "ecm.txt";

    private static AtomicInteger totalPCM = new AtomicInteger(0);

    private Map<Type, Collection<JMethod>> pcmMapOld;

    private static Integer pngCount = 0;

    /**
     * Parses Zipper argument and runs Zipper.
     */
    public static Set<JMethod> run(PointerAnalysisResult pta, String arg) {
        boolean isExpress;
        boolean isLLM;
        float pv;
        if (arg.equals("zipper")) {
            isExpress = false;
            isLLM = false;
            pv = 1;
        } else if (arg.equals("zipper-e")) {
            isExpress = true;
            isLLM = false;
            pv = DEFAULT_PV;
        } else if (arg.startsWith("zipper-e=")) { // zipper-e=pv
            isExpress = true;
            isLLM = false;
            pv = Float.parseFloat(arg.split("=")[1]);
        } else if (arg.startsWith("l4p")) {
            isExpress = true;
            isLLM = true;
            pv = DEFAULT_PV;
        } else {
            throw new IllegalArgumentException("Illegal Zipper argument: " + arg);
        }
        return new Zipper(pta, isExpress, pv, isLLM)
                .selectPrecisionCriticalMethods();
    }

    public static Pair<Map<Type, Collection<JMethod>>, Map<Type, Collection<JMethod>>> run_test(PointerAnalysisResult pta, String arg) {
        boolean isExpress;
        boolean isLLM;
        float pv;
        isExpress = true;
        isLLM = false;
        pv = DEFAULT_PV;
        return new Zipper(pta, isExpress, pv, isLLM)
                .selectPrecisionCriticalMethodsSets();
    }

    public Zipper(PointerAnalysisResult ptaBase, boolean isExpress, float pv, boolean isLLM) {
        this.pta = new PointerAnalysisResultExImpl(ptaBase, true);
        this.isExpress = isExpress;
        this.pv = pv;
        this.isLLM = isLLM;
        this.oag = Timer.runAndCount(() -> new ObjectAllocationGraph(pta),
                "Building OAG", Level.INFO);
        this.pce = Timer.runAndCount(() -> new PotentialContextElement(pta, oag),
                "Building PCE", Level.INFO);
        this.ofg = ptaBase.getObjectFlowGraph();
        logger.info("{} nodes in OFG", ofg.getNodes().size());
        logger.info("{} edges in OFG",
                ofg.getNodes().stream().mapToInt(ofg::getOutDegreeOf).sum());
    }

    /**
     * @return a set of precision-critical methods that should be analyzed
     * context-sensitively.
     */
    public Set<JMethod> selectPrecisionCriticalMethods() {
        totalPFGNodes = new AtomicInteger(0);
        totalPFGEdges = new AtomicInteger(0);
        pcmMap = Maps.newConcurrentMap(1024);
        pcmMapOld = Maps.newConcurrentMap(1024);

        // prepare information for Zipper-e
        if (isExpress) {
            PointerAnalysisResult pta = this.pta.getBase();
            int totalPts = 0;
            methodPts = Maps.newMap(pta.getCallGraph().getNumberOfMethods());
            for (Var var : pta.getVars()) {
                int size = pta.getPointsToSet(var).size();
                if (size > 0) {
                    totalPts += size;
                    methodPts.computeIfAbsent(var.getMethod(),
                                    __ -> new MutableInt(0))
                            .add(size);
                }
            }
            pcmThreshold = (int) (pv * totalPts);
        }

        // build and analyze precision-flow graphs
        Set<Type> types = pta.getObjectTypes();
        Timer.runAndCount(() -> types.stream().forEach(this::analyze),
                "Building and analyzing PFG", Level.INFO);
        logger.info("#types: {}", types.size());
        logger.info("#avg. nodes in PFG: {}", totalPFGNodes.get() / types.size());
        logger.info("#avg. edges in PFG: {}", totalPFGEdges.get() / types.size());
        logger.info("total types: {}", totalPCM.get());

        // collect all precision-critical methods
        Set<JMethod> pcms = pcmMap.values()
                .stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toUnmodifiableSet());
        logger.info("#precision-critical methods: {}", pcms.size());
        return pcms;
    }

    public Pair<Map<Type, Collection<JMethod>>, Map<Type, Collection<JMethod>>> selectPrecisionCriticalMethodsSets() {
        totalPFGNodes = new AtomicInteger(0);
        totalPFGEdges = new AtomicInteger(0);
        pcmMap = Maps.newConcurrentMap(1024);
        pcmMapOld = Maps.newConcurrentMap(1024);

        // prepare information for Zipper-e
        if (isExpress) {
            PointerAnalysisResult pta = this.pta.getBase();
            int totalPts = 0;
            methodPts = Maps.newMap(pta.getCallGraph().getNumberOfMethods());
            for (Var var : pta.getVars()) {
                int size = pta.getPointsToSet(var).size();
                if (size > 0) {
                    totalPts += size;
                    methodPts.computeIfAbsent(var.getMethod(),
                                    __ -> new MutableInt(0))
                            .add(size);
                }
            }
            pcmThreshold = (int) (pv * totalPts);
        }

        // build and analyze precision-flow graphs
        Set<Type> types = pta.getObjectTypes();
        Timer.runAndCount(() -> types.parallelStream().forEach(this::analyze),
                "Building and analyzing PFG", Level.INFO);
        logger.info("#types: {}", types.size());
        logger.info("#avg. nodes in PFG: {}", totalPFGNodes.get() / types.size());
        logger.info("#avg. edges in PFG: {}", totalPFGEdges.get() / types.size());

        // collect all precision-critical methods
        Set<JMethod> pcms = pcmMap.values()
                .stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toUnmodifiableSet());
        logger.info("#precision-critical methods: {}", pcms.size());
        return new Pair<>(pcmMapOld, pcmMap);
    }

    private void analyze(Type type) {
        PrecisionFlowGraph pfg = new PFGBuilder(pta, ofg, oag, pce, type).build();
        totalPFGNodes.addAndGet(pfg.getNumberOfNodes());
        totalPFGEdges.addAndGet(pfg.getNodes()
                .stream()
                .mapToInt(pfg::getOutDegreeOf)
                .sum());
        Set<JMethod> pcms = getPrecisionCriticalMethods(pfg);
        // getPFGFlow(pfg);
        if (!pcms.isEmpty()) {
            pcmMap.put(type, pcms);
            totalPCM.addAndGet(1);
        }
    }

    private Set<JMethod> getPrecisionCriticalMethods(PrecisionFlowGraph pfg) {
        Set<JMethod> pcms = getFlowNodes(pfg)
                .stream()
                .map(Zipper::node2Method)
                .filter(Objects::nonNull)
                .filter(pce.pceMethodsOf(pfg.getType())::contains)
                .collect(Collectors.toUnmodifiableSet());
        if (isLLM) {
//            Set<JMethod> new_pcms = new HashSet<>();
//            for (JMethod m : pcms) {
//                StringBuilder sb = new StringBuilder();
//                for (Stmt stmt : m.getIR().getStmts()) {
//                    sb.append(stmt.toString());
//                }
//                String ir = sb.toString();
//                String result = LLMInteraction.query(ir, pfg.getType().getName());
//                if(result.equals("YES")) {
//                    new_pcms.add(m);
//                }
//            }
//            pcms = new_pcms;


        }
        if (!pcms.isEmpty()) {
            pcmMapOld.put(pfg.getType(), pcms);
        }

        if (isExpress && !isLLM) {
            int accPts = 0;
            for (JMethod m : pcms) {
                MutableInt mPtsSize = methodPts.get(m);
                if (mPtsSize != null) {
                    accPts += mPtsSize.intValue();
                }
            }
            if (accPts > pcmThreshold) {
                // clear precision-critical method group whose accumulative
                // points-to size exceeds the threshold
                pcms = Set.of();
            }
        }
        return pcms;
    }

    private static Set<Node> getFlowNodes(PrecisionFlowGraph pfg) {
        Set<Node> visited = Sets.newSet();
        for (VarNode outNode : pfg.getOutNodes()) {
            Deque<Node> workList = new ArrayDeque<>();
            workList.add(outNode);
            while (!workList.isEmpty()) {
                Node node = workList.poll();
                if (visited.add(node)) {
                    pfg.getPredsOf(node)
                            .stream()
                            .filter(Predicate.not(visited::contains))
                            .forEach(workList::add);
                }
            }
        }
        return visited;
    }

    /**
     * @return containing method of {@code node}.
     */
    @Nullable
    private static JMethod node2Method(Node node) {
        if (node instanceof VarNode varNode) {
            return varNode.getVar().getMethod();
        } else {
            Obj base = ((InstanceNode) node).getBase();
            if (base.getAllocation() instanceof New newStmt) {
                return newStmt.getContainer();
            }
        }
        return null;
    }

    private static HashMap<Node, Graph<Node>> getPFGFlows(PrecisionFlowGraph pfg) {
        HashMap<Node, Graph<Node>> ret2flows = new HashMap<>();

        Set<Node> visited = Sets.newSet();
        for (VarNode outNode : pfg.getOutNodes()) {
            SimpleGraph<Node> flows = new SimpleGraph<>();
            Deque<Node> workList = new ArrayDeque<>();
            workList.add(outNode);
            while (!workList.isEmpty()) {
                Node node = workList.poll();
                if (visited.add(node)) {
                    flows.addNode(node);
                    pfg.getPredsOf(node)
                            .stream()
                            .filter(Predicate.not(visited::contains))
                            .forEach(pred -> {
                                workList.add(pred);
                                flows.addNode(pred);
                                flows.addEdge(pred, node);
                            });
                }
            }
            if (!flows.getNodes().isEmpty())
                ret2flows.put(outNode, flows);
        }
        return ret2flows;

    }

    private static Graph<Node> getPFGFlow(PrecisionFlowGraph pfg) {
        Set<Node> visited = Sets.newSet();
        SimpleGraph<Node> flows = new SimpleGraph<>();
        for (VarNode outNode : pfg.getOutNodes()) {
            Deque<Node> workList = new ArrayDeque<>();
            workList.add(outNode);
            while (!workList.isEmpty()) {
                Node node = workList.poll();
                pfg.getPredsOf(node).forEach(pred -> {
                    flows.addNode(pred);
                    flows.addEdge(pred, node);
                    if (visited.add(pred)) {
                        workList.add(pred);
                    }
                });
            }
        }

        MutableGraph graph = mutGraph("zipper-e").setDirected(true);

        Set<Node> nodes = flows.getNodes();

        MutableNode[] gvNodes = new MutableNode[nodes.size()];
        int idx = 0;
        for (Node n : nodes) {
            gvNodes[idx] = mutNode(String.valueOf(n));
            graph.add(gvNodes[idx]);
            idx++;
        }

        String fileCount = pngCount.toString();
        File outFile = new File(World.get().getOptions().getOutputDir(), "jython" + fileCount + ".png");
        for (Node from : nodes) {
            for (Node to : flows.getSuccsOf(from)) {
                gvNodes[indexOf(nodes, from)].addLink(gvNodes[indexOf(nodes, to)]);
            }
        }

        if (gvNodes.length < 15) {
            logger.info("Drawing zipper-e png......");
            try {
                Graphviz.fromGraph(graph).width(2000).render(Format.PNG).toFile(outFile);
            } catch (IOException e) {
                logger.info("no such file.");
            }
            logger.info("Drawing zipper-e png done.");
            pngCount++;
        }

        return flows;
    }

    private static int indexOf(Set<Node> set, Node key) {
        int i = 0;
        for (Node n : set) {
            if (n.equals(key)) return i;
            i++;
        }
        throw new IllegalArgumentException("Node not in the set.");
    }



}
