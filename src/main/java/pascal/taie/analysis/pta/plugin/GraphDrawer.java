package pascal.taie.analysis.pta.plugin;


import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.core.solver.Solver;

public class GraphDrawer {

    private Solver solver;

    public void setSolver(Solver solver) {
        this.solver = solver;
    }

    public void onFinish() {
        process(solver.getResult());
    }

    public static void process(PointerAnalysisResult result) {

    }
}
