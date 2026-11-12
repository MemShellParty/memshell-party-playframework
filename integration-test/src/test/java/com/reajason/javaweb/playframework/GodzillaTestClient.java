package com.reajason.javaweb.playframework;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.zip.GZIPInputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Headless Godzilla client for the integration test. The published Godzilla artifact is the
 * desktop application, so the test speaks the wire protocol directly: AES payload in a form
 * field, md5-wrapped AES+GZIP response.
 */
final class GodzillaTestClient implements AutoCloseable {

    private final String entrypoint;
    private final String pass;
    private final String key;
    private final String md5;
    private final String headerName;
    private final String headerValue;

    GodzillaTestClient(String entrypoint, String key, String pass, String headerName, String headerValue) {
        this.entrypoint = entrypoint;
        this.pass = pass;
        this.key = md5Hex(key).substring(0, 16);
        this.md5 = md5Hex(pass + this.key).toUpperCase();
        this.headerName = headerName;
        this.headerValue = headerValue;
    }

    /**
     * First request: deliver the payload class. The shell defines it and answers with an empty body.
     */
    boolean start() throws IOException {
        return post(readClass(GodzillaTestPayload.class)).status == 200;
    }

    /**
     * Second request: the shell now runs the defined payload, which writes {@code ok}.
     */
    boolean test() throws IOException {
        Response response = post("ping".getBytes(StandardCharsets.UTF_8));
        return response.status == 200 && "ok".equals(decode(response.body));
    }

    private String decode(String body) {
        if (body == null || body.length() < 32) {
            return null;
        }
        String left = md5.substring(0, 16);
        String right = md5.substring(16);
        int start = body.indexOf(left);
        if (start < 0) {
            return null;
        }
        start += left.length();
        int end = body.indexOf(right, start);
        if (end < 0) {
            return null;
        }
        try {
            byte[] compressed = transform(Base64.getDecoder().decode(body.substring(start, end)), false);
            try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                return new String(readAll(input), StandardCharsets.UTF_8).trim();
            }
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    private Response post(byte[] plain) throws IOException {
        String payload = Base64.getEncoder().encodeToString(transform(plain, true));
        byte[] form = (urlEncode(pass) + "=" + urlEncode(payload)).getBytes(StandardCharsets.UTF_8);

        HttpURLConnection connection = (HttpURLConnection) new URL(entrypoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        connection.setRequestProperty(headerName, headerValue);
        connection.setFixedLengthStreamingMode(form.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(form);
        }

        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = stream == null ? "" : new String(readAll(stream), StandardCharsets.UTF_8);
        connection.disconnect();
        return new Response(status, body);
    }

    private byte[] transform(byte[] value, boolean encrypt) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"));
            return cipher.doFinal(value);
        } catch (Exception e) {
            throw new IOException("AES failed", e);
        }
    }

    private static String urlEncode(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static byte[] readClass(Class<?> type) throws IOException {
        try (InputStream input = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            if (input == null) {
                throw new IOException("payload class not found: " + type.getName());
            }
            return readAll(input);
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) > 0) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String md5Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                text.append(String.format("%02x", item));
            }
            return text.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        // HttpURLConnection is disconnected per request.
    }

    private static final class Response {
        private final int status;
        private final String body;

        private Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}
