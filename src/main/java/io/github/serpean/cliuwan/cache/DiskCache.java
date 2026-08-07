package io.github.serpean.cliuwan.cache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.stream.Stream;

/**
 * On-disk response cache with a TTL.
 *
 * <p>The CLI is a short-lived process, so an in-memory cache would not survive between
 * invocations. Keeping it on disk means a working session that queries the same application
 * several times only hits the API once.
 *
 * <p>Files live under {@code ~/.cache/cliuwan/} with restrictive permissions: they can contain
 * source paths and vulnerability details.
 */
public class DiskCache {

    private final Path root;
    private final Duration ttl;
    private final boolean enabled;
    private final boolean verbose;
    private final String scope;

    /**
     * @param scope identifies the user and the Kiuwan instance the data belongs to. It is part
     *              of the key, so different credentials or instances never share entries:
     *              without it, switching accounts could serve cached data the current account
     *              is not allowed to see.
     */
    public DiskCache(boolean enabled, Duration ttl, boolean verbose, String scope) {
        this.enabled = enabled;
        this.ttl = ttl;
        this.verbose = verbose;
        this.scope = scope == null ? "" : scope;
        this.root = resolveRoot();
    }

    private static Path resolveRoot() {
        String xdg = System.getenv("XDG_CACHE_HOME");
        Path base = (xdg != null && !xdg.isBlank())
            ? Paths.get(xdg)
            : Paths.get(System.getProperty("user.home"), ".cache");
        return base.resolve("cliuwan");
    }

    /**
     * Returns the cached entry if it exists and has not expired.
     *
     * @return the cached content, or {@code null} if there is nothing usable
     */
    public String get(String key) {
        if (!enabled || ttl.isZero() || ttl.isNegative()) {
            return null;
        }
        Path file = fileFor(key);
        try {
            if (!Files.isReadable(file)) {
                return null;
            }
            Instant modified = Files.getLastModifiedTime(file).toInstant();
            if (modified.plus(ttl).isBefore(Instant.now())) {
                if (verbose) {
                    System.err.println("cliuwan: cache expired for " + key);
                }
                Files.deleteIfExists(file);
                return null;
            }
            if (verbose) {
                System.err.println("cliuwan: cache HIT " + key);
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // An unreadable cache must never break the query: ignore it and hit the API.
            return null;
        }
    }

    /** Stores an entry. A write failure does not interrupt the query. */
    public void put(String key, String value) {
        if (!enabled || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        try {
            Files.createDirectories(root);
            restrictPermissions(root);
            Path file = fileFor(key);
            // Atomic write: an interrupted CLI must not leave a half-written entry.
            Path tmp = Files.createTempFile(root, "tmp-", ".json");
            Files.writeString(tmp, value, StandardCharsets.UTF_8);
            restrictPermissions(tmp);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            if (verbose) {
                System.err.println("cliuwan: could not write to cache: " + e.getMessage());
            }
        }
    }

    /**
     * Empties the cache.
     *
     * @return the number of files removed
     */
    public int clear() throws IOException {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            var files = paths.filter(Files::isRegularFile).sorted(Comparator.reverseOrder()).toList();
            int deleted = 0;
            for (Path p : files) {
                if (Files.deleteIfExists(p)) {
                    deleted++;
                }
            }
            return deleted;
        }
    }

    /** Cache directory, for showing it to the user. */
    public Path location() {
        return root;
    }

    private Path fileFor(String key) {
        // The scope carries credentials, so only its hash is stored, never the plain value.
        return root.resolve(sha256(scope + " " + key) + ".json");
    }

    private static void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, Files.isDirectory(path)
                ? java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")
                : java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException e) {
            // Non-POSIX filesystem: carry on without hardening permissions.
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available in this JVM", e);
        }
    }
}
