package com.xlib.txtreader;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class ByteOffsetMapTest {
    @Test
    public void mapsMixedUtf8InBothDirections() {
        assertRoundTrips("开头 A🙂\n第二行 ending", StandardCharsets.UTF_8);
    }

    @Test
    public void mapsUtf16LittleEndianInBothDirections() {
        assertRoundTrips("第一行\nUTF16🙂结束", StandardCharsets.UTF_16LE);
    }

    @Test
    public void mapsUtf16BigEndianInBothDirections() {
        assertRoundTrips("第一行\nUTF16🙂结束", StandardCharsets.UTF_16BE);
    }

    @Test
    public void mapsGb18030InBothDirections() {
        assertRoundTrips("简体中文，ASCII 123，𠀀", Charset.forName("GB18030"));
    }

    @Test
    public void floorsOffsetsInsideAMultibyteCharacter() {
        ByteOffsetMap map = ByteOffsetMap.create("A中B", StandardCharsets.UTF_8);
        assertEquals(1, map.charIndexForByteOffset(1));
        assertEquals(1, map.charIndexForByteOffset(2));
        assertEquals(1, map.charIndexForByteOffset(3));
        assertEquals(2, map.charIndexForByteOffset(4));
    }

    private void assertRoundTrips(String text, Charset charset) {
        ByteOffsetMap map = new ByteOffsetMap(text, charset, 16);
        assertEquals(text.getBytes(charset).length, map.totalBytes());
        for (int index = 0; index <= text.length(); index++) {
            if (index < text.length() && Character.isLowSurrogate(text.charAt(index))) continue;
            int byteOffset = map.byteOffsetForCharIndex(index);
            assertEquals(index, map.charIndexForByteOffset(byteOffset));
        }
    }
}
