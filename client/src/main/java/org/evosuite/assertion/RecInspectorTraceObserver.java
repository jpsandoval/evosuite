package org.evosuite.assertion;

import org.evosuite.Properties;
import org.evosuite.runtime.mock.EvoSuiteMock;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.LoggingUtils;

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



        RecInspectorTraceEntry entry = new RecInspectorTraceEntry(var);
        RecComposeInspector emptyInspector = new RecComposeInspector(var.getVariableClass());
        fillInjectors(entry, scope, var, emptyInspector, statement, 0);
        trace.addEntry(statement.getPosition(), var, entry);

    }

    public void fillInjectors(RecInspectorTraceEntry entry,     Scope scope, VariableReference var, RecComposeInspector lastInspector, Statement statement, int level){
        if(level>1) return;
        LoggingUtils.getEvoLogger().info("current: "+lastInspector.getMethodCalls());
        fillPrimitiveInjectors(entry, scope, var, lastInspector, statement, level);
        fillObjectInjectors(entry, scope, var, lastInspector, statement, level);
    }

    public void fillPrimitiveInjectors(RecInspectorTraceEntry entry,     Scope scope, VariableReference var, RecComposeInspector lastInspector, Statement statement, int level){
        // el getClazz de inspector devuelve la clase del ultimo getter
        List<Inspector> inspectors = InspectorManager.getInstance().getInspectors(lastInspector.getClazz());
        for (Inspector i : inspectors) {
            LoggingUtils.getEvoLogger().info("primitive "+i.getClassName()+">>"+i.getMethodCall());
            fillPrimitiveInspector(entry, scope,var, statement,lastInspector.extend(i.getMethod()));
        }
    }
    public void fillObjectInjectors(RecInspectorTraceEntry entry,Scope scope, VariableReference var, RecComposeInspector lastInspector, Statement statement, int level) {
        // el getClazz de inspector devuelve la clase del ultimo getter
        List<Inspector> objectInspectors = RecInspectorManager.getInstance().getInspectors(lastInspector.getClazz());
        for (Inspector oi : objectInspectors) {
            LoggingUtils.getEvoLogger().info("object "+oi.getClassName()+">>"+oi.getMethodCall());
            fillInjectors(entry, scope,var, lastInspector.extend(oi.getMethod()),statement, level+1);
        }
    }

    public void fillPrimitiveInspector(RecInspectorTraceEntry entry,Scope scope, VariableReference var,Statement statement, RecComposeInspector i){
        LoggingUtils.getEvoLogger().info(" A ");
        if (i.hasAMethodFromObjectClass())
            return;

        try {
            LoggingUtils.getEvoLogger().info(" B ");
            Object target = var.getObject(scope);
            LoggingUtils.getEvoLogger().info("target: "+target.toString());
            if (target != null) {
                // Don't call inspector methods on mock objects
                if (target.getClass().getCanonicalName().contains("EnhancerByMockito"))
                    return;
                //el problema es que ejecutaremos muchas veces el mismo getter, y tardara mas
                Object value = i.getValue(target);
                LoggingUtils.getEvoLogger().info("C");
                LoggingUtils.getEvoLogger().info("Inspector " + i.getMethodCalls() + " is: " + value.toString());
                // We need no assertions that include the memory location
                if (value instanceof String) {
                    // String literals may not be longer than 32767
                    if (((String) value).length() >= 32767)
                        return;
                    // Maximum length of strings we look at
                    if (((String) value).length() > Properties.MAX_STRING)
                        return;
                    // If we suspect an Object hashCode not use this, as it may lead to flaky tests
                    if (addressPattern.matcher((String) value).find())
                        return;
                    // The word "hashCode" is also suspicious
                    if (((String) value).toLowerCase().contains("hashcode"))
                        return;
                    // Avoid asserting anything on values referring to mockito proxy objects
                    if (((String) value).toLowerCase().contains("EnhancerByMockito"))
                        return;
                    if (((String) value).toLowerCase().contains("$MockitoMock$"))
                        return;
                    if (target instanceof URL) {
                        // Absolute paths may be different between executions
                        if (((String) value).startsWith("/") || ((String) value).startsWith("file:/"))
                            return;
                    }
                }

                LoggingUtils.getEvoLogger().info("inspector: " + i.getMethodCalls()+ " value: "+ value.toString());
                entry.addValue(i, value);

            }
        } catch (Exception e) {
            e.printStackTrace();

            if (e instanceof TimeoutException) {
                logger.debug("Timeout during inspector call - deactivating inspector "
                        + i.getMethodCalls());
                // aqui deberia borrarlo pero no lo hago :(
            }
            if (e.getCause() != null
                    && !e.getCause().getClass().equals(NullPointerException.class)) {
                logger.debug("Exception during call to inspector: " + e + " - "
                        + e.getCause());
            }
        }


        logger.debug("Found " + entry.size() + " inspectors for " + var
                + " at statement " + statement.getPosition());


    }

    @Override
    public void testExecutionFinished(ExecutionResult r, Scope s) {
        // do nothing
    }
}
