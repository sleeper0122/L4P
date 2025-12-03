package pascal.taie.analysis.pta.toolkit.l4p;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.toolkit.PointerAnalysisResultEx;
import pascal.taie.analysis.pta.toolkit.PointerAnalysisResultExImpl;
import pascal.taie.language.classes.JMethod;

import java.util.Set;

public class L4P {
    private static final Logger logger = LogManager.getLogger(L4P.class);

    private final PointerAnalysisResultEx pta;

    public static Set<JMethod> run(PointerAnalysisResult pta, String arg) {

        return null;
    }

    public L4P(PointerAnalysisResult ptaBase) {
        this.pta = new PointerAnalysisResultExImpl(ptaBase, true);

    }

    public Set<JMethod> selectCtxSenMethods() {
        pta.getBase().getCallGraph().getNumberOfMethods();
        return null;
    }

}
