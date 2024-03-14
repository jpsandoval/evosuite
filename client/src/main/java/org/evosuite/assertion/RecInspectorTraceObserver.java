package org.evosuite.assertion;

import org.evosuite.Properties;
import org.evosuite.runtime.mock.EvoSuiteMock;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;
import java.lang.reflect.Method;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

public class RecInspectorTraceObserver extends AssertionTraceObserver<RecInspectorTraceEntry> {

    private static final Pattern addressPattern = Pattern.compile(".*[\\w+\\.]+@[abcdef\\d]+.*", Pattern.MULTILINE);


    /* (non-Javadoc)
     * @see org.evosuite.assertion.AssertionTraceObserver#visit(org.evosuite.testcase.StatementInterface, org.evosuite.testcase.Scope, org.evosuite.testcase.VariableReference)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    protected void visit(Statement statement, Scope scope, VariableReference var) {
        // TODO: Check the variable class is complex?

        // We don't want inspector checks on string constants
        Statement declaringStatement = currentTest.getStatement(var.getStPosition());
        if (declaringStatement instanceof PrimitiveStatement<?>)
            return;

        if (statement.isAssignmentStatement() && statement.getReturnValue().isArrayIndex())
            return;

        if (statement instanceof ConstructorStatement) {
            if (statement.getReturnValue().isWrapperType() || statement.getReturnValue().isAssignableTo(EvoSuiteMock.class))
                return;
        }

        if (var.isPrimitive() || var.isString() || var.isWrapperType())
            return;

        logger.debug("Checking for inspectors of " + var + " at statement "
                + statement.getPosition());


        List<Inspector> inspectors = InspectorManager.getInstance().getInspectors(var.getVariableClass());

        RecInspectorTraceEntry entry = new RecInspectorTraceEntry(var);

        for (Inspector i : inspectors) {

            // No inspectors from java.lang.Object
            if (i.getMethod().getDeclaringClass().equals(Object.class))
                continue;

            try {
                Object target = var.getObject(scope);
                if (target != null) {

                    // Don't call inspector methods on mock objects
                    if (target.getClass().getCanonicalName().contains("EnhancerByMockito"))
                        return;

                    Object value = i.getValue(target);
                    logger.debug("Inspector " + i.getMethodCall() + " is: " + value);

                    // We need no assertions that include the memory location
                    if (value instanceof String) {
                        // String literals may not be longer than 32767
                        if (((String) value).length() >= 32767)
                            continue;

                        // Maximum length of strings we look at
                        if (((String) value).length() > Properties.MAX_STRING)
                            continue;

                        // If we suspect an Object hashCode not use this, as it may lead to flaky tests
                        if (addressPattern.matcher((String) value).find())
                            continue;
                        // The word "hashCode" is also suspicious
                        if (((String) value).toLowerCase().contains("hashcode"))
                            continue;
                        // Avoid asserting anything on values referring to mockito proxy objects
                        if (((String) value).toLowerCase().contains("EnhancerByMockito"))
                            continue;
                        if (((String) value).toLowerCase().contains("$MockitoMock$"))
                            continue;

                        if (target instanceof URL) {
                            // Absolute paths may be different between executions
                            if (((String) value).startsWith("/") || ((String) value).startsWith("file:/"))
                                continue;
                        }
                    }
                    ArrayList<Method> list = new ArrayList<Method>();
                    list.add(i.getMethod());
                    RecComposeInspector ri = new RecComposeInspector(var.getVariableClass(),list);
                    entry.addValue(ri, value);
                }
            } catch (Exception e) {
                if (e instanceof TimeoutException) {
                    logger.debug("Timeout during inspector call - deactivating inspector "
                            + i.getMethodCall());
                    InspectorManager.getInstance().removeInspector(var.getVariableClass(), i);
                }
                logger.debug("Exception " + e + " / " + e.getCause());
                if (e.getCause() != null
                        && !e.getCause().getClass().equals(NullPointerException.class)) {
                    logger.debug("Exception during call to inspector: " + e + " - "
                            + e.getCause());
                }
            }
        }
        logger.debug("Found " + entry.size() + " inspectors for " + var
                + " at statement " + statement.getPosition());

        trace.addEntry(statement.getPosition(), var, entry);

    }

    @Override
    public void testExecutionFinished(ExecutionResult r, Scope s) {
        // do nothing
    }
}
