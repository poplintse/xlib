package com.xlib.txtreader;

import static org.junit.Assert.assertEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class TextFileUtilsTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void detectsUtf8Text() throws Exception {
        File file = write("utf8.txt", "你好，Xlib".getBytes(StandardCharsets.UTF_8));
        assertEquals("UTF-8", TextFileUtils.detectEncoding(file));
    }

    @Test
    public void detectsUtf16Bom() throws Exception {
        File littleEndian = write("utf16le.txt", new byte[]{(byte) 0xFF, (byte) 0xFE, 0x41, 0x00});
        File bigEndian = write("utf16be.txt", new byte[]{(byte) 0xFE, (byte) 0xFF, 0x00, 0x41});
        assertEquals("UTF-16LE", TextFileUtils.detectEncoding(littleEndian));
        assertEquals("UTF-16BE", TextFileUtils.detectEncoding(bigEndian));
    }

    @Test
    public void fallsBackToGb18030ForInvalidUtf8() throws Exception {
        File file = write("gb.txt", new byte[]{(byte) 0xC4, (byte) 0xE3, (byte) 0xBA, (byte) 0xC3});
        assertEquals("GB18030", TextFileUtils.detectEncoding(file));
    }

    @Test
    public void detectsUtf8WhenSampleEndsInsideMultibyteCharacter() throws Exception {
        byte[] prefix = "a".repeat(4095).getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "中b".getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[prefix.length + suffix.length];
        System.arraycopy(prefix, 0, content, 0, prefix.length);
        System.arraycopy(suffix, 0, content, prefix.length, suffix.length);

        File file = write("utf8-boundary.txt", content);

        assertEquals("UTF-8", TextFileUtils.detectEncoding(file));
    }

    @Test
    public void formatsFileSizesAtUnitBoundaries() {
        assertEquals("0 Byte", TextFileUtils.formatFileSize(-1));
        assertEquals("1.0 KB", TextFileUtils.formatFileSize(1024));
        assertEquals("1.0 MB", TextFileUtils.formatFileSize(1024L * 1024L));
        assertEquals("1.0 GB", TextFileUtils.formatFileSize(1024L * 1024L * 1024L));
    }

    private File write(String name, byte[] bytes) throws Exception {
        File file = temporaryFolder.newFile(name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        }
        return file;
    }
}
