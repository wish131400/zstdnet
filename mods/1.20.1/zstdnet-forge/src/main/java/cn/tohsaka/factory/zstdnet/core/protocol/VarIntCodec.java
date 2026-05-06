package cn.tohsaka.factory.zstdnet.core.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

public final class VarIntCodec {
    private VarIntCodec() {
    }

    public static int read(InputStream in) throws IOException {
        int value = 0;
        int position = 0;
        while (true) {
            int next = in.read();
            if (next < 0) {
                if (position == 0) {
                    return -1;
                }
                throw new EOFException("eof during varint");
            }

            value |= (next & 0x7F) << position;
            if ((next & 0x80) == 0) {
                return value;
            }

            position += 7;
            if (position > 28) {
                throw new IOException("varint too big");
            }
        }
    }

    public static VarIntRead read(byte[] data, int start) {
        return read(data, start, data.length);
    }

    public static VarIntRead read(byte[] data, int start, int endExclusive) {
        int value = 0;
        int position = 0;
        int index = start;

        while (index < endExclusive) {
            int next = data[index++] & 0xFF;
            value |= (next & 0x7F) << position;
            if ((next & 0x80) == 0) {
                return new VarIntRead(value, index);
            }

            position += 7;
            if (position > 28) {
                return null;
            }
        }

        return null;
    }

    public static byte[] encode(int value) {
        int working = value;
        byte[] buffer = new byte[5];
        int index = 0;

        do {
            int next = working & 0x7F;
            working >>>= 7;
            if (working != 0) {
                next |= 0x80;
            }
            buffer[index++] = (byte) next;
        } while (working != 0);

        return Arrays.copyOf(buffer, index);
    }
}
