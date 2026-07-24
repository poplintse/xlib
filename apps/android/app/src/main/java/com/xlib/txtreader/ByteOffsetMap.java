package com.xlib.txtreader;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/** Sparse, bidirectional mapping between Java UTF-16 indices and source bytes. */
final class ByteOffsetMap {
    private static final int DEFAULT_STRIDE = 128;

    private final String text;
    private final Charset charset;
    private final int[] charAnchors;
    private final int[] byteAnchors;

    static ByteOffsetMap create(String text, Charset charset) {
        return new ByteOffsetMap(text == null ? "" : text, charset, DEFAULT_STRIDE);
    }

    ByteOffsetMap(String text, Charset charset, int stride) {
        this.text = text == null ? "" : text;
        this.charset = charset;
        int safeStride = Math.max(16, stride);
        List<Integer> chars = new ArrayList<>();
        List<Integer> bytes = new ArrayList<>();
        chars.add(0);
        bytes.add(0);

        int previousChar = 0;
        int byteOffset = 0;
        while (previousChar < this.text.length()) {
            int nextChar = Math.min(this.text.length(), previousChar + safeStride);
            if (nextChar < this.text.length()
                    && Character.isLowSurrogate(this.text.charAt(nextChar))
                    && nextChar > previousChar) {
                nextChar--;
            }
            if (nextChar <= previousChar) {
                nextChar = Math.min(this.text.length(), previousChar + 1);
            }
            byteOffset += encodedLength(previousChar, nextChar);
            chars.add(nextChar);
            bytes.add(byteOffset);
            previousChar = nextChar;
        }

        charAnchors = toIntArray(chars);
        byteAnchors = toIntArray(bytes);
    }

    int byteOffsetForCharIndex(int charIndex) {
        int safeIndex = normalizeCharBoundary(Math.max(0, Math.min(charIndex, text.length())));
        int anchor = floorIndex(charAnchors, safeIndex);
        return byteAnchors[anchor] + encodedLength(charAnchors[anchor], safeIndex);
    }

    int charIndexForByteOffset(long byteOffset) {
        int target = (int) Math.max(0L, Math.min(byteOffset, totalBytes()));
        int anchor = floorIndex(byteAnchors, target);
        int charIndex = charAnchors[anchor];
        int currentByte = byteAnchors[anchor];
        while (charIndex < text.length()) {
            int nextChar = charIndex + Character.charCount(text.codePointAt(charIndex));
            int nextByte = currentByte + encodedLength(charIndex, nextChar);
            if (nextByte > target) break;
            charIndex = nextChar;
            currentByte = nextByte;
        }
        return charIndex;
    }

    int totalBytes() {
        return byteAnchors[byteAnchors.length - 1];
    }

    private int normalizeCharBoundary(int index) {
        if (index > 0 && index < text.length() && Character.isLowSurrogate(text.charAt(index))) {
            return index - 1;
        }
        return index;
    }

    private int encodedLength(int start, int end) {
        if (end <= start) return 0;
        return text.substring(start, end).getBytes(charset).length;
    }

    private static int floorIndex(int[] values, int target) {
        int low = 0;
        int high = values.length - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (values[mid] <= target) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return Math.max(0, high);
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
        return result;
    }
}
