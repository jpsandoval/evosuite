package org.evosuite.assertion;

import com.sun.codemodel.internal.JMethod;
import org.evosuite.Properties;
import org.evosuite.TimeController;
import org.evosuite.coverage.io.output.OutputCoverageTestFitness;
import org.evosuite.coverage.mutation.Mutation;
import org.evosuite.coverage.mutation.MutationTimeoutStoppingCondition;
import org.evosuite.rmi.ClientServices;
import org.evosuite.rmi.service.ClientState;
import org.evosuite.rmi.service.ClientStateInformation;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.utils.LoggingUtils;
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.*;

public class JuampiAssertionGenerator extends SimpleMutationAssertionGenerator{
    private final static Logger logger = LoggerFactory.getLogger(JuampiAssertionGenerator.class);


    public void addAssertions(TestSuiteChromosome suite) {
        setupClassLoader(suite);

        if (!Properties.hasTargetClassBeenLoaded()) {
            // Need to load class explicitly since it was re-instrumented
            Properties.getTargetClassAndDontInitialise();
            if (!Properties.hasTargetClassBeenLoaded()) {
                logger.warn("Could not initialize SUT before Assertion generation");
            }
        }

        removeTestWithoutUniqueGoals(suite);

        Set<Integer> tkilled = new HashSet<>();
        // Generation
        for(TestCase test: suite.getTests()){
            logger.debug("Running on original");
            ExecutionResult origResult = runTest(test);
            logger.debug("Adding Mutation Killed Assertions");
            addMutantKilledAssertions(test,origResult, tkilled, mutants);
            logger.debug("Adding Output Coverage Assertions");
            addGoalRelatedAssertion(test,origResult);
        }
        // Minimization
        suiteLevelMinimization(suite,tkilled);

        calculateMutationScore(tkilled);
        restoreCriterion(suite);
    }


    private void addGoalRelatedAssertion(TestCase test, ExecutionResult origResult){
        // generating all assertions
        for (OutputTrace<?> trace : origResult.getTraces()) {
            trace.getAllAssertions(test);
            trace.clear();
        }
        // related with goals.
        for(TestFitnessFunction goal: test.getCoveredGoals()) {
            // For now, I only flag the output coverage goal
            if (goal instanceof OutputCoverageTestFitness) {
                OutputCoverageTestFitness outputGoal = (OutputCoverageTestFitness) goal;
                for (Assertion assertion : test.getAssertions()) {
                    if (assertion instanceof PrimitiveAssertion && assertion.getStatement() instanceof MethodStatement) {
                        MethodStatement methodStatement = (MethodStatement) assertion.getStatement();
                        if (outputGoal.getMethod().equals(methodStatement.getMethodName() + methodStatement.getDescriptor())
                                && outputGoal.getClassName().equals(methodStatement.getDeclaringClassName())) {
                            assertion.addRelatedGoal(outputGoal);
                        }
                    }
                }
            }
        }
    }

