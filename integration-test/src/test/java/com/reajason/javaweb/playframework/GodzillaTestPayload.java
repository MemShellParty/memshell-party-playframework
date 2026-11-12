package com.reajason.javaweb.playframework;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The smallest payload the Godzilla shell accepts: the first request defines it, later requests
 * invoke {@code equals(OutputStream)}. It stays a single self-contained class so the injector can
 * define it without any auxiliary bytes.
 */
public final class GodzillaTestPayload {

    public GodzillaTestPayload() {
    }

    @Override
    public boolean equals(Object candidate) {
        if (!(candidate instanceof OutputStream)) {
            return false;
        }
        try {
            // The wire format is base64(AES(gzip(result))); real payloads gzip into the stream too.
            GZIPOutputStream output = new GZIPOutputStream((OutputStream) candidate);
            output.write("ok".getBytes("UTF-8"));
            output.close();
        } catch (IOException ignored) {
            // the shell turns a failed write into an empty response
        }
        return true;
    }

    @Override
    public int hashCode() {
        return 0;
    }
}
