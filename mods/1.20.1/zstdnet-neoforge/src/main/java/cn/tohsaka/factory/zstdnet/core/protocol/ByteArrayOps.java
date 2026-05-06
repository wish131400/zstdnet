package cn.tohsaka.factory.zstdnet.core.protocol;

public final class ByteArrayOps {
    private ByteArrayOps() {
    }

    public static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] array : arrays) {
            if (array != null) {
                total += array.length;
            }
        }

        byte[] merged = new byte[total];
        int offset = 0;
        for (byte[] array : arrays) {
            if (array == null || array.length == 0) {
                continue;
            }
            System.arraycopy(array, 0, merged, offset, array.length);
            offset += array.length;
        }
        return merged;
    }

    public static byte[] slice(byte[] source, int startInclusive, int endExclusive) {
        int start = Math.max(0, startInclusive);
        int end = Math.max(start, Math.min(source.length, endExclusive));
        byte[] out = new byte[end - start];
        System.arraycopy(source, start, out, 0, out.length);
        return out;
    }
}
