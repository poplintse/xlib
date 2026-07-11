package com.xlib.txtreader;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

final class TocStore {
    private final File directory;

    TocStore(Context context) {
        directory = new File(context.getFilesDir(), "toc");
    }

    TocDocument read(Book book) {
        File source = new File(book.path);
        File cache = fileFor(book);
        if (!cache.exists() || !source.exists()) return null;
        try (FileInputStream input = new FileInputStream(cache)) {
            byte[] data = new byte[(int) cache.length()];
            int total = 0;
            while (total < data.length) {
                int count = input.read(data, total, data.length - total);
                if (count < 0) break;
                total += count;
            }
            JSONObject root = new JSONObject(new String(data, 0, total, StandardCharsets.UTF_8));
            long size = root.optLong("fileSize", -1L);
            long modified = root.optLong("modifiedAt", -1L);
            if (size != source.length() || modified != source.lastModified()) return null;
            JSONArray array = root.optJSONArray("entries");
            ArrayList<TocEntry> entries = new ArrayList<>();
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    entries.add(new TocEntry(item.optInt("level", 1),
                            item.optString("title"), item.optLong("offset")));
                }
            }
            return new TocDocument(size, modified, root.optString("encoding", book.encoding), entries);
        } catch (Exception ignored) {
            return null;
        }
    }

    void write(Book book, TocDocument document) throws Exception {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create TOC directory");
        }
        JSONObject root = new JSONObject();
        root.put("fileSize", document.fileSize);
        root.put("modifiedAt", document.modifiedAt);
        root.put("encoding", document.encoding);
        JSONArray array = new JSONArray();
        for (TocEntry entry : document.entries) {
            JSONObject item = new JSONObject();
            item.put("level", entry.level);
            item.put("title", entry.title);
            item.put("offset", entry.offset);
            array.put(item);
        }
        root.put("entries", array);
        try (FileOutputStream output = new FileOutputStream(fileFor(book))) {
            output.write(root.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    void delete(Book book) {
        File file = fileFor(book);
        if (file.exists()) file.delete();
    }

    private File fileFor(Book book) {
        return new File(directory, book.id + ".json");
    }
}
