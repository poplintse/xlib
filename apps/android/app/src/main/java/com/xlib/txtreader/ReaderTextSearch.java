package com.xlib.txtreader;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Character-boundary-safe streaming search over a TXT source file. */
final class ReaderTextSearch {
    private ReaderTextSearch() {
    }

    static Batch find(File file, String encoding, String query, long startOffset,
                      long endOffset, int segmentBytes, int resultLimit) throws IOException {
        Charset charset = Charset.forName(encoding == null ? "UTF-8" : encoding);
        long boundary = Math.max(0L, Math.min(endOffset, file.length()));
        long position = Math.max(0L, Math.min(startOffset, boundary));
        if (query == null || query.isEmpty() || !file.exists() || position >= boundary) {
            return new Batch(Collections.emptyList(), position, true);
        }

        int safeSegmentBytes = Math.max(4, segmentBytes);
        int safeLimit = Math.max(1, resultLimit);
        ArrayList<Long> offsets = new ArrayList<>();
        String carry = "";
        long carryOffset = position;
        long matchedThrough = position;
        java.util.regex.Pattern pattern = SearchTextRules.pattern(query);
        while (position < boundary && offsets.size() < safeLimit) {
            CacheSegment segment = ReaderSegmentSource.read(file, position,
                    (int) Math.min(safeSegmentBytes, boundary - position), charset);
            if (segment.bytesRead <= 0) break;
            String searchable = carry + segment.text;
            long searchableOffset = carry.isEmpty() ? segment.offset : carryOffset;
            ByteOffsetMap searchableMap = ByteOffsetMap.create(searchable, charset);
            int from = searchableMap.charIndexForByteOffset(Math.max(0, matchedThrough - searchableOffset));
            java.util.regex.Matcher matcher = pattern.matcher(searchable);
            while (from < searchable.length()) {
                if (!matcher.find(from)) break;
                int match = matcher.start();
                long offset = searchableOffset
                        + searchableMap.byteOffsetForCharIndex(match);
                int matchEnd = matcher.end();
                long end = searchableOffset
                        + searchableMap.byteOffsetForCharIndex(matchEnd);
                if (offset >= startOffset && end <= boundary) {
                    offsets.add(offset);
                    matchedThrough = end;
                    if (offsets.size() >= safeLimit) {
                        return new Batch(offsets, end, end >= boundary);
                    }
                }
                from = matchEnd;
            }
            position = segment.endOffset();
            int carryStart = Math.max(0, searchable.length() - query.length() + 1);
            if (carryStart > 0 && carryStart < searchable.length()
                    && Character.isLowSurrogate(searchable.charAt(carryStart))) {
                carryStart++;
            }
            carryOffset = searchableOffset
                    + searchableMap.byteOffsetForCharIndex(carryStart);
            carry = searchable.substring(carryStart);
        }
        return new Batch(offsets, position, position >= boundary);
    }

    static final class Batch {
        final List<Long> offsets;
        final long nextOffset;
        final boolean reachedBoundary;

        Batch(List<Long> offsets, long nextOffset, boolean reachedBoundary) {
            this.offsets = Collections.unmodifiableList(new ArrayList<>(offsets));
            this.nextOffset = nextOffset;
            this.reachedBoundary = reachedBoundary;
        }
    }
}
