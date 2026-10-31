package net.psunset.translatorpp.exception;

/**
 * A translation service error, with a message fit to show the player.
 */
public final class ServiceException extends RuntimeException {
    /** The HTTP status, or 0 when no request was made. */
    public final int statusCode;
    /** How long the service asked us to wait, or 0 if it didn't say. */
    public final long retryAfterSeconds;

    public ServiceException(String message, int statusCode, long retryAfterSeconds) {
        super(message);
        this.statusCode = statusCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