    protected void addMutantKilledAssertions(TestCase test, ExecutionResult origResult,Set<Integer> killed,  Map<Integer, Mutation> mutants) {
        if (test.isEmpty())
            return;

        logger.debug("Generating assertions");

        int s1 = killed.size();
        // ExecutionResult origResult = runTest(test);
        if (origResult.hasTimeout() || origResult.hasTestException()) {
            logger.debug("Skipping test, as it has timeouts or exceptions");
            return;
        }

        Map<Mutation, List<OutputTrace<?>>> mutationTraces = new HashMap<>();
        List<Mutation> executedMutants = new ArrayList<>();

        for (Integer mutationId : origResult.getTrace().getTouchedMutants()) {
            if (!mutants.containsKey(mutationId)) {
                //logger.warn("Mutation ID unknown: " + mutationId);
                //logger.warn(mutants.keySet().toString());
            } else
                executedMutants.add(mutants.get(mutationId));
        }

        Randomness.shuffle(executedMutants);
        logger.debug("Executed mutants: " + origResult.getTrace().getTouchedMutants());

        int numExecutedMutants = 0;
        for (Mutation m : executedMutants) {

            numExecutedMutants++;
            if (!TimeController.getInstance().isThereStillTimeInThisPhase()) {
                logger.info("Reached maximum time to generate assertions!");
                break;
            }

            assert (m != null);
            if (MutationTimeoutStoppingCondition.isDisabled(m)) {
                killed.add(m.getId());
                continue;
            }
            if (timedOutMutations.containsKey(m)) {
                if (timedOutMutations.get(m) >= Properties.MUTATION_TIMEOUTS) {
                    logger.debug("Skipping timed out mutant");
                    killed.add(m.getId());
                    continue;
                }
            }
            if (exceptionMutations.containsKey(m)) {
                if (exceptionMutations.get(m) >= Properties.MUTATION_TIMEOUTS) {
                    logger.debug("Skipping mutant with exceptions");
                    killed.add(m.getId());
                    continue;
                }
            }
            if (Properties.MAX_MUTANTS_PER_TEST > 0
                    && numExecutedMutants > Properties.MAX_MUTANTS_PER_TEST)
                break;

			/*
			if (killed.contains(m.getId())) {
				logger.info("Skipping dead mutant");
				continue;
			}
			*/

            logger.debug("Running test on mutation {}", m.getMutationName());
            ExecutionResult mutantResult = runTest(test, m);

            int numKilled = 0;
            for (Class<?> observerClass : observerClasses) {
                if (mutantResult.getTrace(observerClass) == null
                        || origResult.getTrace(observerClass) == null)
                    continue;
                numKilled += origResult.getTrace(observerClass).getAssertions(test,
                        mutantResult.getTrace(observerClass));
            }

            List<OutputTrace<?>> traces = new ArrayList<>(
                    mutantResult.getTraces());
            mutationTraces.put(m, traces);

            if (mutantResult.hasTimeout()) {
                logger.debug("Increasing timeout count!");
                if (!timedOutMutations.containsKey(m)) {
                    timedOutMutations.put(m, 1);
                } else {
                    timedOutMutations.put(m, timedOutMutations.get(m) + 1);
                }
                MutationTimeoutStoppingCondition.timeOut(m);

            } else if (!mutantResult.noThrownExceptions()
                    && origResult.noThrownExceptions()) {
                logger.debug("Increasing exception count.");
                if (!exceptionMutations.containsKey(m)) {
                    exceptionMutations.put(m, 1);
                } else {
                    exceptionMutations.put(m, exceptionMutations.get(m) + 1);
                }
                MutationTimeoutStoppingCondition.raisedException(m);
            }

            if (numKilled > 0
                    || mutantResult.hasTimeout()
                    || (!mutantResult.noThrownExceptions() && origResult.noThrownExceptions())) {
                killed.add(m.getId());
            }
        }

        List<Assertion> assertions = test.getAssertions();
        logger.info("Got " + assertions.size() + " assertions");
        Map<Integer, Set<Integer>> killMap = new HashMap<>();
        int num = 0;
        for (Assertion assertion : assertions) {
            Set<Integer> killedMutations = new HashSet<>();
            for (Mutation m : executedMutants) {

                boolean isKilled = false;
                if (mutationTraces.containsKey(m)) {
                    for (OutputTrace<?> trace : mutationTraces.get(m)) {
                        if (trace.isDetectedBy(assertion)) {
                            isKilled = true;
                            break;
                        }
                    }
                }
                if (isKilled) {
                    killedMutations.add(m.getId());
                    assertion.addKilledMutation(m);
                }
            }
            killMap.put(num, killedMutations);
            logger.info("Assertion " + num + " kills mutants " + killedMutations);
            num++;
        }
    }
    private void suiteLevelMinimization(TestSuiteChromosome suite,Set<Integer> tkilled){
        Map<TestCase, LinkedHashSet<Assertion>> filteredAssertions = filterAssertionByTest(suite);
        for (Map.Entry<TestCase, LinkedHashSet<Assertion>> entry : filteredAssertions.entrySet()) {
            TestCase test = entry.getKey();
            LinkedHashSet<Assertion> result = entry.getValue();
            // Remove all current assertions from the test
            test.removeAssertions();

            // reattach each assertion to its original statement
            for (Assertion assertion : result) {
                assertion.getStatement().addAssertion(assertion);
                // count the mutants that kill the assertion I add
                for(Mutation m: assertion.getKilledMutations()){
                    tkilled.add(m.getId());
                }
            }

        }
    }

