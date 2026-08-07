package io.github.serpean.cliuwan;

import io.github.serpean.cliuwan.client.Credentials;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the credentials file.
 *
 * <p>The path is redirected by pointing {@code user.home} at a temporary directory, so the
 * real HOME of whoever runs the suite is never touched.
 */
class CredentialsTest {

    @TempDir
    Path tempHome;

    private String originalUserHome;

    @BeforeEach
    void redirectHome() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", originalUserHome);
    }

    @Test
    @DisplayName("The file path hangs off the user's home")
    void pathIsUnderUserHome() {
        assertEquals(tempHome.resolve(".config/cliuwan/credentials"), Credentials.path());
    }

    @Test
    @DisplayName("Saving and reading back returns the same credentials")
    void saveThenResolveRoundTrip() {
        Credentials.save("my_username", "my_password");

        Credentials read = Credentials.resolve();

        assertEquals("my_username", read.user());
        assertEquals("my_password", read.pass());
    }

    @Test
    @DisplayName("The stored file is not readable by other users")
    void savedFileIsNotWorldReadable() throws IOException {
        Path file = Credentials.save("username", "secret");

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);

        assertTrue(perms.contains(PosixFilePermission.OWNER_READ));
        assertFalse(perms.contains(PosixFilePermission.GROUP_READ), "must not be group-readable");
        assertFalse(perms.contains(PosixFilePermission.OTHERS_READ), "must not be world-readable");
    }

    @Test
    @DisplayName("The config directory is not left open to others either")
    void configDirectoryIsPrivate() throws IOException {
        Path file = Credentials.save("username", "secret");

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file.getParent());

        assertFalse(perms.contains(PosixFilePermission.OTHERS_READ));
        assertFalse(perms.contains(PosixFilePermission.OTHERS_EXECUTE));
    }

    @Test
    @DisplayName("Saving twice overwrites instead of appending")
    void saveOverwrites() {
        Credentials.save("first", "pass1");
        Credentials.save("second", "pass2");

        Credentials read = Credentials.resolve();

        assertEquals("second", read.user());
        assertEquals("pass2", read.pass());
    }

    @Test
    @DisplayName("Handles passwords with special characters and equals signs")
    void handlesSpecialCharacters() {
        String tricky = "p=ss:w#rd with spaces=and=equals";
        Credentials.save("username", tricky);

        assertEquals(tricky, Credentials.resolve().pass());
    }

    @Test
    @DisplayName("Ignores comments and blank lines in the file")
    void ignoresCommentsAndBlankLines() throws IOException {
        Path file = Credentials.path();
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            # this is a comment
            user=username

            # another comment
            pass=secret
            line-without-equals
            """);

        Credentials read = Credentials.resolve();

        assertEquals("username", read.user());
        assertEquals("secret", read.pass());
    }

    @Test
    @DisplayName("With no file and no environment, the error suggests 'cliuwan login'")
    void errorMessageSuggestsLogin() {
        var error = assertThrows(Credentials.MissingCredentialsException.class,
            Credentials::resolve);

        assertTrue(error.getMessage().contains("cliuwan login"), error.getMessage());
    }

    @Test
    @DisplayName("Source is FILE with only a file, and NONE with nothing at all")
    void reportsSource() {
        assertEquals(Credentials.Source.NONE, Credentials.source());

        Credentials.save("username", "secret");

        assertEquals(Credentials.Source.FILE, Credentials.source());
    }

    @Test
    @DisplayName("fileExists reflects whether the file is really there")
    void fileExistsReflectsReality() {
        assertFalse(Credentials.fileExists());

        Credentials.save("username", "secret");

        assertTrue(Credentials.fileExists());
    }

    @Test
    @DisplayName("logout deletes the file and is idempotent")
    void deleteIsIdempotent() {
        Credentials.save("username", "secret");

        assertTrue(Credentials.delete(), "the first call must delete it");
        assertFalse(Credentials.delete(), "the second has nothing left to delete");
        assertFalse(Credentials.fileExists());
    }

    @Test
    @DisplayName("toString does not reveal the password")
    void toStringHidesPassword() {
        String text = new Credentials("username", "secret-that-must-not-leak").toString();

        assertFalse(text.contains("secret-that-must-not-leak"), text);
        assertTrue(text.contains("username"), text);
    }
}
