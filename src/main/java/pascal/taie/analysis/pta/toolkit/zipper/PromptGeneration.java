package pascal.taie.analysis.pta.toolkit.zipper;

import dev.langchain4j.model.input.PromptTemplate;

import java.util.Map;

public class PromptGeneration {
    private final static String TEMPLATE_SIMPLE = """
            # Efficiency Critical Method Selection Method

            ## Your Role
            You are an senior researcher in static program analysis specialized in Java pointer analysis.

            ## Task Requirements
            In context-sensitive pointer analysis, we can apply selective context-sensitive strategy to accelerate
            the analysis and improve its scalability. Our tool conduct a context-insensitive pre-analysis to
            select a set of precision-critical methods and apply context-sensitivity to them. The precision-critical
            methods are the methods whose context-sensitive analysis tends to improve overall pointer analysis precision.
            Our tool also select a set of efficiency-critical methods from precision-critical methods. Efficiency-critical
            methods are the methods that cost more time and memory than normal methods when analyzed context-sensitively.
            Your task is to analyze the efficiency-critical methods selected by the pre-analysis, and determine whether
            they require context-sensitive analysis. Note that you should balance precision and efficiency, which means
            you should only select methods that are likely to benefit significantly from context-sensitive analysis.
            You will receive a set of efficiency-critical methods for a specific class, they should be analyzed together
            context-sensitively/context-insensitively.

            ## Input Parameters
            You will receive a set of efficiency-critical methods for class {{class}}
            **Efficiency Critical Methods**
            Method signature and method body
            {{ecm}}
            """;

    private final static String TEMPLATE_PFG = """
            # Efficiency Critical Method Selection Task

            ## Your Role
            You are an senior researcher in static program analysis specialized in Java pointer analysis.

            ## Task Requirements
            In context-sensitive pointer analysis, we can apply selective context-sensitive strategy to accelerate
            the analysis and improve its scalability. Our tool conduct a context-insensitive pre-analysis to
            select a set of precision-critical methods and apply context-sensitivity to them. The precision-critical
            methods are the methods whose context-sensitive analysis tends to improve overall pointer analysis precision.
            Our tool also select a set of efficiency-critical methods from precision-critical methods. Efficiency-critical
            methods are the methods that cost more time and memory than normal methods when analyzed context-sensitively.
            Your task is to analyze the efficiency-critical methods selected by the pre-analysis, and determine whether
            they require context-sensitive analysis. Note that you should balance precision and efficiency, which means
            you should only select methods that are likely to benefit significantly from context-sensitive analysis.


            ## Background Knowledge
            **Precision-Loss Patterns**
            Most imprecision in context-insensitive analysis comes from three general patterns of value flows.
            Direct Flow: An object flows directly from an In method parameter to an Out method return.
            Wrapped Flow: An object is stored("wrapped") into another object, which then flows to an Out method.
            Unwrapped Flow: An object is extracted("unwrapped") from another object, which then flows to an Out method.
            These patterns and their combinations form the theoretical basis for detecting precison loss.

            **Object Flow Graph(OFG)**
            A graph where nodes are program variables and fields, and edges represent how objects may flow between them.
            Built using results from a fast context-insensitive pre-analysis.
            OFG can naturally capture direct flows but not wrapped/unwrapped ones.

            **Precision Flow Graph**
            An extension of OFG to uniformly capture direct, wrapped, and unwrapped flows.
            Each class in the program has its own PFG.
            Constructed by augmenting OFG with special edges for wrapping/unwrapping operations.
            Example: If a is stored in b.f, an edge from a to the abstract object of b is added (for wrapped flow).

            **Identifying Precision-Critical Methods**
            The algorithm builds PFGs and reduces the problem to a graph reachability question: Does a path exist from
            an In method parameter node to an Out method return node in the PFG?
            If yes, all methods along that flow are considered precision-critical.
            These methods are then analyzed context-sensitively, while others are kept context-insensitive.

            **Efficiency Consideration**
            Some methods are both precision-critical and very expensive to analyze context-sensitively.
            Excluding efficiency-critical methods:
            For each class, compute the cumulative size of its points-to sets (#pts_c).
            If #pts_c is disproportionately large (above a threshold % of total program points-to size), those methods are excluded.

            ## Input Parameters
            You will receive a subgraph of precision flow graph for each class that is identified as efficiency-critical, which contains
            the nodes relevant to the precision-loss flows, these flows are separated by return node, each time you will receive the flows
            relevant to a single return node.
            The input format is a node index followed by a colon to indicate its successor nodes.

            You will receive the subgraph for class {{class}}
            **Precision Critical Flow**
            graph structure
            {{input}}
            """;

    private final static String TEMPLATE_IR = """
            # Efficiency Critical Method Selection Task

            ## Your Role
            You are a senior researcher in static program analysis specialized in Java pointer analysis.

            ## Task Requirements
            In context-sensitive pointer analysis, we can apply selective context-sensitive strategy to accelerate
            the analysis and improve its scalability. Our tool conduct a context-insensitive pre-analysis to
            select a set of precision-critical methods and apply context-sensitivity to them. The precision-critical
            methods are the methods whose context-sensitive analysis tends to improve overall pointer analysis precision.
            Our tool also select a set of efficiency-critical methods from precision-critical methods. Efficiency-critical
            methods are the methods that cost more time and memory than normal methods when analyzed context-sensitively.
            Your task is to analyze the precision-critical methods selected by the pre-analysis, and identify the possible
            efficiency-critical methods among them. Note that you should balance precision and efficiency, which means
            you should only select methods that are likely to cost much if applied context-sensitive analysis.

            ## Input Parameters
            You will receive the IR of a method that is identified as precision critical.

            You will receive the IR of one of the precision critical methods for class {{class}}
            **Precision Critical Method's IR**
            IR
            {{input}}
            """;

    private final static String TEMPLATE_OUTPUT = """
            ## Output Format
            The answer should be "YES" or "NO", which means the flows should or should not be analyzed context sensitively.
            The output should follow the format below:

            <FINAL_ANSWER>
            YOUR_FINAL_ANSWER_HERE(YES/NO)
            </FINAL_ANSWER>
            """;


    public static String generate(String input, String type) {
        String template = TEMPLATE_IR + "\n" + TEMPLATE_OUTPUT;
        PromptTemplate promptTemplate = PromptTemplate.from(template);

        Map<String, Object> contextMap = Map.of("class", type, "input", input);
//        System.out.println(promptTemplate.apply(contextMap).text());
        return promptTemplate.apply(contextMap).text();
    }



}
