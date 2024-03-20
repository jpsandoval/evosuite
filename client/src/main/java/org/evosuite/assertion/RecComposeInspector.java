package org.evosuite.assertion;

import org.evosuite.TestGenerationContext;
import org.evosuite.runtime.sandbox.Sandbox;
import org.evosuite.setup.TestClusterUtils;
import org.evosuite.utils.LoggingUtils;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.List;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class RecComposeInspector implements Serializable{

    private static final long serialVersionUID = -6865880297202184953L;

    //receiver class
    private transient Class<?> clazz;

    //methods
    private transient List<Method> methods;


    /**
     * <p>
     * Constructor for Inspector.
     * </p>
     *
     * @param clazz a {@link java.lang.Class} object.
     */
    public RecComposeInspector(Class<?> clazz) {
        this.clazz = clazz;
        methods = new ArrayList<>();
    }



    public RecComposeInspector(Class<?> clazz, List<Method> ms) {
        this.clazz = clazz;
        methods = ms;
        for(Method m: methods) {
            m.setAccessible(true);
        }
    }

    /**
     * <p>
     * getValue
     * </p>
     *
     * @param object a {@link java.lang.Object} object.
     * @return a {@link java.lang.Object} object.
     * @throws java.lang.IllegalArgumentException          if any.
     * @throws java.lang.IllegalAccessException            if any.
     * @throws java.lang.reflect.InvocationTargetException if any.
     */
    public Object getValue(Object object) throws IllegalArgumentException,
            IllegalAccessException, InvocationTargetException {
        //LoggingUtils.getEvoLogger().info("*** RecComposeInspector>>getValue ***");
        boolean needsSandbox = !Sandbox.isOnAndExecutingSUTCode();
        boolean safe = Sandbox.isSafeToExecuteSUTCode();

        if (needsSandbox) {
            Sandbox.goingToExecuteSUTCode();
            TestGenerationContext.getInstance().goingToExecuteSUTCode();
            if (!safe)
                Sandbox.goingToExecuteUnsafeCodeOnSameThread();
        }
        Object ret = null;

        try {
            if (this.methods.size() > 0) {
                ret = object;
            }
            for (Method m : this.methods) {
                ret = m.invoke(ret);
            }
        } finally {
            if (needsSandbox) {
                if (!safe)
                    Sandbox.doneWithExecutingUnsafeCodeOnSameThread();
                Sandbox.doneWithExecutingSUTCode();
                TestGenerationContext.getInstance().doneWithExecutingSUTCode();
            }
        }

        return ret;
    }

    /**
     * <p>
     * Getter for the field <code>method</code>.
     * </p>
     *
     * @return a {@link java.lang.reflect.Method} object.
     */
    public List<Method> getMethods() {
        return methods;
    }


    public RecComposeInspector extend(Method method){
        List<Method> list= new ArrayList<>();
        list.addAll(methods);
        list.add(method);
        RecComposeInspector ni=new RecComposeInspector(clazz,list);
        return ni;
    }
    public void printMethods(){
        LoggingUtils.getEvoLogger().info("methods:");
        for(Method m: methods){
            LoggingUtils.getEvoLogger().info(m.getName());
        }
    }

    public boolean hasAMethodFromObjectClass(){
        for(Method m: methods){
            if(m.getDeclaringClass().equals(Object.class)){
                return true;
            }
        }
        return false;
    }


    /**
     * <p>
     * getMethodCall
     * </p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String getMethodCalls() {
        if(methods.isEmpty()) return "";

        StringBuilder call=new StringBuilder();
        int last =methods.size()-1;
        for(int i=0;i<last;i++){
            call.append(methods.get(i).getName()+"()");
            call.append(".");
        }
        call.append(methods.get(last).getName());
        return call.toString();
    }

    /**
     * <p>
     * getClassName
     * </p>
     *
     * @return a {@link java.lang.String} object.
     */

    public Class<?> getClazz(){return this.clazz;}
    public String getClassName() {
        return clazz.getName();
    }

    /**
     * <p>
     * getReturnType
     * </p>
     *
     * @return a {@link java.lang.Class} object.
     */
    public Class<?> getReturnType() {
        return methods.get(methods.size() - 1).getReturnType();
    }



    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        RecComposeInspector other = (RecComposeInspector) obj;
        if (clazz == null) {
            if (other.clazz != null)
                return false;
        } else if (!clazz.equals(other.clazz))
            return false;
        if (methods == null) {
            return other.methods == null;
        } else{
            return methods.equals(other.methods);
        }
    }

    public void changeClassLoader(ClassLoader loader){

        try {
            for(int i=0; i< this.methods.size();i++) {
                Method method = methods.get(i);
                Class<?> oldClass = method.getDeclaringClass();
                Class<?> newClass = loader.loadClass(oldClass.getName());
                for (Method newMethod : TestClusterUtils.getMethods(newClass)) {
                    if (newMethod.getName().equals(method.getName())) {
                        boolean equals = true;
                        Class<?>[] oldParameters = method.getParameterTypes();
                        Class<?>[] newParameters = newMethod.getParameterTypes();
                        if (oldParameters.length != newParameters.length)
                            continue;

                        if (!newMethod.getDeclaringClass().getName().equals(method.getDeclaringClass().getName()))
                            continue;

                        if (!newMethod.getReturnType().getName().equals(method.getReturnType().getName()))
                            continue;

                        for (int j = 0; j < newParameters.length; j++) {
                            if (!oldParameters[j].getName().equals(newParameters[i].getName())) {
                                equals = false;
                                break;
                            }
                        }
                        if (equals) {
                            this.methods.set(i,newMethod);
                            newMethod.setAccessible(true);
                            return;
                        }
                    }
                }
                LoggingUtils.getEvoLogger().info("Method not found - keeping old class loader ");
            }
        } catch (ClassNotFoundException e) {
            LoggingUtils.getEvoLogger().info("Class not found - keeping old class loader ", e);
        } catch (SecurityException e) {
            LoggingUtils.getEvoLogger().info("Class not found - keeping old class loader ", e);
        }
    }
    private void writeObject(ObjectOutputStream oos) throws IOException {
        oos.defaultWriteObject();
        // Write/save additional fields
        oos.writeObject(clazz.getName());
        oos.writeObject(new Integer(methods.size()));
        for(Method method: methods) {
            oos.writeObject(method.getDeclaringClass().getName());
            oos.writeObject(method.getName());
            oos.writeObject(Type.getMethodDescriptor(method));
        }
    }

    // assumes "static java.util.Date aDate;" declared
    private void readObject(ObjectInputStream ois) throws ClassNotFoundException, IOException {
        ois.defaultReadObject();
        // Read/initialize additional fields
        this.clazz = TestGenerationContext.getInstance().getClassLoaderForSUT().loadClass((String) ois.readObject());

        Integer numberOfMethods = (Integer) ois.readObject();
        methods = new ArrayList<>();

        for(int i=0;i< numberOfMethods;i++) {
            Class<?> methodClass = TestGenerationContext.getInstance().getClassLoaderForSUT().loadClass((String) ois.readObject());
            String methodName = (String) ois.readObject();
            String methodDesc = (String) ois.readObject();

            for (Method method : methodClass.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    if (Type.getMethodDescriptor(method).equals(methodDesc)) {
                        methods.add(method);
                        return;
                    }
                }
            }
        }
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((clazz == null) ? 0 : clazz.hashCode());
        if(methods!=null) {
            for (Method m : methods) {
                result = prime * result + ((m == null) ? 0 : m.hashCode());
            }
        }
        return result;
    }
}


