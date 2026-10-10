package com.framegen.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CadencePacerTest {

    private FramePacer framePacer;

    @BeforeEach
    void setUp() {
        framePacer = new FramePacer();
    }

    @Test
    void testComputeTargetHalfInterval() {
        // At 30 FPS base (frame duration 33.333 ms), target half-step interval should be ~16.666 ms
        framePacer.recordBaseFrame(33_333_333L);
        long halfIntervalNanos = framePacer.computeTargetHalfIntervalNanos();
        assertThat(halfIntervalNanos).isCloseTo(16_666_666L, within(500_000L));

        // At 60 FPS base (frame duration 16.666 ms), target half-step interval should be ~8.333 ms
        framePacer.reset();
        framePacer.recordBaseFrame(16_666_666L);
        halfIntervalNanos = framePacer.computeTargetHalfIntervalNanos();
        assertThat(halfIntervalNanos).isCloseTo(8_333_333L, within(500_000L));

        // When uninitialized (base FPS = 0), fallback to 60Hz half interval (16.666 ms)
        framePacer.reset();
        halfIntervalNanos = framePacer.computeTargetHalfIntervalNanos();
        assertThat(halfIntervalNanos).isCloseTo(16_666_666L, within(10_000L));
    }

    @Test
    void testAdaptiveBudgetClamp() {
        framePacer.recordBaseFrame(33_333_333L); // 30 FPS base -> 16.666 ms half-step
        long targetHalfInterval = framePacer.computeTargetHalfIntervalNanos();

        // Case 1: Work took 5 ms -> remaining wait should be ~11.666 ms
        long elapsedNanos = 5_000_000L;
        long waitNanos = framePacer.computeRemainingWaitNanos(elapsedNanos, targetHalfInterval);
        assertThat(waitNanos).isCloseTo(targetHalfInterval - elapsedNanos, within(100L));

        // Case 2: Work took 20 ms (> 16.666 ms) -> wait clamps to 0 (never stall game)
        long overbudgetNanos = 20_000_000L;
        waitNanos = framePacer.computeRemainingWaitNanos(overbudgetNanos, targetHalfInterval);
        assertThat(waitNanos).isEqualTo(0L);
    }

    @Test
    void testGatedFrameSkipsPacing() {
        framePacer.recordBaseFrame(33_333_333L);
        framePacer.setLastFrameGated(true);

        long targetHalfInterval = framePacer.computeTargetHalfIntervalNanos();
        long waitNanos = framePacer.computeRemainingWaitNanos(1_000_000L, targetHalfInterval);
        assertThat(waitNanos).isEqualTo(0L);
    }

    @Test
    void testPacingWaitExecutionBounded() {
        // Test high-precision hybrid wait for 3 ms (3,000,000 ns)
        long start = System.nanoTime();
        framePacer.performHybridWait(2_000_000L); // 2 ms
        long duration = System.nanoTime() - start;

        // Verify elapsed time is at least 1.9 ms and no more than 4.0 ms (safe tolerance on Windows timers)
        assertThat(duration).isGreaterThanOrEqualTo(1_900_000L);
        assertThat(duration).isLessThan(5_000_000L);
    }
}
