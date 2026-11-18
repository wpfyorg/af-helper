package io.github.wpfyorg.afhelper.net;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CancellationTest {
    @Test public void cancelStopsTheRequestThatWasRunning() {
        Cancellation cancellation = new Cancellation();
        long session = cancellation.begin();
        cancellation.cancel();
        assertTrue(cancellation.isCancelled(session));
    }

    @Test public void beginAllowsTheNextRequestAfterACancel() {
        Cancellation cancellation = new Cancellation();
        long first = cancellation.begin();
        cancellation.cancel();

        long second = cancellation.begin();

        assertTrue("the cancelled request stays stopped", cancellation.isCancelled(first));
        assertFalse("a reconnect must be able to sign in again", cancellation.isCancelled(second));
    }

    @Test public void cancelBeforeWorkDoesNotPoisonTheNextSession() {
        Cancellation cancellation = new Cancellation();
        cancellation.cancel();

        long next = cancellation.begin();

        assertFalse(cancellation.isCancelled(next));
    }
}
