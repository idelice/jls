package org.javacs;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Cache maps a file + an arbitrary key to a value. When the file is modified, the mapping expires. */
class Cache<K, V> {
    private static final long DEFAULT_MAX_SIZE = 20_000;

    private static class Key<K> {
        final Path file;
        final K key;

        Key(Path file, K key) {
            this.file = file;
            this.key = key;
        }

        @Override
        public boolean equals(Object other) {
            if (other.getClass() != Cache.Key.class) return false;
            var that = (Cache.Key) other;
            return Objects.equals(this.key, that.key) && Objects.equals(this.file, that.file);
        }

        @Override
        public int hashCode() {
            return Objects.hash(file, key);
        }
    }

    private static class Value<V> {
        final V value;
        final Instant created = Instant.now();

        Value(V value) {
            this.value = value;
        }
    }

    private final String name;
    private final com.github.benmanes.caffeine.cache.Cache<Key<K>, Value<V>> cache;
    private final Map<Path, Set<Key<K>>> keysByFile = new ConcurrentHashMap<>();

    Cache() {
        this("cache");
    }

    Cache(String name) {
        this(name, DEFAULT_MAX_SIZE);
    }

    Cache(String name, long maximumSize) {
        this.name = name;
        this.cache =
                Caffeine.newBuilder()
                        .maximumSize(maximumSize)
                        .removalListener(this::onRemoval)
                        .build();
    }

    private void onRemoval(Key<K> key, Value<V> value, RemovalCause cause) {
        if (key == null) {
            return;
        }
        keysByFile.computeIfPresent(
                key.file,
                (file, keys) -> {
                    keys.remove(key);
                    return keys.isEmpty() ? null : keys;
                });
    }

    private void indexKey(Key<K> key) {
        keysByFile.computeIfAbsent(key.file, __ -> ConcurrentHashMap.newKeySet()).add(key);
    }

    private void invalidateFile(Path file) {
        var keys = keysByFile.remove(file);
        if (keys == null || keys.isEmpty()) {
            return;
        }
        cache.invalidateAll(keys);
    }

    boolean needs(Path file, K k) {
        // If key is not in map, it needs to be loaded
        var key = new Key<K>(file, k);
        var value = cache.getIfPresent(key);
        if (value == null) {
            CacheAudit.miss(name);
            return true;
        }

        // If key was loaded before file was last modified, it needs to be reloaded
        var modified = FileStore.modified(file);
        if (modified == null) {
            invalidateFile(file);
            CacheAudit.miss(name);
            return true;
        }
        var stale = value.created.isBefore(modified);
        if (stale) {
            invalidateFile(file);
            CacheAudit.miss(name);
            return true;
        }
        CacheAudit.hit(name);
        return false;
    }

    void load(Path file, K k, V v) {
        var key = new Key<K>(file, k);
        var value = new Value<>(v);
        cache.put(key, value);
        indexKey(key);
        CacheAudit.load(name);
        CacheAudit.store(name);
    }

    V get(Path file, K k) {
        var key = new Key<K>(file, k);
        var value = cache.getIfPresent(key);
        if (value == null) {
            throw new IllegalArgumentException(k + " is not in cache " + name);
        }
        return value.value;
    }

    int size() {
        cache.cleanUp();
        return Math.toIntExact(cache.estimatedSize());
    }
}
