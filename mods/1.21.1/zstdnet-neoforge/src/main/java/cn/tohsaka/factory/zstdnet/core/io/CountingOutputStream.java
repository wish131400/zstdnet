package cn.tohsaka.factory.zstdnet.core.io;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

public final class CountingOutputStream extends OutputStream {
    private final OutputStream delegate;
    private final ByteCounter counter;

    public CountingOutputStream(OutputStream delegate, ByteCounter counter) {
        this.delegate = Objects.requireNonNull(delegate);
        this.counter = Objects.requireNonNull(counter);
    }

    @Override
    public void write(int b) throws IOException {
        delegate.write(b);
        counter.add(1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        delegate.write(b, off, len);
        if (len > 0) {
            counter.add(len);
        }
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
