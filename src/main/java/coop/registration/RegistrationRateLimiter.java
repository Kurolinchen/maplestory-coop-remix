package coop.registration;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

public final class RegistrationRateLimiter {
    private final LongSupplier ticker;
    private final int perIpBurst;
    private final long windowNanos;
    private final long entryTtlNanos;
    private final Map<String, Bucket> perIp = new ConcurrentHashMap<>();
    private final Bucket globalBucket;

    public RegistrationRateLimiter(LongSupplier ticker, int perIpBurst, long windowNanos, int globalHourlyCap,
                                   long globalWindowNanos, long entryTtlNanos) {
        this.ticker = ticker;
        this.perIpBurst = perIpBurst;
        this.windowNanos = windowNanos;
        this.entryTtlNanos = entryTtlNanos;
        if (perIpBurst <= 0 || windowNanos <= 0 || globalHourlyCap <= 0
                || globalWindowNanos <= 0 || entryTtlNanos <= 0) {
            throw new IllegalArgumentException("Rate-limit capacities and durations must be positive");
        }
        this.globalBucket = new Bucket(globalHourlyCap, globalWindowNanos, ticker.getAsLong());
    }

    public synchronized boolean allow(String clientKey) {
        long now = ticker.getAsLong();
        purgeExpired(now);
        globalBucket.refresh(now);
        if (!globalBucket.hasCapacity()) {
            return false;
        }
        Bucket clientBucket = perIp.computeIfAbsent(clientKey,
                key -> new Bucket(perIpBurst, windowNanos, now));
        clientBucket.refresh(now);
        if (!clientBucket.hasCapacity()) {
            return false;
        }
        clientBucket.consume(now);
        globalBucket.consume(now);
        return true;
    }

    synchronized int trackedClientCount() {
        return perIp.size();
    }

    private void purgeExpired(long now) {
        Iterator<Map.Entry<String, Bucket>> iterator = perIp.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Bucket> entry = iterator.next();
            if (now - entry.getValue().lastTouchedNanos() > entryTtlNanos) {
                iterator.remove();
            }
        }
    }

    private static final class Bucket {
        private final int capacity;
        private final long windowNanos;
        private long windowStartNanos;
        private int tokens;
        private long lastTouchedNanos;

        private Bucket(int capacity, long windowNanos, long now) {
            this.capacity = capacity;
            this.windowNanos = windowNanos;
            this.windowStartNanos = now;
            this.tokens = 0;
            this.lastTouchedNanos = now;
        }

        void refresh(long now) {
            if (now - windowStartNanos >= windowNanos) {
                windowStartNanos = now;
                tokens = 0;
            }
        }

        boolean hasCapacity() {
            return tokens < capacity;
        }

        void consume(long now) {
            tokens++;
            lastTouchedNanos = now;
        }

        long lastTouchedNanos() {
            return lastTouchedNanos;
        }
    }
}
