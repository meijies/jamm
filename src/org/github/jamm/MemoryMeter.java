package org.github.jamm;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.Callable;

public class MemoryMeter {
    private static Instrumentation inst;

    public static void premain(String options, Instrumentation inst) {
        MemoryMeter.inst = inst;
    }

    private final Callable<Set<Object>> trackerProvider;

    public MemoryMeter() {
        this(new Callable<Set<Object>>() {
            public Set<Object> call() throws Exception {
                // using a normal HashSet to track seen objects screws things up in two ways:
                // - it can undercount objects that are "equal"
                // - calling equals() can actually change object state (e.g. creating entrySet in HashMap)
                return Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            }
        });
    }

    /**
     * @param trackerProvider returns a Set with which to track seen objects and avoid cycles
     */
    public MemoryMeter(Callable<Set<Object>> trackerProvider) {
        this.trackerProvider = trackerProvider;
    }

    /**
     * @return the shallow memory usage of @param object
     * @throws NullPointerException if object is null
     */
    public long measure(Object object) {
        if (inst == null) {
            throw new IllegalStateException("Instrumentation is not set; Jamm must be set as -javaagent");
        }
        return inst.getObjectSize(object);
    }

    /**
     * @return the memory usage of @param object including referenced objects
     * @throws NullPointerException if object is null
     */
    public long measureDeep(Object object) {
        if (object == null) {
            throw new NullPointerException(); // match getObjectSize behavior
        }

        Set<Object> tracker;
        try {
            tracker = trackerProvider.call();
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }

        tracker.add(object);

        // track stack manually so we can handle deeper heirarchies than recursion
        Stack<Object> stack = new Stack<Object>();
        stack.push(object);

        long total = 0;
        while (!stack.isEmpty()) {
            Object current = stack.pop();
            assert current != null;
            total += measure(current);

            if (current instanceof Object[]) {
                addArrayChildren((Object[]) current, stack, tracker);
            }
            else {
                addFieldChildren(current, stack, tracker);
            }
        }

        return total;
    }

    /**
     * @return the number of child objects referenced by @param object
     * @throws NullPointerException if object is null
     */
    public long countChildren(Object object) {
        if (object == null) {
            throw new NullPointerException();
        }

        Set<Object> tracker = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        tracker.add(object);
        Stack<Object> stack = new Stack<Object>();
        stack.push(object);

        long total = 0;
        while (!stack.isEmpty()) {
            Object current = stack.pop();
            assert current != null;
            total++;

            if (current instanceof Object[]) {
                addArrayChildren((Object[]) current, stack, tracker);
            }
            else {
                addFieldChildren(current, stack, tracker);
            }
        }

        return total;
    }

    private void addFieldChildren(Object current, Stack<Object> stack, Set<Object> seen) {
        Class cls = current.getClass();
        while (cls != null) {
            for (Field field : cls.getDeclaredFields()) {
                if (field.getType().isPrimitive() || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }

                field.setAccessible(true);
                Object child;
                try {
                    child = field.get(current);
                }
                catch (IllegalArgumentException e) {
                    throw new RuntimeException(e);
                }
                catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }

                if (child != null && !seen.contains(child)) {
                    stack.push(child);
                    seen.add(child);
                }
            }

            cls = cls.getSuperclass();
        }
    }

    private void addArrayChildren(Object[] current, Stack<Object> stack, Set<Object> tracker) {
        for (Object child : current) {
            if (child != null && !tracker.contains(child)) {
                stack.push(child);
                tracker.add(child);
            }
        }
    }
}
