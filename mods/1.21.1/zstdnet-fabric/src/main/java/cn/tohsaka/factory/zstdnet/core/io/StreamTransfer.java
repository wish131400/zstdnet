package cn.tohsaka.factory.zstdnet.core.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class StreamTransfer {
    private static final int DEFAULT_BUFFER_SIZE = 16 * 1024;

    private StreamTransfer() {
    }

    public static void copyAndFlush(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            if (read > 0) {
                out.write(buffer, 0, read);
                out.flush();
            }
        }
    }
}
