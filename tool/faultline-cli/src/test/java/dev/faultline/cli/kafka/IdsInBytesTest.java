package dev.faultline.cli.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class IdsInBytesTest {
    private static final IdsInBytes RUN = new IdsInBytes(List.of("pay-42-1", "pay-42-12", "pay-42-120"));

    /** Bytes as written one after another; a String as its UTF-8 bytes. */
    private static byte[] bytes(Object... parts) {
        var out = new ByteArrayOutputStream();
        for (Object p : parts) {
            if (p instanceof String s) out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
            else out.write((Integer) p);
        }
        return out.toByteArray();
    }

    @Test
    void anAvroStringIsItsZigzagLengthThenItsBytes() {
        // Confluent's wire format: magic byte and schema ID, then the record {id: "pay-42-12", note: "x"}
        assertThat(RUN.in(bytes(0, 0, 0, 0, 7, 18, "pay-42-12", 2, "x"))).containsExactly("pay-42-12");
        // a 36-character UUID's length byte is 'H', which an ID could start with
        String uuid = UUID.nameUUIDFromBytes(new byte[] {1}).toString();
        assertThat(new IdsInBytes(List.of(uuid)).in(bytes(0, 0, 0, 0, 3, 72, uuid, 0))).containsExactly(uuid);
    }

    @Test
    void aShorterIdInsideALongerStringIsNotThere() {
        // pay-42-12 is not an ID of this run, and pay-42-1 is only its start
        assertThat(new IdsInBytes(List.of("pay-42-1")).in(bytes(18, "pay-42-12"))).isEmpty();
        assertThat(RUN.in(bytes(20, "pay-42-120"))).containsExactly("pay-42-120");
    }

    @Test
    void protobufAndMessagePackStringsAreFoundToo() {
        // Protobuf: field 1, wire type 2, then the length as a plain varint
        assertThat(RUN.in(bytes(0x0a, 9, "pay-42-12"))).containsExactly("pay-42-12");
        // MessagePack: a fixstr of 8 bytes
        assertThat(RUN.in(bytes(0xa8, "pay-42-1"))).containsExactly("pay-42-1");
    }

    @Test
    void aLongStringsLengthTakesTwoBytes() {
        String id = "order-" + "x".repeat(100);
        // Avro writes 2 * 106 = 212 as the varint d4 01
        assertThat(new IdsInBytes(List.of(id)).in(bytes(0xd4, 0x01, id))).containsExactly(id);
    }

    @Test
    void anIdBetweenBytesThatCannotBePartOfOneIsFoundInAnyFormat() {
        assertThat(RUN.in(bytes("{\"id\":\"pay-42-1\"}"))).containsExactly("pay-42-1");
        assertThat(RUN.in(bytes("id=pay-42-1x"))).isEmpty();
    }

    @Test
    void onlyAValueThatOpensAnObjectOrAnArrayIsReadAsJson() {
        assertThat(DownstreamReader.json(bytes("  {\"a\": 1}"))).isTrue();
        assertThat(DownstreamReader.json(bytes("[1]"))).isTrue();
        assertThat(DownstreamReader.json(bytes(0, 0, 0, 0, 7))).isFalse();
        assertThat(DownstreamReader.json(null)).isFalse();
    }
}
