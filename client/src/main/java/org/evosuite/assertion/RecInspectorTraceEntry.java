package org.evosuite.assertion;

import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.TreeMap;
import java.util.TreeSet;

import java.util.Map;
import java.util.Set;


/**
 * <p>InspectorTraceEntry class.</p>
 *
 * @author fraser
 */
public class RecInspectorTraceEntry implements OutputTraceEntry {

    private final static Logger logger = LoggerFactory.getLogger(org.evosuite.assertion.InspectorTraceEntry.class);
    private final Map<RecComposeInspector, Object> inspectorMap = new TreeMap<>();
    private final Map<String, RecComposeInspector> methodInspectorMap = new TreeMap<>();
    private final VariableReference var;

    /**
     * <p>Constructor for InspectorTraceEntry.</p>
     *
     * @param var a {@link org.evosuite.testcase.variable.VariableReference} object.
     */
    public RecInspectorTraceEntry(VariableReference var) {
        this.var = var;
    }

    /**
     * <p>addValue</p>
     *
     * @param inspector a {@link org.evosuite.assertion.Inspector} object.
     * @param value     a {@link java.lang.Object} object.
     */
    public void addValue(RecComposeInspector inspector, Object value) {
        LoggingUtils.getEvoLogger().info(inspector.getMethodCalls()+" => "+ value.toString());
        inspectorMap.put(inspector, value);
        methodInspectorMap.put(inspector.getClassName() + " " + inspector.getMethodCalls(), inspector);
    }

    /**
     * <p>size</p>
     *
     * @return a int.
     */
    public int size() {
        return inspectorMap.size();
    }

    /* (non-Javadoc)
     * @see org.evosuite.assertion.OutputTraceEntry#differs(org.evosuite.assertion.OutputTraceEntry)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean differs(OutputTraceEntry other) {
        if (other instanceof org.evosuite.assertion.RecInspectorTraceEntry) {
            if (!((RecInspectorTraceEntry) other).var.equals(var)) {
                return false;
            }

            RecInspectorTraceEntry otherEntry = (RecInspectorTraceEntry) other;
            for (RecComposeInspector inspector : inspectorMap.keySet()) {
                logger.debug("Current inspector: " + inspector);
                if (!otherEntry.inspectorMap.containsKey(inspector)
                        || otherEntry.inspectorMap.get(inspector) == null
                        || inspectorMap.get(inspector) == null) {
                    logger.debug("Other trace does not have " + inspector);
                    continue;
                }

                if (!otherEntry.inspectorMap.get(inspector).equals(inspectorMap.get(inspector))) {
                    return true;
                } else {
                    logger.debug("Value is equal: " + inspector);
                }
            }

        }
        return false;
    }

    /* (non-Javadoc)
     * @see org.evosuite.assertion.OutputTraceEntry#getAssertions(org.evosuite.assertion.OutputTraceEntry)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<Assertion> getAssertions(OutputTraceEntry other) {
        Set<Assertion> assertions = new TreeSet<>();

        if (other instanceof org.evosuite.assertion.InspectorTraceEntry) {
            RecInspectorTraceEntry otherEntry = (RecInspectorTraceEntry) other;
            for (String inspector : methodInspectorMap.keySet()) {
                if (!otherEntry.inspectorMap.containsKey(otherEntry.methodInspectorMap.get(inspector))
                        || otherEntry.inspectorMap.get(otherEntry.methodInspectorMap.get(inspector)) == null
                        || inspectorMap.get(methodInspectorMap.get(inspector)) == null) {
                    continue;
                }

                if (!otherEntry.inspectorMap.get(otherEntry.methodInspectorMap.get(inspector))
                        .equals(inspectorMap.get(methodInspectorMap.get(inspector)))) {
                    RecInspectorAssertion assertion = new RecInspectorAssertion();
                    assertion.value = inspectorMap.get(methodInspectorMap.get(inspector));
                    assertion.inspector = methodInspectorMap.get(inspector);
                    assertion.source = var;
                    assertions.add(assertion);
                    assert (assertion.isValid());

                }
            }
        }
        return assertions;
    }

    /* (non-Javadoc)
     * @see org.evosuite.assertion.OutputTraceEntry#getAssertions()
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<Assertion> getAssertions() {

        Set<Assertion> assertions = new TreeSet<>();

        LoggingUtils.getEvoLogger().info("---- RecInspectorTraceEntry>>getAssertions  "+ inspectorMap.keySet().size());
        for (RecComposeInspector inspector : inspectorMap.keySet()) {

            RecInspectorAssertion assertion = new RecInspectorAssertion();
            assertion.value = inspectorMap.get(inspector);
            assertion.inspector = inspector;
            assertion.source = var;
            assertions.add(assertion);
            LoggingUtils.getEvoLogger().info("assertion - +++++  "+assertion.getCode());
            assert (assertion.isValid());
        }
        return assertions;
    }

    /* (non-Javadoc)
     * @see org.evosuite.assertion.OutputTraceEntry#isDetectedBy(org.evosuite.assertion.Assertion)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isDetectedBy(Assertion assertion) {
        if (assertion instanceof InspectorAssertion) {
            RecInspectorAssertion ass = (RecInspectorAssertion) assertion;
            if (ass.source.same(var)) {
                if (inspectorMap.containsKey(ass.inspector)
                        && inspectorMap.get(ass.inspector) != null && ass.value != null) {
                    return !inspectorMap.get(ass.inspector).equals(ass.value);
                }
            }
        }
        return false;
    }

    /* (non-Javadoc)
     * @see org.evosuite.assertion.OutputTraceEntry#cloneEntry()
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public OutputTraceEntry cloneEntry() {
        RecInspectorTraceEntry copy = new RecInspectorTraceEntry(var);
        copy.inspectorMap.putAll(inspectorMap);
        copy.methodInspectorMap.putAll(methodInspectorMap);
        return copy;
    }

}
