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
        fillInspectors(entry, scope, var, emptyInspector, statement, 0, var.getVariableClass());
        trace.addEntry(statement.getPosition(), var, entry);

    }

    public void fillInspectors(RecInspectorTraceEntry entry, Scope scope, VariableReference var, RecComposeInspector lastInspector, Statement statement, int level, Class<?> lastClass){
        if(level>1) return;
        fillPrimitiveInspectors(entry, scope, var, lastInspector, statement, level,lastClass);
        fillObjectInspectors(entry, scope, var, lastInspector, statement, level, lastClass);

    }

    public void fillPrimitiveInspectors(RecInspectorTraceEntry entry,     Scope scope, VariableReference var, RecComposeInspector lastInspector, Statement statement, int level, Class<?> lastClass){
        // el getClazz de inspector devuelve la clase del ultimo getter
        lastInspector.printMethods();
        List<Inspector> inspectors = InspectorManager.getInstance().getInspectors(lastClass);
        for (Inspector i : inspectors) {
            fillPrimitiveInspector(entry, scope,var, statement,lastInspector.extend(i.getMethod()));
        }
    }
    public void fillObjectInspectors(RecInspectorTraceEntry entry,Scope scope, VariableReference var, RecComposeInspector lastInspector, Statement statement, int level, Class<?> lastClass) {
        // el getClazz de inspector devuelve la clase del ultimo getter
        List<Inspector> objectInspectors = RecInspectorManager.getInstance().getInspectors(lastClass);
        for (Inspector oi : objectInspectors) {
            try {
                RecComposeInspector ins = lastInspector.extend(oi.getMethod());
                Object target = var.getObject(scope);
                Object lastValue = ins.getValue(target);
                fillInspectors(entry, scope, var, ins, statement, level + 1,lastValue.getClass());
            }catch (Exception e){
                LoggingUtils.getEvoLogger().info(e.getMessage());
            }
        }
    }

    public void fillPrimitiveInspector(RecInspectorTraceEntry entry,Scope scope, VariableReference var,Statement statement, RecComposeInspector i){

        if (i.hasAMethodFromObjectClass())
            return ;
        try {
            Object target = var.getObject(scope);
            if (target != null) {
                // Don't call inspector methods on mock objects
                if (target.getClass().getCanonicalName().contains("EnhancerByMockito"))
                    return;
                //el problema es que ejecutaremos muchas veces el mismo getter, y tardara mas
                Object value = i.getValue(target);
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

                //LoggingUtils.getEvoLogger().info("inspector: " + i.getMethodCalls()+ " value: "+ value.toString());
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
        } finally {
            logger.debug("Found " + entry.size() + " inspectors for " + var
                    + " at statement " + statement.getPosition());

        }




    }

    @Override
    public void testExecutionFinished(ExecutionResult r, Scope s) {
        // do nothing
    }
}
