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

package pascal.taie.analysis.pta;

import org.apache.logging.log4j.Level;
import pascal.taie.World;
import pascal.taie.analysis.ProgramAnalysis;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.MapBasedCSManager;
import pascal.taie.analysis.pta.core.cs.selector.ContextSelector;
import pascal.taie.analysis.pta.core.cs.selector.ContextSelectorFactory;
import pascal.taie.analysis.pta.core.heap.AllocationSiteBasedModel;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.analysis.pta.core.solver.DefaultSolver;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.AnalysisTimer;
import pascal.taie.analysis.pta.plugin.ClassInitializer;
import pascal.taie.analysis.pta.plugin.CompositePlugin;
import pascal.taie.analysis.pta.plugin.EntryPointHandler;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.analysis.pta.plugin.ReferenceHandler;
import pascal.taie.analysis.pta.plugin.ResultProcessor;
import pascal.taie.analysis.pta.plugin.ThreadHandler;
import pascal.taie.analysis.pta.plugin.exception.ExceptionAnalysis;
import pascal.taie.analysis.pta.plugin.invokedynamic.InvokeDynamicAnalysis;
import pascal.taie.analysis.pta.plugin.invokedynamic.Java9StringConcatHandler;
import pascal.taie.analysis.pta.plugin.invokedynamic.LambdaAnalysis;
import pascal.taie.analysis.pta.plugin.natives.NativeModeller;
import pascal.taie.analysis.pta.plugin.reflection.ReflectionAnalysis;
import pascal.taie.analysis.pta.plugin.taint.TaintAnalysis;
import pascal.taie.analysis.pta.toolkit.CollectionMethods;
import pascal.taie.analysis.pta.toolkit.mahjong.Mahjong;
import pascal.taie.analysis.pta.toolkit.scaler.Scaler;
import pascal.taie.analysis.pta.toolkit.zipper.Zipper;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.config.AnalysisOptions;
import pascal.taie.config.ConfigException;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.Type;
import pascal.taie.util.AnalysisException;
import pascal.taie.util.Timer;
import pascal.taie.util.collection.Pair;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class PointerAnalysis extends ProgramAnalysis<PointerAnalysisResult> {

    public static final String ID = "pta";

    public PointerAnalysis(AnalysisConfig config) {
        super(config);
    }

    @Override
    public PointerAnalysisResult analyze() {
        AnalysisOptions options = getOptions();
        HeapModel heapModel = new AllocationSiteBasedModel(options);
        ContextSelector selector = null;
        Pair<Map<Type, Collection<JMethod>>, Map<Type, Collection<JMethod>>> pair = null;
        Map<Type, Collection<JMethod>> pcmMap = null;
        Map<Type, Collection<JMethod>> pcmMapE = null;
        String advanced = options.getString("advanced");
        String cs = options.getString("cs");
        if (advanced != null) {
            if (advanced.equals("collection")) {
                selector = ContextSelectorFactory.makeSelectiveSelector(cs,
                        new CollectionMethods(World.get().getClassHierarchy()).get());
            } else {
                // run context-insensitive analysis as pre-analysis
                PointerAnalysisResult preResult = runAnalysis(heapModel,
                        ContextSelectorFactory.makeCISelector());
                if (advanced.startsWith("scaler")) {
                    selector = Timer.runAndCount(() -> ContextSelectorFactory
                                    .makeGuidedSelector(Scaler.run(preResult, advanced)),
                            "Scaler", Level.INFO);
                } else if (advanced.startsWith("zipper")) {
                    selector = Timer.runAndCount(() -> ContextSelectorFactory
                                    .makeSelectiveSelector(cs, Zipper.run(preResult, advanced)),
                            "Zipper", Level.INFO);
                } else if (advanced.equals("mahjong")) {
                    heapModel = Timer.runAndCount(() -> Mahjong.run(preResult, options),
                            "Mahjong", Level.INFO);
                } else if (advanced.startsWith("l4p")) {
                    selector = Timer.runAndCount(() -> ContextSelectorFactory
                                    .makeSelectiveSelector(cs, Zipper.run(preResult, advanced)),
                            "L4P", Level.INFO);
                } else if (advanced.startsWith("test")) {
                    pair = Timer.runAndCount(() -> Zipper.run_test(preResult, advanced),"Test", Level.INFO);
                    pcmMap = pair.first();
                    pcmMapE = pair.second();
                    run_test(pcmMap, pcmMapE);
                    return null;
                } else {
                    throw new IllegalArgumentException(
                            "Illegal advanced analysis argument: " + advanced);
                }
            }
        }
        if (selector == null) {
            selector = ContextSelectorFactory.makePlainSelector(cs);
        }
        return runAnalysis(heapModel, selector);
    }

    private PointerAnalysisResult runAnalysis(HeapModel heapModel,
                                              ContextSelector selector) {
        AnalysisOptions options = getOptions();
        Solver solver = new DefaultSolver(options,
                heapModel, selector, new MapBasedCSManager());
        // The initialization of some Plugins may read the fields in solver,
        // e.g., contextSelector or csManager, thus we initialize Plugins
        // after setting all other fields of solver.
        setPlugin(solver, options);
        solver.solve();
        return solver.getResult();
    }

    private static void setPlugin(Solver solver, AnalysisOptions options) {
        CompositePlugin plugin = new CompositePlugin();
        // add builtin plugins
        // To record elapsed time precisely, AnalysisTimer should be added at first.
        plugin.addPlugin(
                new AnalysisTimer(),
                new EntryPointHandler(),
                new ClassInitializer(),
                new ThreadHandler(),
                new NativeModeller(),
                new ExceptionAnalysis()
        );
        int javaVersion = World.get().getOptions().getJavaVersion();
        if (javaVersion < 9) {
            // current reference handler doesn't support Java 9+
            plugin.addPlugin(new ReferenceHandler());
        }
        if (javaVersion >= 8) {
            plugin.addPlugin(new LambdaAnalysis());
        }
        if (javaVersion >= 9) {
            plugin.addPlugin(new Java9StringConcatHandler());
        }
        if (options.getString("reflection-inference") != null ||
                options.getString("reflection-log") != null) {
            plugin.addPlugin(new ReflectionAnalysis());
        }
        if (options.getBoolean("handle-invokedynamic") &&
                InvokeDynamicAnalysis.useMethodHandle()) {
            plugin.addPlugin(new InvokeDynamicAnalysis());
        }
        if (options.getString("taint-config") != null
                || !((List<String>) options.get("taint-config-providers")).isEmpty()) {
            plugin.addPlugin(new TaintAnalysis());
        }
        plugin.addPlugin(new ResultProcessor());
        // add plugins specified in options
        // noinspection unchecked
        addPlugins(plugin, (List<String>) options.get("plugins"));
        // connects plugins and solver
        plugin.setSolver(solver);
        solver.setPlugin(plugin);
    }

    private static void addPlugins(CompositePlugin plugin,
                                   List<String> pluginClasses) {
        for (String pluginClass : pluginClasses) {
            try {
                Class<?> clazz = Class.forName(pluginClass);
                Constructor<?> ctor = clazz.getConstructor();
                Plugin newPlugin = (Plugin) ctor.newInstance();
                plugin.addPlugin(newPlugin);
            } catch (ClassNotFoundException e) {
                throw new ConfigException(
                        "Plugin class " + pluginClass + " is not found");
            } catch (IllegalAccessException | NoSuchMethodException e) {
                throw new AnalysisException("Failed to get constructor of " +
                        pluginClass + ", does the plugin class" +
                        " provide a public non-arg constructor?");
            } catch (InvocationTargetException | InstantiationException e) {
                throw new AnalysisException(
                        "Failed to create plugin instance for " + pluginClass, e);
            }
        }
    }

    private void run_test(Map<Type, Collection<JMethod>> pcmMap, Map<Type, Collection<JMethod>> pcmMapE) {
        iterateCombinations(pcmMap, pcmMapE);
    }

    private void iterateCombinations(Map<Type, Collection<JMethod>> pcmMap, Map<Type, Collection<JMethod>> pcmMapE) {
        long n = pcmMap.values().stream().filter(value -> !value.isEmpty()).count();
        List<Collection<JMethod>> pcmList = pcmMap.values().stream().toList();
        List<Collection<JMethod>> pcmListE = pcmMapE.values().stream().toList();

        // get complement
        List<Collection<JMethod>> complement = new ArrayList<>(pcmList);
        complement.removeAll(pcmListE);

        AnalysisOptions options = getOptions();
        HeapModel heapModel = new AllocationSiteBasedModel(options);
        String cs = options.getString("cs");
        ContextSelector selector = null;

//        List<Collection<JMethod>> plus = new ArrayList<>(pcmListE);
//        List<Collection<JMethod>> minus = new ArrayList<>(pcmListE);
//        List<Collection<JMethod>> pm = new ArrayList<>(pcmListE);

        File outFile = new File(World.get().getOptions().getOutputDir(), "test-plan.log");
        try (PrintStream out = new PrintStream(new FileOutputStream(outFile))) {
            int min = 1;
            int maxP = complement.size();
            int maxM = pcmListE.size();
            int count = 0;
            while(true) {
                // plus
                int randP = ThreadLocalRandom.current().nextInt(min, maxP);
                List<Collection<JMethod>> copy1 = new ArrayList<>(complement);
                Collections.shuffle(copy1);
                List<Collection<JMethod>> plus = new ArrayList<>(pcmListE);
                plus.addAll(copy1.subList(0, randP));
                Set<JMethod> pcms = plus.stream().flatMap(Collection::stream).collect(Collectors.toUnmodifiableSet());
                selector = ContextSelectorFactory.makeSelectiveSelector(cs, pcms);
                runAnalysis(heapModel, selector);

                count++;
                out.printf("%d: add %d classes%n", count, randP);

                // minus
                int randM = ThreadLocalRandom.current().nextInt(min, maxM);
                List<Collection<JMethod>> copy2 = new ArrayList<>(pcmListE);
                Collections.shuffle(copy2);
                List<Collection<JMethod>> minus = new ArrayList<>(pcmListE);
                minus.removeAll(copy2.subList(0, randM));
                pcms = minus.stream().flatMap(Collection::stream).collect(Collectors.toUnmodifiableSet());
                selector = ContextSelectorFactory.makeSelectiveSelector(cs, pcms);
                runAnalysis(heapModel, selector);

                count++;
                out.printf("%d: remove %d classes%n", count, randM);

                // plus-minus
                List<Collection<JMethod>> pm = new ArrayList<>(pcmListE);
                pm.addAll(copy1.subList(0, randP));
                pm.removeAll(copy2.subList(0, randM));
                pcms = pm.stream().flatMap(Collection::stream).collect(Collectors.toUnmodifiableSet());
                selector = ContextSelectorFactory.makeSelectiveSelector(cs, pcms);
                runAnalysis(heapModel, selector);

                count++;
                out.printf("%d: add %d classes and remove %d classes%n", count, randP, randM);

                StringBuilder sb = new StringBuilder();
                sb.append("Added classes: ");
                Iterator<Collection<JMethod>> iterator  = copy1.subList(0, randP).iterator();
                for(int i = 0; i < randP; i++) {
                    Collection<JMethod> next = iterator.next();
                    Type key = getTypeByValue(pcmMap, next);
                    sb.append(key.toString()).append(",");
                }
                sb.append("\nRemoved classes: ");
                iterator  = copy2.subList(0, randM).iterator();
                for(int i = 0; i < randM; i++) {
                    Collection<JMethod> next = iterator.next();
                    Type key = getTypeByValue(pcmMap, next);
                    sb.append(key.toString()).append(",");
                }
                out.println(sb);

                if(count > 10000) {
                    return;
                }

            }
        } catch (FileNotFoundException e) {
            ;
        }



//        for (Collection<JMethod> express : pcmListE) {
//            List<Collection<JMethod>> pcmListNew = new ArrayList<>(pcmListE);
//            pcmListNew.remove(express);
//
//            Set<JMethod> pcms = pcmListNew.stream().flatMap(Collection::stream).collect(Collectors.toUnmodifiableSet());
//            selector = ContextSelectorFactory.makeSelectiveSelector(cs, pcms);
//            runAnalysis(heapModel, selector);
//        }
    }

    private Type getTypeByValue(Map<Type, Collection<JMethod>> pcmMap, Collection<JMethod> pcms) {
        for (Map.Entry<Type, Collection<JMethod>> entry : pcmMap.entrySet()) {
            if (Objects.equals(entry.getValue(), pcms)) {
                return entry.getKey();   // 返回第一个匹配的 key
            }
        }
        return null;
    }
}
