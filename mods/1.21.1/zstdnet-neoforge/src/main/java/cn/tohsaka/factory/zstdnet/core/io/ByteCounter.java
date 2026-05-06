package cn.tohsaka.factory.zstdnet.core.io;

@FunctionalInterface
public interface ByteCounter {
    void add(long bytes);
}
