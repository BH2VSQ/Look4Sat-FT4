package com.rtbishop.look4sat.core.cw;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class WaterfallHistoryTest {
    private int[] palette() {
        int[] colors = new int[256];
        for (int i = 0; i < colors.length; i++) colors[i] = 0xff000000 | i;
        return colors;
    }

    @Test public void columnsFollowInputRatherThanRenderCalls() {
        WaterfallHistory history = new WaterfallHistory(16, 2, palette());
        int[] pixels = new int[32];
        for (int i = 0; i < 5; i++) history.appendSpectrum(new float[] {i, i});
        assertEquals(5L, history.copyIfChanged(-1, pixels));
        for (int i = 0; i < 10000; i++) assertEquals(5L, history.copyIfChanged(5, pixels));
        assertEquals(5, history.nextColumn(5));
        for (int i = 0; i < 5; i++) assertEquals(palette()[i * 4 + 30], pixels[i]);
    }

    @Test public void stalledRenderingPreservesConsecutiveColumnsWithoutBlackGaps() {
        WaterfallHistory history = new WaterfallHistory(16, 1, palette());
        for (int i = 0; i < 12; i++) history.appendSpectrum(new float[] {i});
        int[] snapshot = new int[16];
        assertEquals(12L, history.copyIfChanged(-1, snapshot));
        for (int i = 0; i < 12; i++) assertEquals(palette()[i * 4 + 30], snapshot[i]);
    }

    @Test public void ringWrapKeepsTheLatestColumnsAndNeverChangesItsSize() {
        WaterfallHistory history = new WaterfallHistory(8, 1, palette());
        for (int i = 0; i < 23; i++) history.appendSpectrum(new float[] {i});
        int[] snapshot = new int[8];
        assertEquals(23L, history.copyIfChanged(-1, snapshot));
        for (int i = 15; i < 23; i++) assertEquals(palette()[i * 4 + 30], snapshot[i % 8]);
        assertEquals(7, history.nextColumn(23));
    }

    @Test public void contextRecreationCanRestoreAnIdenticalSnapshot() {
        WaterfallHistory history = new WaterfallHistory(16, 3, palette());
        for (int i = 0; i < 50; i++) history.appendSpectrum(new float[] {i, i, i});
        int[] before = new int[48], restored = new int[48];
        long sequence = history.copyIfChanged(-1, before);
        assertEquals(sequence, history.copyIfChanged(-1, restored));
        assertArrayEquals(before, restored);
        assertEquals(sequence, history.getColumnsWritten());
    }

    @Test public void frequencyOrderAndClampingMatchTheOriginalPalette() {
        WaterfallHistory history = new WaterfallHistory(4, 2, palette());
        history.appendSpectrum(new float[] {-100, 100});
        int[] snapshot = new int[8];
        history.copyIfChanged(-1, snapshot);
        assertEquals(palette()[255], snapshot[0]);
        assertEquals(palette()[0], snapshot[4]);
        assertFalse(history.appendSpectrum(new float[] {1}));
        assertEquals(1, history.getColumnsWritten());
    }

    @Test public void prolongedInputCannotAccumulateAScrollSpeedError() {
        WaterfallHistory history = new WaterfallHistory(64, 1, palette());
        float[] spectrum = new float[] {1};
        int[] snapshot = new int[64];
        for (int i = 1; i <= 1000000; i++) {
            history.appendSpectrum(spectrum);
            if (i % 997 == 0) assertEquals(i, history.copyIfChanged(-1, snapshot));
        }
        assertEquals(1000000L, history.getColumnsWritten());
        assertEquals(0, history.nextColumn(1000000));
    }

    @Test public void decoderAndRendererCannotObservePartiallyWrittenColumns() throws Exception {
        WaterfallHistory history = new WaterfallHistory(64, 4, palette());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        Thread producer = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 20000; i++) {
                    float value = i % 30;
                    history.appendSpectrum(new float[] {value, value, value, value});
                }
            } catch (Throwable error) { failure.set(error); }
        });
        producer.start(); start.countDown();
        int[] snapshot = new int[256];
        do {
            history.copyIfChanged(-1, snapshot);
            for (int x = 0; x < 64; x++) for (int y = 1; y < 4; y++) assertEquals(snapshot[x], snapshot[y * 64 + x]);
        } while (producer.isAlive());
        producer.join();
        assertNull(failure.get());
        assertEquals(20000L, history.getColumnsWritten());
    }
}
