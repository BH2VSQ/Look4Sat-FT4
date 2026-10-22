package com.rtbishop.look4sat.core.cw;

import java.util.Arrays;

/** Fixed-size sample-driven history. A render stall never changes the audio time scale. */
public final class WaterfallHistory {
    private final int width;
    private final int height;
    private final int[] palette;
    private final int[] pixels;
    private long columnsWritten;

    public WaterfallHistory(int width, int height, int[] palette) {
        if (width <= 0 || height <= 0 || palette.length != 256) throw new IllegalArgumentException("Invalid waterfall geometry");
        this.width = width;
        this.height = height;
        this.palette = palette.clone();
        this.pixels = new int[Math.multiplyExact(width, height)];
        Arrays.fill(pixels, palette[0]);
    }

    public synchronized boolean appendSpectrum(float[] spectrum) {
        if (spectrum == null || spectrum.length != height) return false;
        int column = (int) (columnsWritten % width);
        for (int bin = 0; bin < height; bin++) {
            int color = Math.max(0, Math.min(255, Math.round(spectrum[bin] * 4f + 30f)));
            pixels[(height - 1 - bin) * width + column] = palette[color];
        }
        columnsWritten++;
        return true;
    }

    /** Copies only when new input exists; the returned sequence and pixels describe one snapshot. */
    public synchronized long copyIfChanged(long previousSequence, int[] output) {
        if (output.length != pixels.length) throw new IllegalArgumentException("Invalid waterfall snapshot size");
        if (previousSequence != columnsWritten) System.arraycopy(pixels, 0, output, 0, pixels.length);
        return columnsWritten;
    }

    public synchronized long getColumnsWritten() { return columnsWritten; }

    public int nextColumn(long sequence) { return (int) (sequence % width); }
}
