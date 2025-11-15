package org.evosuite.assertion;


import org.evosuite.coverage.line.LineCoverageTestFitness;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.coverage.exception.ExceptionCoverageTestFitness;
import org.evosuite.coverage.mutation.MutationTestFitness;
import org.evosuite.coverage.io.output.OutputCoverageTestFitness;
import org.evosuite.coverage.method.MethodCoverageTestFitness;
import org.evosuite.coverage.method.MethodNoExceptionCoverageTestFitness;




import org.evosuite.Properties;
import org.evosuite.TimeController;
import org.evosuite.coverage.mutation.Mutation;
import org.evosuite.coverage.mutation.MutationTimeoutStoppingCondition;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.utils.LoggingUtils;
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.*;

public class JuampiMutationAssertionGenerator extends  SimpleMutationAssertionGenerator{

    private final static Logger logger = LoggerFactory.getLogger(JuampiMutationAssertionGenerator.class);

    public void addAssertions(TestSuiteChromosome suite) {
        LoggingUtils.getEvoLogger().info("JUAMPI MUTATION ASSERTION STRATEGY");
        // this call to the add assertion for each test case, and track killed mutants
        super.addAssertions(suite);
        // At this point all the test cases have assertions
        // And the assertion knows which mutant target to kill
        Map<TestCase, LinkedHashSet<Assertion>> filteredAssertions = filterAssertionByTest(suite);
        for (Map.Entry<TestCase, LinkedHashSet<Assertion>> entry : filteredAssertions.entrySet()) {
            TestCase test = entry.getKey();
            LinkedHashSet<Assertion> result = entry.getValue();
            // Remove all current assertions from the test
            test.removeAssertions();

            // Option A: reattach each assertion to its original statement
            for (Assertion assertion : result) {
                assertion.getStatement().addAssertion(assertion);
            }

        }
    }



    protected void addAssertions(TestCase test, Set<Integer> killed) {
        addAssertions(test, killed, mutants);
        //filterRedundantNonnullAssertions(test);
    }

    protected void minimize(TestCase test, List<Mutation> mutants, final List<Assertion> assertions, Map<Integer, Set<Integer>> killMap) {
        // No minimization at TestCase level
        // The minimization would be at suite level
    }

