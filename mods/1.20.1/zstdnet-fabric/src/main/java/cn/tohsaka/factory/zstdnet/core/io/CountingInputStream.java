package cn.tohsaka.factory.zstdnet.core.io;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public final class CountingInputStream extends InputStream {
    private final InputStream delegate;
    private final ByteCounter counter;

    public CountingInputStream(InputStream delegate, ByteCounter counter) {
        this.delegate = Objects.requireNonNull(delegate);
        this.counter = Objects.requireNonNull(counter);
    }

    @Override
    public int read() throws IOException {
        int value = delegate.read();
        if (value >= 0) {
            counter.add(1);
        }
        return value;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int read = delegate.read(b, off, len);
        if (read > 0) {
            counter.add(read);
        }
        return read;
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
