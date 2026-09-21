package com.velox.core.loader;

/**
 * A load could not be completed, for a reason that is not the loader's own
 * unchecked exception: the waiting thread was interrupted, or the loader failed
 * with something that is neither a {@link RuntimeException} nor an {@link Error}.
 *
 * <p>When a loader throws an ordinary {@code RuntimeException}, that exact
 * exception is what every waiting caller receives; this wrapper exists only for
 * the leftover cases.
 */
public class CacheLoadException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message what went wrong
     * @param cause   the underlying failure
     */
    public CacheLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
