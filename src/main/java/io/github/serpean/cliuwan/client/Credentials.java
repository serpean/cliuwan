package io.github.serpean.cliuwan.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;

/**
 * Resolves and stores Kiuwan credentials.
 *
 * <p>Precedence:
 * <ol>
 *   <li>The {@code KIUWAN_USER} / {@code KIUWAN_PASS} environment variables</li>
 *   <li>The credentials file, {@code ~/.config/cliuwan/credentials} by default
 *       ({@code key=value} format), written by {@code cliuwan login}</li>
 * </ol>
 *
 * <p>There is deliberately no {@code --pass} option: a password passed as an argument ends up
 * in the shell history and is visible in {@code ps}.
 */
public record Credentials(String user, String pass) {

    /** Where the credentials came from. */
    public enum Source {
        /** Environment variables. */
        ENV,
        /** The credentials file. */
        FILE,
        /** A mix of both: username from one source, password from the other. */
        MIXED,
        /** No credentials configured. */
        NONE
    }

    /**
     * Path of the credentials file.
     *
     * <p>Computed on every call rather than cached in a constant, so that it honours
     * {@code CLIUWAN_CREDENTIALS_FILE} and can be redirected in tests.
     */
    public static Path path() {
        String override = System.getenv("CLIUWAN_CREDENTIALS_FILE");
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".config", "cliuwan", "credentials");
    }

    /** Whether a readable credentials file exists. */
    public static boolean fileExists() {
        return Files.isReadable(path());
    }

    /** Works out where credentials would come from, without actually resolving them. */
    public static Source source() {
        boolean envUser = !isBlank(System.getenv("KIUWAN_USER"));
        boolean envPass = !isBlank(System.getenv("KIUWAN_PASS"));
        if (envUser && envPass) {
            return Source.ENV;
        }
        Credentials file = fileExists() ? readFile() : null;
        boolean fileUser = file != null && !isBlank(file.user());
        boolean filePass = file != null && !isBlank(file.pass());

        if (fileUser && filePass && !envUser && !envPass) {
            return Source.FILE;
        }
        if ((envUser || fileUser) && (envPass || filePass)) {
            return Source.MIXED;
        }
        return Source.NONE;
    }

    /**
     * Resolves the credentials, or fails with a message explaining how to configure them.
     *
     * @throws MissingCredentialsException if none can be found
     */
    public static Credentials resolve() {
        String user = System.getenv("KIUWAN_USER");
        String pass = System.getenv("KIUWAN_PASS");

        if (isBlank(user) || isBlank(pass)) {
            Credentials fromFile = readFile();
            if (fromFile != null) {
                if (isBlank(user)) {
                    user = fromFile.user();
                }
                if (isBlank(pass)) {
                    pass = fromFile.pass();
                }
            }
        }

        if (isBlank(user) || isBlank(pass)) {
            throw new MissingCredentialsException("""
                No Kiuwan credentials found.

                The easiest way to set them up:

                    cliuwan login

                It asks for your username and password, checks them against the API and
                stores them in %s with 600 permissions.

                For automated environments (CI, containers):

                    export KIUWAN_USER=your_username
                    export KIUWAN_PASS=your_password
                """.formatted(path()));
        }

        return new Credentials(user, pass);
    }

    /**
     * Stores the credentials, creating the directory if needed.
     *
     * <p>The directory ends up with {@code 700} permissions and the file with {@code 600}.
     * The write is atomic so an interrupted run cannot leave a half-written file.
     *
     * @return the path of the file written
     */
    public static Path save(String user, String pass) {
        Path file = path();
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
                setPermissions(parent, "rwx------");
            }

            String content = """
                # Kiuwan credentials. Written by 'cliuwan login'.
                # This file contains a password: do not share it or commit it.
                user=%s
                pass=%s
                """.formatted(user, pass);

            Path tmp = Files.createTempFile(
                parent != null ? parent : file.toAbsolutePath().getParent(), "cred-", ".tmp");
            setPermissions(tmp, "rw-------");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            setPermissions(file, "rw-------");
            return file;
        } catch (IOException e) {
            throw new MissingCredentialsException(
                "Could not save credentials to " + file + ": " + e.getMessage());
        }
    }

    /**
     * Deletes the credentials file.
     *
     * @return {@code true} if a file was there and got deleted
     */
    public static boolean delete() {
        try {
            return Files.deleteIfExists(path());
        } catch (IOException e) {
            throw new MissingCredentialsException(
                "Could not delete " + path() + ": " + e.getMessage());
        }
    }

    private static Credentials readFile() {
        Path file = path();
        if (!Files.isReadable(file)) {
            return null;
        }
        try {
            warnIfWorldReadable(file);
            String user = null;
            String pass = null;
            List<String> lines = Files.readAllLines(file);
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int sep = line.indexOf('=');
                if (sep < 0) {
                    continue;
                }
                String key = line.substring(0, sep).trim().toLowerCase();
                String value = line.substring(sep + 1).trim();
                switch (key) {
                    case "user", "kiuwan_user" -> user = value;
                    case "pass", "password", "kiuwan_pass" -> pass = value;
                    default -> { /* unknown key: ignored */ }
                }
            }
            return new Credentials(user, pass);
        } catch (IOException e) {
            throw new MissingCredentialsException(
                "Could not read " + file + ": " + e.getMessage());
        }
    }

    /** Warns on stderr if the credentials file is readable by other users. */
    private static void warnIfWorldReadable(Path file) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            boolean exposed = perms.contains(PosixFilePermission.GROUP_READ)
                || perms.contains(PosixFilePermission.OTHERS_READ);
            if (exposed) {
                System.err.println("cliuwan: warning: " + file
                    + " is readable by other users; run: chmod 600 " + file);
            }
        } catch (IOException | UnsupportedOperationException e) {
            // Non-POSIX filesystem (Windows, for instance): nothing to check.
        }
    }

    private static void setPermissions(Path path, String posixSpec) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(posixSpec));
        } catch (IOException | UnsupportedOperationException e) {
            // Non-POSIX filesystem: carry on without hardening permissions.
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** No usable credentials. Maps to exit code 3. */
    public static class MissingCredentialsException extends RuntimeException {
        public MissingCredentialsException(String message) {
            super(message);
        }
    }

    /** Keeps the password out of logs and error messages by accident. */
    @Override
    public String toString() {
        return "Credentials[user=" + user + ", pass=***]";
    }
}
