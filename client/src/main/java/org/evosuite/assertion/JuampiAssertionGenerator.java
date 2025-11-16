package org.evosuite.assertion;

import org.evosuite.Properties;
import org.evosuite.TimeController;
import org.evosuite.coverage.io.output.OutputCoverageTestFitness;
import org.evosuite.coverage.mutation.Mutation;
import org.evosuite.coverage.mutation.MutationTimeoutStoppingCondition;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.utils.LoggingUtils;
import org.evosuite.utils.Randomness;

import java.util.*;

public class JuampiAssertionGenerator extends SimpleMutationAssertionGenerator{


    public void addAssertions(TestSuiteChromosome suite) {
        // I found a test without unique goals, not sure how is that possible
        removeTestWithoutUniqueGoals(suite);
        // This generate mutation assertions and output goal assertions
        super.addAssertions(suite);
        // At this point all the test cases have assertions
        // And the assertion knows which mutant target to kill

        Set<Integer> tkilled = new HashSet<>(); // I do this as other version of assertion generation does

        suiteLevelMinimization(suite,tkilled);

        calculateMutationScore(tkilled);
        restoreCriterion(suite);
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


    protected void addAssertions(TestCase test, Set<Integer> killed) {
        // this add assertions that kill mutants
        // each assertion knows which muntant kill
        addAssertions(test, killed, mutants);
        // add all kind of assertions
        ExecutionResult result = runTest(test);
        for (OutputTrace<?> trace : result.getTraces()) {
            trace.getAllAssertions(test);
            trace.clear();
        }

        // flag assertions that are related to a OUTPUT coverage goal
        // are there assertions that are related to other goal rather than mutation and output?
        // these goals can be asserted...
        for(TestFitnessFunction goal: test.getCoveredGoals()){
            if(goal instanceof OutputCoverageTestFitness){
                OutputCoverageTestFitness outputGoal = (OutputCoverageTestFitness)goal;
                for(Assertion assertion: test.getAssertions()){
                    if(assertion instanceof PrimitiveAssertion && assertion.getStatement() instanceof MethodStatement){
                        MethodStatement methodStatement= (MethodStatement) assertion.getStatement();
                        // generating one assertion for output goal
                        if(outputGoal.getMethod().equals(methodStatement.getMethodName()+methodStatement.getDescriptor())
                                && outputGoal.getClassName().equals(methodStatement.getDeclaringClassName())){
                            assertion.addRelatedGoal(outputGoal);
                        }
                    }
                }
            }
        }
    }
    protected void minimize(TestCase test, List<Mutation> mutants, final List<Assertion> assertions, Map<Integer, Set<Integer>> killMap) {
        // do nothing
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
            List<Assertion> list = assertionsByTest.get(t);
            if (list == null) {
                list = new ArrayList<>();
                assertionsByTest.put(t, list);
            }
            list.add(a);
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
                List<Assertion> list = mutantToAssertions.get(m);
                if (list == null) {
                    list = new ArrayList<>();
                    mutantToAssertions.put(m, list);
                }
                list.add(a);
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
                List<Assertion> list = goalToAssertions.get(g);
                if (list == null) {
                    list = new ArrayList<>();
                    goalToAssertions.put(g, list);
                }
                list.add(a);
            }
        }

        Set<Assertion> chosenAssertions = new HashSet<>();

