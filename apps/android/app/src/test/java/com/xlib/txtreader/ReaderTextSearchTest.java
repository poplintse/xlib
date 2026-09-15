package com.xlib.txtreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public class ReaderTextSearchTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void selectsAndClampsSearchOrigin() {
        assertEquals(0L, ReaderTextSearch.startOffset(true, 50L, 100L));
        assertEquals(50L, ReaderTextSearch.startOffset(false, 50L, 100L));
        assertEquals(100L, ReaderTextSearch.startOffset(false, 150L, 100L));
        assertEquals(0L, ReaderTextSearch.startOffset(false, -1L, 100L));
    }

    @Test
    public void beginningIncludesEarlierMatchesWhileCurrentPageExcludesThem() throws Exception {
        File file = write("scope.txt", "目标前文目标后文目标", StandardCharsets.UTF_8);
        long current = "目标前文".getBytes(StandardCharsets.UTF_8).length;
        long last = "目标前文目标后文".getBytes(StandardCharsets.UTF_8).length;
        ReaderTextSearch.Batch forward = ReaderTextSearch.find(file, "UTF-8", "目标",
                ReaderTextSearch.startOffset(false, current, file.length()), file.length(), 7, 20);
        ReaderTextSearch.Batch beginning = ReaderTextSearch.find(file, "UTF-8", "目标",
                0L, file.length(), 7, 20);
        assertEquals(List.of(current, last), forward.offsets);
        assertEquals(List.of(0L, current, last), beginning.offsets);
    }

    @Test
    public void findsQueryAcrossDecodedSegmentBoundary() throws Exception {
        File file = write("cross.txt", "0123目标内容89", StandardCharsets.UTF_8);
        long expected = "0123".getBytes(StandardCharsets.UTF_8).length;

        ReaderTextSearch.Batch batch = ReaderTextSearch.find(
                file, "UTF-8", "目标内容", 0L, file.length(), 7, 20);

        assertEquals(List.of(expected), batch.offsets);
        assertTrue(batch.reachedBoundary);
    }

    @Test
    public void returnsOnlyCharacterAlignedUtf16Matches() throws Exception {
        String text = "甲目标乙目标丙";
        File file = write("utf16.txt", text, StandardCharsets.UTF_16LE);
        long first = "甲".getBytes(StandardCharsets.UTF_16LE).length;
        long second = "甲目标乙".getBytes(StandardCharsets.UTF_16LE).length;

        ReaderTextSearch.Batch batch = ReaderTextSearch.find(
                file, "UTF-16LE", "目标", 0L, file.length(), 8, 20);

        assertEquals(List.of(first, second), batch.offsets);
    }

    @Test
    public void preservesGb18030ByteOffsets() throws Exception {
        Charset charset = Charset.forName("GB18030");
        String text = "开头𠀀目标结尾";
        File file = write("gb.txt", text, charset);
        long expected = "开头𠀀".getBytes(charset).length;

        ReaderTextSearch.Batch batch = ReaderTextSearch.find(
                file, "GB18030", "目标", 0L, file.length(), 9, 20);

        assertEquals(List.of(expected), batch.offsets);
    }

    @Test
    public void limitReturnsNextCharacterBoundaryForContinuation() throws Exception {
        File file = write("limit.txt", "aaaaa", StandardCharsets.UTF_8);

        ReaderTextSearch.Batch first = ReaderTextSearch.find(
                file, "UTF-8", "aa", 0L, file.length(), 4, 1);
        ReaderTextSearch.Batch second = ReaderTextSearch.find(
                file, "UTF-8", "aa", first.nextOffset, file.length(), 4, 20);

        assertEquals(List.of(0L), first.offsets);
        assertFalse(first.reachedBoundary);
        assertEquals(2L, first.nextOffset);
        assertEquals(List.of(2L), second.offsets);
    }

    @Test
    public void supplementaryQueryIsNotDuplicatedByCarryWindow() throws Exception {
        File file = write("supplementary.txt", "🙂🙂x", StandardCharsets.UTF_8);

        ReaderTextSearch.Batch batch = ReaderTextSearch.find(
                file, "UTF-8", "🙂", 0L, file.length(), 5, 20);

        assertEquals(List.of(0L, 4L), batch.offsets);
    }

    @Test public void caseInsensitiveNonOverlappingAcrossEverySegmentSize() throws Exception {
        File file = write("case.txt", "哈哈哈哈哈 HeLLoHELLO hello", StandardCharsets.UTF_8);
        for (int size = 4; size < 30; size++) {
            assertEquals(List.of(0L, 6L), ReaderTextSearch.find(file, "UTF-8", "哈哈", 0,
                    file.length(), size, 200).offsets);
            assertEquals(List.of(16L, 21L, 27L), ReaderTextSearch.find(file, "UTF-8", "hello", 0,
                    file.length(), size, 200).offsets);
        }
    }

    @Test public void moreThanTwoHundredAndManualWrapPartitionDoNotOverlap() throws Exception {
        File file = write("many.txt", "Aa".repeat(450), StandardCharsets.UTF_8);
        ReaderTextSearch.Batch first = ReaderTextSearch.find(file, "UTF-8", "aa", 100, file.length(), 7, 200);
        ReaderTextSearch.Batch second = ReaderTextSearch.find(file, "UTF-8", "aa", first.nextOffset,
                file.length(), 7, 200);
        ReaderTextSearch.Batch wrapped = ReaderTextSearch.find(file, "UTF-8", "aa", 0, 100, 7, 200);
        assertEquals(200, first.offsets.size());
        assertEquals(200, second.offsets.size());
        assertEquals(50, wrapped.offsets.size());
        assertEquals(Long.valueOf(498), first.offsets.get(199));
        assertEquals(Long.valueOf(500), second.offsets.get(0));
        assertTrue(second.reachedBoundary);
        assertTrue(wrapped.reachedBoundary);
    }

    private File write(String name, String text, Charset charset) throws Exception {
        File file = temporaryFolder.newFile(name);
        Files.write(file.toPath(), text.getBytes(charset));
        return file;
    }
}