    public Map<TestCase, LinkedHashSet<Assertion>> filterAssertionByTest(TestSuiteChromosome suite) {
        // this is the result
        Map<TestCase, LinkedHashSet<Assertion>> finalAssertionsByTest = new LinkedHashMap<>();

        Set<TestCase> testBag = new LinkedHashSet<>(suite.getTests());
        Set<Mutation> mutantsBag = new LinkedHashSet<>();
        Map<Assertion, TestCase> assertionBag = new LinkedHashMap<>();
        Map<TestCase, LinkedHashSet<Mutation>> mutantsKilled = new LinkedHashMap<>();


        // 1) Filling: mutantsKilled, mutantsBag, assertionBag
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

        Map<TestCase, List<Assertion>> assertionsByTest = new LinkedHashMap<>();
        for (Map.Entry<Assertion, TestCase> e : assertionBag.entrySet()) {
            Assertion a = e.getKey();
            TestCase t = e.getValue();
            List<Assertion> list = assertionsByTest.get(t);
            if (list == null) {
                list = new ArrayList<>();
                assertionsByTest.put(t,list );
            }
            list.add(a);
        }

        Map<Assertion, Set<Mutation>> mutantsByAssertion = new LinkedHashMap<>();
        for (Assertion a : assertionBag.keySet()) {
            mutantsByAssertion.put(a, new LinkedHashSet<>(a.getKilledMutations()));
        }

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

        Set<Assertion> chosenAssertions = new HashSet<>();

        // Greedy: We chose assertions as long we have mutants in the bag
        // the bag contain mutants that are not killed by any chosen assertion yet.
        while (!mutantsBag.isEmpty()) {

            Assertion bestAssertion = null;
            TestCase bestTest = null;

            int bestGlobalUnique = -1;              // prioridad 1
            int bestTestChosenCount = Integer.MAX_VALUE; // prioridad 2 (balance entre tests)
            int bestContribution = -1;              // prioridad 3
            int bestUniqueInTest = -1;              // prioridad 4 (local dentro del test)

            // Search the priority assertion in the test suite
            for (TestCase test : testBag) {
                List<Assertion> assertions = assertionsByTest.get(test);
                if (assertions == null || assertions.isEmpty()) {
                    continue;
                }

                int chosenCountForTest =  finalAssertionsByTest.get(test).size();

                for (Assertion assertion : assertions) {
                    Set<Mutation> killedByAssert = mutantsByAssertion.get(assertion);
                    if (killedByAssert == null || killedByAssert.isEmpty()) {
                        continue;
                    }

                    // 1) Contribución: killed mutants not killed by the assertions already choosen
                    int contribution = 0;
                    for (Mutation m : killedByAssert) {
                        if (mutantsBag.contains(m)) {
                            contribution++;
                        }
                    }
                    if (contribution == 0) { continue; } // this assertion is discarted as no add the mutant score

                    // 2) Unique mutants along the suite that this assertion kills
                    int globalUnique = 0;
                    for (Mutation m : killedByAssert) {
                        if (!mutantsBag.contains(m)) { continue; } // we only consider mutants already not killed by the choosen
                        List<Assertion> killers = mutantToAssertions.get(m);
                        if (killers != null && killers.size() == 1 && killers.get(0) == assertion) {
                            globalUnique++;
                        }
                    }

                    // 3) Unique mutants in the same tests
                    int uniqueInThisTest = 0;
                    for (Mutation m : killedByAssert) {
                        if (!mutantsBag.contains(m)) { continue; }
                        boolean killedByOtherAssertInSameTest = false;
                        for (Assertion other : assertions) {
                            if (other == assertion) {
                                continue;
                            }
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

                    // ---- GREEDY CRITERIA ----
                    // 1) larger globalUnique (across all tests)  <-- highest priority
                    // 2) if tied, prefer the test with fewer already–selected assertions (balance)
                    // 3) if still tied, larger contribution (kills more remaining mutants)
                    // 4) if still tied, larger uniqueInThisTest

                    boolean better = false;

                    if (globalUnique > bestGlobalUnique) {
                        better = true;
                    } else if (globalUnique == bestGlobalUnique) {
                        if (chosenCountForTest < bestTestChosenCount) {
                            better = true;
                        } else if (chosenCountForTest == bestTestChosenCount) {
                            if (contribution > bestContribution) {
                                better = true;
                            } else if (contribution == bestContribution &&
                                    uniqueInThisTest > bestUniqueInTest) {
                                better = true;
                            }
                        }
                    }

                    if (better) {
                        bestAssertion = assertion;
                        bestTest = test;
                        bestGlobalUnique = globalUnique;
                        bestTestChosenCount = chosenCountForTest;
                        bestContribution = contribution;
                        bestUniqueInTest = uniqueInThisTest;
                    }
                }
            }

            // If we did not find any assertion that kill the remaining mutants in the bag, we are done
            if (bestAssertion == null) {
                break;
            }


            System.out.println("BEST ASSERTION"+ bestAssertion.toString());

            // 4) We choose the best assertion found
            chosenAssertions.add(bestAssertion);
            finalAssertionsByTest.get(bestTest).add(bestAssertion);
            assertionsByTest.get(bestTest).remove(bestAssertion); // deleting it so it is not consider in the next iteration

            // We delete the mutants killed from the bag
            Set<Mutation> killedByBest = mutantsByAssertion.get(bestAssertion);
            if (killedByBest != null) {
                mutantsBag.removeAll(killedByBest);
            }
        }

        // 6) As not all goals are mutation score, lets try to maintain assert related to the unique goals in the test
        //    1) search the goals unique in these test,
        //    2) try to add the assertion most related to the goal
        for (TestCase test : testBag) {

            LinkedHashSet<Assertion> testAssertions = finalAssertionsByTest.get(test);
            Set<TestFitnessFunction> goalsThisTest = test.getCoveredGoals();

            Set<TestFitnessFunction> goalsOtherTests = new HashSet<>();
            for (TestCase other : testBag) {
                if (other == test) {
                    continue;
                }
                Set<TestFitnessFunction> otherGoals = other.getCoveredGoals();
                goalsOtherTests.addAll(otherGoals);
            }

            // Goals exclusivos de este test
            Set<TestFitnessFunction> uniqueGoals = new HashSet<>(goalsThisTest);
            uniqueGoals.removeAll(goalsOtherTests);

            Set<Assertion> assertionsRelatedToUniqueGoals = findAssertionFor(uniqueGoals,test);
            // the duplicates will be deleted
            testAssertions.addAll(assertionsRelatedToUniqueGoals);

        }

        return finalAssertionsByTest;
    }
    private Set<Assertion> findAssertionFor(Set<TestFitnessFunction> uniqueGoals,TestCase test){
        Set<Assertion> result = new HashSet<>();
        for(TestFitnessFunction goal : uniqueGoals) {
            Assertion best = null;
            int minDistance = Integer.MAX_VALUE;
            for (Assertion ass : test.getAssertions()) {
                int distance = Integer.MAX_VALUE;
                if(goal instanceof LineCoverageTestFitness){
                    distance = assertDistanceLineGoal((LineCoverageTestFitness) goal,ass);
                } else if(goal instanceof BranchCoverageTestFitness){
                    distance = assertDistanceBranchGoal((BranchCoverageTestFitness)goal,ass);
                } else if(goal instanceof ExceptionCoverageTestFitness){
                    distance = assertDistanceExceptionGoal((ExceptionCoverageTestFitness)goal,ass);
                } else if(goal instanceof MutationTestFitness){
                    distance = assertDistanceMutationGoal((MutationTestFitness)goal,ass);
                } else if(goal instanceof OutputCoverageTestFitness){
                    distance = assertDistanceOutputGoal((OutputCoverageTestFitness)goal,ass);
                } else if(goal instanceof MethodCoverageTestFitness){
                    distance = assertDistanceMethodGoal((MethodCoverageTestFitness)goal,ass);
                } else if(goal instanceof MethodNoExceptionCoverageTestFitness){
                    distance = assertDistanceMethodNoExceptionGoal((MethodNoExceptionCoverageTestFitness)goal,ass);
                } else{

                }
                if(distance< minDistance){
                    minDistance=distance;
                    best = ass;
                }
            }
            if(best!=null){
                result.add(best);
            }
        }
        return result;
    }


    private int assertDistanceLineGoal(LineCoverageTestFitness goal, Assertion a){
        int min = Integer.MAX_VALUE;
        for(Mutation m: a.getKilledMutations()){
            int distance = m.getLineNumber() - goal.getLine();
            if(distance < min)
                min = distance;
        }
        return min;
    }

    private int assertDistanceExceptionGoal(ExceptionCoverageTestFitness goal, Assertion a){
        int min = Integer.MAX_VALUE;
        for(Mutation m: a.getKilledMutations()){
            if(m.getMethodName().equals(goal.getMethod())){
                min = 0;
                break;
            }
        }
        return min;
    }

    private int assertDistanceMutationGoal(MutationTestFitness goal, Assertion a){
        int min = Integer.MAX_VALUE;
        for(Mutation m: a.getKilledMutations()){
            if(goal.getMutation().getId() == m.getId()){
                min =0;
                break;
            }
            int distance = m.getLineNumber() - goal.getMutation().getLineNumber();
            if(distance < min)
                min = distance;
        }
        return min;
    }
    private int assertDistanceOutputGoal(OutputCoverageTestFitness goal, Assertion a) {
        int min = Integer.MAX_VALUE;
        for(Mutation m: a.getKilledMutations()){
            if(goal.getTargetMethod().equals(m.getMethodName())){
                min =0;
                break;
            }
        }
        return min;
    }

    private int assertDistanceMethodGoal(MethodCoverageTestFitness goal, Assertion a) {
        int min = Integer.MAX_VALUE;
        for(Mutation m: a.getKilledMutations()){
            if(goal.getTargetMethod().equals(m.getMethodName())){
                min =0;
                break;
            }
        }
        return min;
    }
    private int assertDistanceMethodNoExceptionGoal(MethodNoExceptionCoverageTestFitness goal, Assertion a) {
        int min = Integer.MAX_VALUE;
        for(Mutation m: a.getKilledMutations()){
            if(goal.getTargetMethod().equals(m.getMethodName())){
                min =0;
                break;
            }
        }
        return min;
    }

    private int assertDistanceBranchGoal(BranchCoverageTestFitness goal, Assertion a) {
        int min = Integer.MAX_VALUE;
        for(Mutation m: a.getKilledMutations()){
            int distance = m.getLineNumber() - goal.getBranchGoal().getLineNumber();
            if(distance < min)
                min = distance;
        }
        return min;
    }




}