        // Greedy: as long as we have uncovered mutants or goals
        while (!mutantsBag.isEmpty() || !goalsBag.isEmpty()) {

            Assertion bestAssertion = null;
            TestCase bestTest = null;

            int bestGlobalUnique = -1;               // priority 1: unique items (mutants+goals) across the suite
            int bestGoalContribution = -1;           // priority 2: contribution to goals (prioritize goals)
            int bestTestChosenCount = Integer.MAX_VALUE; // priority 3: balance between tests
            int bestTotalContribution = -1;          // priority 4: total (mutants+goals)
            int bestUniqueInTest = -1;               // priority 5: unique within test (mutants only)

            // Search for the best assertion in the suite
            for (TestCase test : testBag) {
                List<Assertion> assertions = assertionsByTest.get(test);
                if (assertions == null || assertions.isEmpty()) {
                    continue;
                }

                int chosenCountForTest = finalAssertionsByTest.get(test).size();

                for (Assertion assertion : assertions) {
                    Set<Mutation> killedByAssert = mutantsByAssertion.get(assertion);
                    Set<TestFitnessFunction> goalsOfAssert = goalsByAssertion.get(assertion);

                    boolean hasMutants = killedByAssert != null && !killedByAssert.isEmpty();
                    boolean hasGoals = goalsOfAssert != null && !goalsOfAssert.isEmpty();

                    // 0) Skip useless assertions
                    if (!hasMutants && !hasGoals) {
                        continue;
                    }

                    // 1) Contribution to remaining mutants
                    int contributionMutants = 0;
                    if (hasMutants) {
                        for (Mutation m : killedByAssert) {
                            if (mutantsBag.contains(m)) {
                                contributionMutants++;
                            }
                        }
                    }

                    // 2) Contribution to remaining goals
                    int contributionGoals = 0;
                    if (hasGoals) {
                        for (TestFitnessFunction g : goalsOfAssert) {
                            if (goalsBag.contains(g)) {
                                contributionGoals++;
                            }
                        }
                    }

                    int totalContribution = contributionMutants + contributionGoals;

                    // Skip if it contributes nothing new
                    if (totalContribution == 0) {
                        continue;
                    }

                    // 3) Unique items (mutants+goals) across the suite
                    int globalUnique = 0;

                    if (hasMutants) {
                        for (Mutation m : killedByAssert) {
                            if (!mutantsBag.contains(m)) continue;
                            List<Assertion> killers = mutantToAssertions.get(m);
                            if (killers != null && killers.size() == 1 && killers.get(0) == assertion) {
                                globalUnique++;
                            }
                        }
                    }

                    if (hasGoals) {
                        for (TestFitnessFunction g : goalsOfAssert) {
                            if (!goalsBag.contains(g)) continue;
                            List<Assertion> rel = goalToAssertions.get(g);
                            if (rel != null && rel.size() == 1 && rel.get(0) == assertion) {
                                globalUnique++;
                            }
                        }
                    }

                    // 4) Unique mutants within the same test
                    int uniqueInThisTest = 0;
                    if (hasMutants) {
                        for (Mutation m : killedByAssert) {
                            if (!mutantsBag.contains(m)) continue;
                            boolean killedByOtherAssertInSameTest = false;
                            for (Assertion other : assertions) {
                                if (other == assertion) continue;
                                Set<Mutation> otherKilled = mutantsByAssertion.get(other);
                                if (otherKilled != null && otherKilled.contains(m)) {
                                    killedByOtherAssertInSameTest = true;
                                    break;
                                }
                            }
                            if (!killedByOtherAssertInSameTest) {
                                uniqueInThisTest++;
                            }
                        }
                    }

                    // ---- UPDATED GREEDY CRITERIA ----
                    // 1) higher globalUnique (mutants+goals)
                    // 2) if tied, higher contributionGoals (prioritize goals)
                    // 3) if tied, fewer assertions already chosen in this test (balance)
                    // 4) if tied, higher totalContribution (mutants+goals)
                    // 5) if tied, higher uniqueInThisTest

                    boolean better = false;

                    if (globalUnique > bestGlobalUnique) {
                        better = true;
                    } else if (globalUnique == bestGlobalUnique) {
                        if (contributionGoals > bestGoalContribution) {
                            better = true;
                        } else if (contributionGoals == bestGoalContribution) {
                            if (chosenCountForTest < bestTestChosenCount) {
                                better = true;
                            } else if (chosenCountForTest == bestTestChosenCount) {
                                if (totalContribution > bestTotalContribution) {
                                    better = true;
                                } else if (totalContribution == bestTotalContribution &&
                                        uniqueInThisTest > bestUniqueInTest) {
                                    better = true;
                                }
                            }
                        }
                    }

                    if (better) {
                        bestAssertion = assertion;
                        bestTest = test;
                        bestGlobalUnique = globalUnique;
                        bestGoalContribution = contributionGoals;
                        bestTestChosenCount = chosenCountForTest;
                        bestTotalContribution = totalContribution;
                        bestUniqueInTest = uniqueInThisTest;
                    }
                }
            }

            // If no assertion adds new information, stop
            if (bestAssertion == null) {
                break;
            }

            // Choose the best assertion found
            chosenAssertions.add(bestAssertion);
            finalAssertionsByTest.get(bestTest).add(bestAssertion);
            assertionsByTest.get(bestTest).remove(bestAssertion);

            // Remove covered mutants/goals from bags
            Set<Mutation> killedByBest = mutantsByAssertion.get(bestAssertion);
            if (killedByBest != null) {
                mutantsBag.removeAll(killedByBest);
            }

            Set<TestFitnessFunction> goalsByBest = goalsByAssertion.get(bestAssertion);
            if (goalsByBest != null) {
                goalsBag.removeAll(goalsByBest);
            }
        }

        // === Post-step: ensure every test keeps at least one useful assertion ===
        // If has no assertion it means that no contribution mutation score and outputcoverage
        /*for (TestCase test : testBag) {
            LinkedHashSet<Assertion> selected = finalAssertionsByTest.get(test);
            if (!selected.isEmpty()) continue;

            List<Assertion> candidates = assertionsByTest.get(test);
            if (candidates == null || candidates.isEmpty()) continue;

            // Choose the local assertion that covers the most (mutants+goals)
            Assertion bestLocal = null;
            int bestLocalSize = -1;
            for (Assertion a : candidates) {
                Set<Mutation> ms = mutantsByAssertion.get(a);
                Set<TestFitnessFunction> gs = goalsByAssertion.get(a);
                int size = (ms == null ? 0 : ms.size()) + (gs == null ? 0 : gs.size());
                if (size == 0) {
                    continue;
                }
                if (size > bestLocalSize) {
                    bestLocalSize = size;
                    bestLocal = a;
                }
            }
            if (bestLocal != null) {
                selected.add(bestLocal);
            }
        }*/

        return finalAssertionsByTest;
    }

}
