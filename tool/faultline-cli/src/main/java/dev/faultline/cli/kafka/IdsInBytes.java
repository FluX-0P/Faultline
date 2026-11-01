package dev.faultline.cli.kafka;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which of a run's IDs a binary message names, read without its schema. Avro, Protobuf and MessagePack write a string
 * as its length and then its UTF-8 bytes, so an ID the message carries is in it as it is. An ID counts only where it is
 * a whole string: right after its own length (as Avro, Protobuf or MessagePack write it), or between bytes that cannot
 * be part of an ID. So pay-42-1 is never read out of pay-42-12.
 */
public final class IdsInBytes {
    /** The IDs by their UTF-8 bytes, each byte one char; their lengths in bytes, longest first. */
    private final Map<String, String> ids = new HashMap<>();
    private final int[] lengths;

    public IdsInBytes(Collection<String> ids) {
        var lengths = new TreeSet<Integer>();
        for (String id : ids) {
            if (id == null || id.isEmpty()) continue;
            byte[] bytes = id.getBytes(StandardCharsets.UTF_8);
            this.ids.put(new String(bytes, StandardCharsets.ISO_8859_1), id);
            lengths.add(bytes.length);
        }
        this.lengths = lengths.descendingSet().stream().mapToInt(Integer::intValue).toArray();
    }

    /** The IDs the bytes name, in the order they appear. */
    public Set<String> in(byte[] bytes) {
        var found = new LinkedHashSet<String>();
        if (bytes == null || ids.isEmpty()) return found;
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        for (int at = 0; at < bytes.length; at++) {
            for (int len : lengths) {
                if (at + len > bytes.length) continue;
                String id = ids.get(text.substring(at, at + len));
                if (id != null && whole(bytes, at, len)) {
                    found.add(id);
                    at += len - 1;
                    break;
                }
            }
        }
        return found;
    }

    private static boolean whole(byte[] b, int at, int len) {
        return after(b, at, varint(2L * len)) || after(b, at, varint(len)) || msgpack(b, at, len) || delimited(b, at, len);
    }

    /** Whether the bytes just before {@code at} are {@code prefix}. */
    private static boolean after(byte[] b, int at, byte[] prefix) {
        if (at < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (b[at - prefix.length + i] != prefix[i]) return false;
        }
        return true;
    }

    /** A length as Protobuf writes it (Avro writes twice the length the same way, its zigzag form). */
    private static byte[] varint(long n) {
        var out = new byte[10];
        int i = 0;
        while ((n & ~0x7FL) != 0) {
            out[i++] = (byte) ((n & 0x7F) | 0x80);
            n >>>= 7;
        }
        out[i++] = (byte) n;
        return Arrays.copyOf(out, i);
    }

    /** MessagePack's fixstr (0xa0 + length) or str 8 (0xd9, length). */
    private static boolean msgpack(byte[] b, int at, int len) {
        if (len < 32 && at >= 1 && (b[at - 1] & 0xFF) == (0xa0 | len)) return true;
        return len < 256 && at >= 2 && (b[at - 2] & 0xFF) == 0xd9 && (b[at - 1] & 0xFF) == len;
    }

    private static boolean delimited(byte[] b, int at, int len) {
        return (at == 0 || !idByte(b[at - 1])) && (at + len == b.length || !idByte(b[at + len]));
    }

    private static boolean idByte(byte c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_' || c == '.' || c == ':';
    }
}
