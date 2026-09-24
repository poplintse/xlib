package com.xlib.txtreader;

import static org.junit.Assert.assertEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public class ReaderSegmentSourceTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void trimsOnlyIncompleteUtf8SuffixAndKeepsExactByteMap() throws Exception {
        File file = write("utf8.txt", "A中🙂B", StandardCharsets.UTF_8);

        CacheSegment segment = ReaderSegmentSource.read(
                file, 0L, 6, StandardCharsets.UTF_8);

        assertEquals("A中", segment.text);
        assertEquals(4, segment.bytesRead);
        assertEquals(segment.bytesRead, segment.offsetMap.totalBytes());
    }

    @Test
    public void findsExactGb18030BoundaryWithoutANearbyNewline() throws Exception {
        Charset charset = Charset.forName("GB18030");
        String text = "A中𠀀B";
        File file = write("gb18030.txt", text, charset);
        long beforeSupplementary = "A中".getBytes(charset).length;

        long boundary = ReaderSegmentSource.findReadableOffset(
                file, beforeSupplementary + 2L, "GB18030");

        assertEquals(beforeSupplementary, boundary);
    }

    @Test
    public void utf16SeekBeforeLowSurrogateWithoutANearbyNewline() throws Exception {
        for (String encoding : List.of("UTF-16LE", "UTF-16BE")) {
            Charset charset = Charset.forName(encoding);
            for (boolean hasBom : new boolean[]{false, true}) {
                String prefix = (hasBom ? "\uFEFF" : "") + "A".repeat(140_000);
                File file = write(encoding + hasBom + ".txt", prefix + "🙂B", charset);
                long emojiStart = prefix.getBytes(charset).length;

                long boundary = ReaderSegmentSource.findReadableOffset(
                        file, emojiStart + 2L, encoding);
                CacheSegment segment = ReaderSegmentSource.read(
                        file, boundary, (int) (file.length() - boundary), charset);

                assertEquals(emojiStart, boundary);
                assertEquals("🙂B", segment.text);
                assertEquals(file.length() - boundary, segment.offsetMap.totalBytes());
            }
        }
    }

    @Test
    public void utf8BomKeepsOriginalByteOffsets() throws Exception {
        File file = temporaryFolder.newFile("utf8-bom.txt");
        byte[] content = "甲🙂乙".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[3 + content.length];
        bytes[0] = (byte) 0xEF;
        bytes[1] = (byte) 0xBB;
        bytes[2] = (byte) 0xBF;
        System.arraycopy(content, 0, bytes, 3, content.length);
        Files.write(file.toPath(), bytes);

        CacheSegment segment = ReaderSegmentSource.read(
                file, 0L, bytes.length, StandardCharsets.UTF_8);

        assertEquals("\uFEFF甲🙂乙", segment.text);
        assertEquals(bytes.length, segment.bytesRead);
        assertEquals(bytes.length, segment.offsetMap.totalBytes());
    }

    @Test
    public void prefersPreviousParagraphBoundaryWithinScanWindow() throws Exception {
        File file = write("paragraphs.txt", "第一段\n第二段内容", StandardCharsets.UTF_8);
        long secondParagraph = "第一段\n".getBytes(StandardCharsets.UTF_8).length;

        long boundary = ReaderSegmentSource.findReadableOffset(
                file, file.length() - 1L, "UTF-8");

        assertEquals(secondParagraph, boundary);
    }

    @Test
    public void buildsSparseIndexFromCharacterSafeParagraphOffsets() throws Exception {
        File file = write("index.txt", "aa\nbbbb\ncccc\ndddd", StandardCharsets.UTF_8);

        List<Long> offsets = ReaderSegmentSource.buildReadableIndex(
                file, "UTF-8", 5);

        assertEquals(List.of(0L, 3L, 8L, 13L), offsets);
    }

    @Test(expected = IOException.class)
    public void rejectsTruncatedInvalidSourceInsteadOfPublishingWrongByteMap() throws Exception {
        File file = temporaryFolder.newFile("invalid.txt");
        Files.write(file.toPath(), new byte[]{(byte) 0xE4});

        ReaderSegmentSource.read(file, 0L, 1, StandardCharsets.UTF_8);
    }

    private File write(String name, String text, Charset charset) throws Exception {
        File file = temporaryFolder.newFile(name);
        Files.write(file.toPath(), text.getBytes(charset));
        return file;
    }
}
