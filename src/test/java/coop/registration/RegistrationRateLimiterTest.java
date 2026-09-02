package coop.registration;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistrationRateLimiterTest {
    @Test
    void allowsUpToPerIpBurstThenBlocks() {
        MutableTicker ticker = new MutableTicker();
        RegistrationRateLimiter limiter = new RegistrationRateLimiter(
                ticker, 2, TimeUnit.MINUTES.toNanos(15), 20,
                TimeUnit.HOURS.toNanos(1), TimeUnit.HOURS.toNanos(2));
        assertTrue(limiter.allow("203.0.113.9"));
        assertTrue(limiter.allow("203.0.113.9"));
        assertFalse(limiter.allow("203.0.113.9"));
        assertTrue(limiter.allow("203.0.113.10"));
    }

    @Test
    void globalCapBlocksEveryone() {
        MutableTicker ticker = new MutableTicker();
        RegistrationRateLimiter limiter = new RegistrationRateLimiter(
                ticker, 10, TimeUnit.MINUTES.toNanos(15), 3,
                TimeUnit.HOURS.toNanos(1), TimeUnit.HOURS.toNanos(2));
        assertTrue(limiter.allow("a"));
        assertTrue(limiter.allow("b"));
        assertTrue(limiter.allow("c"));
        assertFalse(limiter.allow("d"));
    }

    @Test
    void globalCapUsesAFullHour() {
        MutableTicker ticker = new MutableTicker();
        RegistrationRateLimiter limiter = new RegistrationRateLimiter(
                ticker, 10, TimeUnit.MINUTES.toNanos(15), 1,
                TimeUnit.HOURS.toNanos(1), TimeUnit.HOURS.toNanos(2));
        assertTrue(limiter.allow("a"));
        ticker.advance(15, TimeUnit.MINUTES);
        assertFalse(limiter.allow("b"));
        ticker.advance(45, TimeUnit.MINUTES);
        assertTrue(limiter.allow("b"));
    }

    @Test
    void rejectedClientDoesNotConsumeAGlobalToken() {
        MutableTicker ticker = new MutableTicker();
        RegistrationRateLimiter limiter = new RegistrationRateLimiter(
                ticker, 1, TimeUnit.MINUTES.toNanos(15), 2,
                TimeUnit.HOURS.toNanos(1), TimeUnit.HOURS.toNanos(2));
        assertTrue(limiter.allow("a"));
        assertFalse(limiter.allow("a"));
        assertTrue(limiter.allow("b"));
        assertFalse(limiter.allow("c"));
    }

    @Test
    void globalRejectionDoesNotConsumeAClientToken() {
        MutableTicker ticker = new MutableTicker();
        RegistrationRateLimiter limiter = new RegistrationRateLimiter(
                ticker, 1, TimeUnit.HOURS.toNanos(2), 1,
                TimeUnit.HOURS.toNanos(1), TimeUnit.HOURS.toNanos(2));
        assertTrue(limiter.allow("a"));
        assertFalse(limiter.allow("b"));
        ticker.advance(1, TimeUnit.HOURS);
        assertTrue(limiter.allow("b"));
    }

    @Test
    void globalRejectionDoesNotAllocateClientBuckets() {
        MutableTicker ticker = new MutableTicker();
        RegistrationRateLimiter limiter = new RegistrationRateLimiter(
                ticker, 10, TimeUnit.MINUTES.toNanos(15), 1,
                TimeUnit.HOURS.toNanos(1), TimeUnit.HOURS.toNanos(2));
        assertTrue(limiter.allow("accepted"));
        for (int i = 0; i < 1_000; i++) {
            assertFalse(limiter.allow("blocked-" + i));
        }
        assertEquals(1, limiter.trackedClientCount());
    }

    private static final class MutableTicker implements LongSupplier {
        private long value;

        @Override
        public long getAsLong() {
            return value;
        }

        void advance(long amount, TimeUnit unit) {
            value += unit.toNanos(amount);
        }
    }
}
