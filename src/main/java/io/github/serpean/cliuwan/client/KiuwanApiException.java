package io.github.serpean.cliuwan.client;

/**
 * An error returned by the Kiuwan API, or raised while talking to it.
 *
 * <p>Carries the HTTP status so the CLI can translate it into a distinguishable exit code.
 */
public class KiuwanApiException extends RuntimeException {

    private final int statusCode;

    public KiuwanApiException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public KiuwanApiException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
