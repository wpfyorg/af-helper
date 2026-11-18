package io.github.wpfyorg.afhelper.net;

/**
 * Cancellation gate shared by every HTTP call of one ODU transport.
 *
 * Cancelling must stop the request that is currently in flight, but it must not
 * poison the transport for the next user action: a disconnect followed by a
 * reconnect has to be able to sign in again. Each logical request therefore
 * records the session it belongs to, and {@link #begin()} starts the next one.
 */
final class Cancellation {
    private long session;
    private long cancelledSession = -1;

    /** Opens a new session and returns its token. */
    synchronized long begin() {
        return ++session;
    }

    /** The session a request that starts now belongs to. */
    synchronized long current() {
        return session;
    }

    /** Cancels the current session; sessions that already began stay cancelled. */
    synchronized void cancel() {
        cancelledSession = session;
    }

    /** True when the given session was cancelled at or after it began. */
    synchronized boolean isCancelled(long requestSession) {
        return cancelledSession >= requestSession;
    }
}