    public Map<TestCase, LinkedHashSet<Assertion>> filterAssertionByTest(TestSuiteChromosome suite) {

        // Final result: selected assertions per test
        Map<TestCase, LinkedHashSet<Assertion>> finalAssertionsByTest = new LinkedHashMap<>();

        Set<TestCase> testBag = new LinkedHashSet<>(suite.getTests());
        Set<Mutation> mutantsBag = new LinkedHashSet<>();
        Map<Assertion, TestCase> assertionBag = new LinkedHashMap<>();
        Map<TestCase, LinkedHashSet<Mutation>> mutantsKilled = new LinkedHashMap<>();

        // 1) Fill: mutantsKilled, mutantsBag, assertionBag
        for (TestCase test : testBag) {
            LinkedHashSet<Mutation> killedByTest = new LinkedHashSet<>();
            for (Assertion assertion : test.getAssertions()) {
                assertionBag.put(assertion, test);
                killedByTest.addAll(assertion.getKilledMutations());
            }
            mutantsKilled.put(test, killedByTest);
            mutantsBag.addAll(killedByTest);
            finalAssertionsByTest.put(test, new LinkedHashSet<Assertion>());
        }

        // Group assertions by test
        Map<TestCase, List<Assertion>> assertionsByTest = new LinkedHashMap<>();
        for (Map.Entry<Assertion, TestCase> e : assertionBag.entrySet()) {
            Assertion a = e.getKey();
            TestCase t = e.getValue();
            assertionsByTest.computeIfAbsent(t, k -> new ArrayList<>()).add(a);
        }

        // Mutants per assertion
        Map<Assertion, Set<Mutation>> mutantsByAssertion = new LinkedHashMap<>();
        for (Assertion a : assertionBag.keySet()) {
            mutantsByAssertion.put(a, new LinkedHashSet<>(a.getKilledMutations()));
        }

        // Mutant -> list of assertions that kill it
        Map<Mutation, List<Assertion>> mutantToAssertions = new HashMap<>();
        for (Map.Entry<Assertion, TestCase> e : assertionBag.entrySet()) {
            Assertion a = e.getKey();
            for (Mutation m : mutantsByAssertion.get(a)) {
                mutantToAssertions.computeIfAbsent(m, k -> new ArrayList<>()).add(a);
            }
        }

        // Assertion -> related goals
        Map<Assertion, Set<TestFitnessFunction>> goalsByAssertion = new LinkedHashMap<>();
        // Global bag of goals covered by assertions
        Set<TestFitnessFunction> goalsBag = new LinkedHashSet<>();

        for (Assertion a : assertionBag.keySet()) {
            Set<TestFitnessFunction> related = new LinkedHashSet<>(a.getRelatedGoals());
            goalsByAssertion.put(a, related);
            goalsBag.addAll(related);
        }

        // Goal -> list of assertions that cover it
        Map<TestFitnessFunction, List<Assertion>> goalToAssertions = new HashMap<>();
        for (Map.Entry<Assertion, TestCase> e : assertionBag.entrySet()) {
            Assertion a = e.getKey();
            for (TestFitnessFunction g : goalsByAssertion.get(a)) {
                goalToAssertions.computeIfAbsent(g, k -> new ArrayList<>()).add(a);
            }
        }

        Set<Assertion> chosenAssertions = new HashSet<>();

        // === Greedy selection loop ===
        // Greedy selection strategy:
        // 1) Prefer assertions that cover more mutants + goals that are unique at suite level.
        // 2) Among ties, prefer assertions from tests with fewer selected assertions (balance).
        // 3) Among ties, prefer assertions that cover mutants + goals that are unique within their test.
        // 4) Remaining ties are left unchanged (effectively random depending on iteration order).

        while (!mutantsBag.isEmpty() || !goalsBag.isEmpty()) {

            Assertion bestAssertion = null;
            TestCase bestTest = null;

            int bestSuiteUnique = -1;            // 1) unique mutants+goals in suite
            int bestTestLoad = Integer.MAX_VALUE; // 2) fewer selected assertions in test
            int bestLocalUnique = -1;            // 3) unique mutants+goals within test

            // Search for the best assertion
            for (TestCase test : testBag) {
                List<Assertion> assertions = assertionsByTest.get(test);
                if (assertions == null || assertions.isEmpty()) continue;

                int currentLoadForTest = finalAssertionsByTest.get(test).size();

                for (Assertion assertion : assertions) {
                    Set<Mutation> killedByAssert = mutantsByAssertion.get(assertion);
                    Set<TestFitnessFunction> goalsOfAssert = goalsByAssertion.get(assertion);

                    boolean hasMutants = killedByAssert != null && !killedByAssert.isEmpty();
                    boolean hasGoals = goalsOfAssert != null && !goalsOfAssert.isEmpty();

                    // Skip assertions with no effect
                    if (!hasMutants && !hasGoals) continue;

                    // Check contribution (skip if adds nothing new)
                    int totalContribution = 0;
                    if (hasMutants) {
                        for (Mutation m : killedByAssert)
                            if (mutantsBag.contains(m)) totalContribution++;
                    }
                    if (hasGoals) {
                        for (TestFitnessFunction g : goalsOfAssert)
                            if (goalsBag.contains(g)) totalContribution++;
                    }
                    if (totalContribution == 0) continue;

                    // 1) Suite-level uniqueness
                    int suiteUnique = 0;
                    if (hasMutants) {
                        for (Mutation m : killedByAssert) {
                            if (!mutantsBag.contains(m)) continue;
                            List<Assertion> killers = mutantToAssertions.get(m);
                            if (killers != null && killers.size() == 1 && killers.get(0) == assertion)
                                suiteUnique++;
                        }
                    }
                    if (hasGoals) {
                        for (TestFitnessFunction g : goalsOfAssert) {
                            if (!goalsBag.contains(g)) continue;
                            List<Assertion> rel = goalToAssertions.get(g);
                            if (rel != null && rel.size() == 1 && rel.get(0) == assertion)
                                suiteUnique++;
                        }
                    }

                    // 3) Local uniqueness within the same test
                    int localUnique = 0;
                    if (hasMutants) {
                        for (Mutation m : killedByAssert) {
                            if (!mutantsBag.contains(m)) continue;
                            boolean killedByOther = false;
                            for (Assertion other : assertions) {
                                if (other == assertion) continue;
                                Set<Mutation> otherKilled = mutantsByAssertion.get(other);
                                if (otherKilled != null && otherKilled.contains(m)) {
                                    killedByOther = true; break;
                                }
                            }
                            if (!killedByOther) localUnique++;
                        }
                    }
                    if (hasGoals) {
                        for (TestFitnessFunction g : goalsOfAssert) {
                            if (!goalsBag.contains(g)) continue;
                            boolean coveredByOther = false;
                            for (Assertion other : assertions) {
                                if (other == assertion) continue;
                                Set<TestFitnessFunction> otherGoals = goalsByAssertion.get(other);
                                if (otherGoals != null && otherGoals.contains(g)) {
                                    coveredByOther = true; break;
                                }
                            }
                            if (!coveredByOther) localUnique++;
                        }
                    }

                    // ---- New simplified greedy decision ----
                    boolean better = false;

                    if (suiteUnique > bestSuiteUnique) {
                        better = true;
                    } else if (suiteUnique == bestSuiteUnique) {
                        if (currentLoadForTest < bestTestLoad) {
                            better = true;
                        } else if (currentLoadForTest == bestTestLoad) {
                            if (localUnique > bestLocalUnique) {
                                better = true;
                            }
                        }
                    }

                    if (better) {
                        bestAssertion = assertion;
                        bestTest = test;
                        bestSuiteUnique = suiteUnique;
                        bestTestLoad = currentLoadForTest;
                        bestLocalUnique = localUnique;
                    }
                }
            }

            // Stop if no useful assertion found
            if (bestAssertion == null) break;

            // Choose best assertion found
            chosenAssertions.add(bestAssertion);
            finalAssertionsByTest.get(bestTest).add(bestAssertion);
            assertionsByTest.get(bestTest).remove(bestAssertion);

            // Remove covered mutants/goals from the global bags
            Set<Mutation> killedByBest = mutantsByAssertion.get(bestAssertion);
            if (killedByBest != null) mutantsBag.removeAll(killedByBest);

            Set<TestFitnessFunction> goalsByBest = goalsByAssertion.get(bestAssertion);
            if (goalsByBest != null) goalsBag.removeAll(goalsByBest);
        }

        return finalAssertionsByTest;
    }


    private void removeTestWithoutUniqueGoals(TestSuiteChromosome suite){
        // --- Remove tests that do not have unique covered goals ---

        List<TestCase> allTests = new ArrayList<>(suite.getTests());

        // Map each test to the set of goals it covers
        Map<TestCase, Set<Object>> goalsByTest = new LinkedHashMap<>();
        for (TestCase t : allTests) {
        goalsByTest.put(t, new LinkedHashSet<>(t.getCoveredGoals()));
        }

        List<TestCase> testsToRemove = new ArrayList<>();

        // For each test, check if it has at least one goal not covered by any other test
        for (TestCase current : allTests) {
        Set<Object> remainingGoals = new LinkedHashSet<>(goalsByTest.get(current));

        for (TestCase other : allTests) {
        if (current == other) continue; // compare by reference
        remainingGoals.removeAll(goalsByTest.get(other));
        if (remainingGoals.isEmpty()) break; // early stop
        }

        // If the test has no unique goals, mark it for removal
        if (remainingGoals.isEmpty()) {
        testsToRemove.add(current);
        }
        }

        // Remove all tests that do not have unique goals
        for (TestCase t : testsToRemove) {
        suite.deleteTest(t); // or suite.removeTest(t), depending on your API
        }
    }


}
