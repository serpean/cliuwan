package io.github.serpean.cliuwan;

import io.github.serpean.cliuwan.cache.DiskCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the on-disk cache.
 *
 * <p>The location is redirected with {@code XDG_CACHE_HOME}, which the build points at a
 * directory under {@code build/} so the real {@code ~/.cache} is never touched. Since that
 * variable cannot be changed from inside the process in Java, these tests assert relative
 * behaviour (same key, different scope, expiry) rather than absolute paths.
 */
class DiskCacheTest {

    private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);

    private static DiskCache cache(String scope) {
        return new DiskCache(true, FIVE_MINUTES, false, scope);
    }

    @Test
    @DisplayName("What goes in comes back out with the same key and scope")
    void storesAndRetrieves() {
        DiskCache c = cache("scope-A");
        String key = "key-" + System.nanoTime();

        c.put(key, "{\"data\":1}");

        assertEquals("{\"data\":1}", c.get(key));
    }

    @Test
    @DisplayName("Different credentials do not share cache entries")
    void differentScopesDoNotShareEntries() {
        String key = "apps|PAYMENTS|false-" + System.nanoTime();

        cache("https://api.kiuwan.com|user-a|pass-a").put(key, "data for A");

        // Same command, same filter, different user: must not see A's data.
        assertNull(cache("https://api.kiuwan.com|user-b|pass-b").get(key),
            "a different user must not read another's cache");
    }

    @Test
    @DisplayName("Rotating the password invalidates the previous cache")
    void rotatingPasswordInvalidatesCache() {
        String key = "defects|APP-" + System.nanoTime();

        cache("https://api.kiuwan.com|user|old-pass").put(key, "stale data");

        assertNull(cache("https://api.kiuwan.com|user|new-pass").get(key));
    }

    @Test
    @DisplayName("Different Kiuwan instances do not share entries")
    void differentInstancesDoNotShareEntries() {
        String key = "apps|X-" + System.nanoTime();

        cache("https://api.kiuwan.com|user|pass").put(key, "cloud data");

        assertNull(cache("https://kiuwan.internal.example|user|pass").get(key));
    }

    @Test
    @DisplayName("A disabled cache neither reads nor writes")
    void disabledCacheIsInert() {
        DiskCache disabled = new DiskCache(false, FIVE_MINUTES, false, "scope");
        String key = "key-" + System.nanoTime();

        disabled.put(key, "something");

        assertNull(disabled.get(key));
        assertNull(cache("scope").get(key), "it must not have written anything");
    }

    @Test
    @DisplayName("A zero TTL disables the cache in practice")
    void zeroTtlDisablesCache() {
        DiskCache noTtl = new DiskCache(true, Duration.ZERO, false, "scope");
        String key = "key-" + System.nanoTime();

        noTtl.put(key, "something");

        assertNull(noTtl.get(key));
    }

    @Test
    @DisplayName("An expired entry is not returned and gets deleted")
    void expiredEntryIsDiscarded() throws IOException {
        DiskCache writer = cache("scope-exp");
        String key = "key-exp-" + System.nanoTime();
        writer.put(key, "stale data");

        // Age the file rather than waiting: the TTL is measured from the modification time.
        Path file = findEntryContaining(writer.location(), "stale data");
        assertNotNull(file, "the entry should have been written");
        Files.setLastModifiedTime(file,
            java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(3600)));

        assertNull(writer.get(key), "an hour-old entry with a 5-minute TTL is no good");
        assertFalse(Files.exists(file), "the expired entry must be deleted");
    }

    @Test
    @DisplayName("clear empties the cache directory")
    void clearEmptiesCache() throws IOException {
        DiskCache c = cache("scope-clear");
        c.put("k1-" + System.nanoTime(), "a");
        c.put("k2-" + System.nanoTime(), "b");

        int deleted = c.clear();

        assertTrue(deleted >= 2, "it should have deleted at least the two entries written");
    }

    /** Finds the cache file whose content matches, so the test can manipulate it. */
    private static Path findEntryContaining(Path root, String content) throws IOException {
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (Stream<Path> paths = Files.list(root)) {
            for (Path p : paths.filter(Files::isRegularFile).toList()) {
                if (Files.readString(p).equals(content)) {
                    return p;
                }
            }
        }
        return null;
    }
}
