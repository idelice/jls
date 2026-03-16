package org.javacs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;

public final class CacheAudit {
    private static final ConcurrentMap<String, Stats> STATS = new ConcurrentHashMap<>();

    private static final class Stats {
        final LongAdder hits = new LongAdder();
        final LongAdder misses = new LongAdder();
        final LongAdder loads = new LongAdder();
        final LongAdder stores = new LongAdder();
    }

    private CacheAudit() {}

    public static void hit(String name) {
        stats(name).hits.increment();
    }

    public static void miss(String name) {
        stats(name).misses.increment();
    }

    public static void load(String name) {
        stats(name).loads.increment();
    }

    public static void store(String name) {
        stats(name).stores.increment();
    }

    public static List<String> summaryLines() {
        var names = new ArrayList<>(STATS.keySet());
        names.sort(Comparator.naturalOrder());
        var lines = new ArrayList<String>();
        for (var name : names) {
            var stats = STATS.get(name);
            if (stats == null) {
                continue;
            }
            var hits = stats.hits.sum();
            var misses = stats.misses.sum();
            var loads = stats.loads.sum();
            var stores = stats.stores.sum();
            var lookups = hits + misses;
            var hitRate = lookups == 0 ? "n/a" : String.format("%.1f%%", (hits * 100.0) / lookups);
            lines.add(
                    String.format(
                            "[cache] summary name=%s hits=%d misses=%d hit_rate=%s loads=%d stores=%d",
                            name, hits, misses, hitRate, loads, stores));
        }
        return lines;
    }

    public static void logSummary(Logger log) {
        for (var line : summaryLines()) {
            log.info(line);
        }
    }

    private static Stats stats(String name) {
        return STATS.computeIfAbsent(name == null || name.isBlank() ? "unnamed" : name, __ -> new Stats());
    }
}
