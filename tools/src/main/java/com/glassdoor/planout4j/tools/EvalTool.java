package com.glassdoor.planout4j.tools;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.MutuallyExclusiveGroup;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;
import net.sourceforge.argparse4j.inf.Subparsers;
import com.google.common.base.MoreObjects;

import com.glassdoor.planout4j.*;
import com.glassdoor.planout4j.compiler.PlanoutDSLCompiler;
import com.glassdoor.planout4j.config.ValidationException;
import com.glassdoor.planout4j.planout.Interpreter;
import com.glassdoor.planout4j.util.Helper;

/**
 * Command-line interface for evaluating namespace, specific experiment, or an ad-hoc snippet.
 */
public class EvalTool {

    private static final Logger LOG = LoggerFactory.getLogger(EvalTool.class);

    public static void configureArgsParser(final Subparsers subparsers) {
        final Subparser eval = subparsers.addParser("eval").help("evaluates namespace, experiment, or code snippet");
        Planout4jTool.addBackendArgs(eval, false);
        final MutuallyExclusiveGroup nameOrScript = eval.addMutuallyExclusiveGroup("evaluation object");
        nameOrScript.addArgument("-n", "--name").help("namespace name");
        final MutuallyExclusiveGroup experimentOrDefinition = eval.addMutuallyExclusiveGroup("additional selectors within namespace");
        experimentOrDefinition.addArgument("--exp").help("experiment name (requires --name NAME)");
        experimentOrDefinition.addArgument("--definition").help("experiment definition key (requires --name NAME)");
        experimentOrDefinition.addArgument("--default").action(Arguments.storeTrue()).help("default experiment (requires --name NAME)");
        nameOrScript.addArgument("--script").help("planout DSL script");
        eval.addArgument("input").nargs("+").help("input parameters in the form of KEY=VALUE");
    }

    public static void execute(final Namespace parsedArgs) throws IOException, ValidationException {
        final Map<String, Object> inputMap = new HashMap<>();
        for (String input : parsedArgs.<String>getList("input")) {
            String[] keyValue = input.split("=", 2);
            if (keyValue.length == 2) {
                inputMap.put(keyValue[0], keyValue[1]);
            } else {
                LOG.warn("Invalid input string '{}' - expecting KEY=VALUE");
            }
        }
        LOG.trace("Have {} input parameters", inputMap.size());

        final String name = parsedArgs.getString("name"), script = parsedArgs.getString("script");
        final Map<String, ?> outcome = name != null ?
                evaluateNamespace(parsedArgs, name, inputMap) : evaluateStandalone(parsedArgs, script, inputMap);
        if (outcome != null) {
            System.out.println(
                    Planout4jTool.getGson(new Namespace(Map.of("pretty", Boolean.TRUE)))
                            .toJson(outcome));
        }
    }

    private static Map<String, ?> evaluateNamespace(final Namespace parsedArgs, final String name,
                                                    final Map<String, Object> inputMap)
    {
        final NamespaceFactory nsFact = new SimpleNamespaceFactory();
        final Optional<com.glassdoor.planout4j.Namespace> ns = nsFact.getNamespace(name, inputMap);
        if (ns.isPresent()) {
            final NamespaceConfig nsConf = ns.get().nsConf;
            Experiment exp = null;
            final String expStr = parsedArgs.getString("exp"), defStr = parsedArgs.getString("definition");
            if (expStr != null) {
                exp = nsConf.getActiveExperiment(expStr);
                if (exp == null) {
                    LOG.error("No active experiment named '{}' exists in namespace '{}'. All active experiments: {}",
                            expStr, name, nsConf.getActiveExperimentNames());
                    return null;
                }
                LOG.debug("Using experiment named {} (definition: {})", exp.name, exp.def.definition);
            } else if (defStr != null) {
                final ExperimentConfig expConf = nsConf.getExperimentConfig(defStr);
                if (expConf == null) {
                    LOG.error("No experiment definition named '{}' exists in namespace '{}'. All experiment definitions: {}",
                            defStr, name, nsConf.getExperimentConfigNames());
                    return null;
                } else {
                    exp = new Experiment(defStr,
                            MoreObjects.firstNonNull(parsedArgs.getString("salt"), String.format("%s.%s", name, defStr)),
                            expConf, Collections.singleton(0));
                    LOG.debug("Using experiment definition {}", defStr);
                }
            } else if (parsedArgs.getBoolean("default")) {
                exp = nsConf.getDefaultExperiment();
                LOG.debug("Using default experiment (def named {})", exp.def.definition);
            }
            if (exp == null) {
                System.out.printf("\nInput gets allocated to %s experiment\n\n",
                        MoreObjects.firstNonNull(ns.get().getExperiment(), nsConf.getDefaultExperiment()).name);
                return ns.get().getParams();
            } else {
                return new Interpreter(exp.def.getCopyOfScript(), exp.salt, inputMap, null).getParams();
            }
        } else {
            LOG.error("Namespace with name {} does not exist");
        }
        return null;
    }

    private static Map<String, ?> evaluateStandalone(final Namespace parsedArgs, final String script,
                                                    final Map<String, Object> inputMap) throws ValidationException
    {
        try {
            return new Interpreter(Helper.deepCopy(PlanoutDSLCompiler.dsl_to_json(script), null),
                    parsedArgs.getString("salt"), inputMap, null).getParams();
        } catch (ValidationException e) {
            System.out.printf("\nFailed to evaluate script, see error trace below\n%s\n\n", script);
            throw e;
        }
    }


    private EvalTool() {}

}
