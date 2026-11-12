package com.reajason.javaweb.playframework.range;

import play.BuiltInComponents;
import play.Mode;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.Results;
import play.routing.RoutingDsl;
import play.server.Server;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Base64;

/**
 * Minimal Play server used as the injection target. Both the Akka (Play 2.x) and Pekko
 * (Play 3.x) modules compile this one source file against their own Play version, so the
 * range pair stays behaviourally identical.
 *
 * <p>{@code POST /vul/load} is the deliberate foothold: it defines and instantiates a class
 * from a base64 class file, standing in for the expression-execution entry point that a real
 * engagement would use to run the generated injector.
 */
public final class PlayframeworkRangeApplication {

    public static void main(String[] args) throws Exception {
        int port = Integer.getInteger("range.port", 0);
        Server server = Server.forRouter(Mode.TEST, port, PlayframeworkRangeApplication::router);
        System.out.println("RANGE_LISTENING " + server.httpPort());
        System.out.flush();
        Thread.currentThread().join();
    }

    private static play.routing.Router router(BuiltInComponents components) {
        RoutingDsl dsl = RoutingDsl.fromComponents(components);
        dsl.GET("/health").routingTo((Http.Request request) -> Results.ok("ok"));
        dsl.POST("/vul/load").routingTo((Http.Request request) -> load(request));
        return dsl.build();
    }

    private static Result load(Http.Request request) {
        try {
            byte[] classBytes = Base64.getDecoder().decode(request.body().asBytes().toArray());
            ClassLoader parent = Thread.currentThread().getContextClassLoader();
            Class<?> injected = new ByteLoader(parent).define(classBytes);
            injected.getDeclaredConstructor().newInstance();
            return Results.ok("loaded " + injected.getName());
        } catch (Throwable e) {
            StringWriter stack = new StringWriter();
            e.printStackTrace(new PrintWriter(stack));
            System.out.println(stack);
            return Results.internalServerError(stack.toString());
        }
    }

    private static final class ByteLoader extends ClassLoader {
        private ByteLoader(ClassLoader parent) {
            super(parent);
        }

        private Class<?> define(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }

    private PlayframeworkRangeApplication() {
    }
}
