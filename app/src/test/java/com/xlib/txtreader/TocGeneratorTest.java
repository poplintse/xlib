package com.xlib.txtreader;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class TocGeneratorTest {
    @Test
    public void chapterOnlyBecomesFirstLevelAndKeepsByteOffset() throws Exception {
        String text = "前言\n第一章 开始\n正文\n第二章 继续\n";
        File file = File.createTempFile("xlib-toc", ".txt");
        try {
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(text.getBytes(StandardCharsets.UTF_8));
            }
            TocDocument document = TocGenerator.generate(file, "UTF-8");
            assertEquals(2, document.entries.size());
            assertEquals(1, document.entries.get(0).level);
            assertEquals("前言\n".getBytes(StandardCharsets.UTF_8).length,
                    document.entries.get(0).offset);
        } finally {
            file.delete();
        }
    }

    @Test
    public void volumeChapterSectionKeepsThreeLevels() throws Exception {
        String text = "第一卷 山海\n第一章 启程\n第一节 夜雨\n";
        File file = File.createTempFile("xlib-toc", ".txt");
        try {
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(text.getBytes(StandardCharsets.UTF_8));
            }
            TocDocument document = TocGenerator.generate(file, "UTF-8");
            assertEquals(3, document.entries.size());
            assertEquals(1, document.entries.get(0).level);
            assertEquals(2, document.entries.get(1).level);
            assertEquals(3, document.entries.get(2).level);
        } finally {
            file.delete();
        }
    }
}
