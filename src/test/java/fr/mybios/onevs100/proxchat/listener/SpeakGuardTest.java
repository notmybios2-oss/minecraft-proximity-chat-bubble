package fr.mybios.onevs100.proxchat.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.mybios.onevs100.proxchat.api.BubbleGate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The optional host gate: absence and failure both allow, a registered gate decides per player,
 * and the provider is re-resolved on every call (registration order between plugins is
 * irrelevant). Mirrors what {@code ChatListener} does with the answer.
 */
class SpeakGuardTest {

    private final AtomicReference<BubbleGate> registered = new AtomicReference<>(); // ServicesManager stand-in
    private final List<String> warnings = new ArrayList<>();
    private final SpeakGuard guard = new SpeakGuard(registered::get, warnings::add);

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    /** A gate that mutes exactly the given UUIDs — the intended implementation shape. */
    private static BubbleGate muting(UUID... muted) {
        Set<UUID> set = Set.of(muted);
        return speaker -> !set.contains(speaker);
    }

    @Test
    void noProviderAllowsEveryone() {
        // Fail-OPEN: a server with no moderation plugin must not silence anybody.
        assertTrue(guard.maySpeak(alice));
        assertEquals("gate=none", guard.describe());
        assertTrue(warnings.isEmpty()); // absence is normal, never logged
    }

    @Test
    void registeredGateDecidesPerPlayer() {
        registered.set(muting(alice));
        assertFalse(guard.maySpeak(alice));
        assertTrue(guard.maySpeak(bob)); // one mute never leaks onto another speaker
        assertEquals("gate=registered", guard.describe());
    }

    @Test
    void theSpeakerUuidIsPassedThroughUnchanged() {
        AtomicReference<UUID> seen = new AtomicReference<>();
        registered.set(speaker -> {
            seen.set(speaker);
            return true;
        });
        assertTrue(guard.maySpeak(alice));
        assertEquals(alice, seen.get());
    }

    @Test
    void providerIsResolvedPerCallNotCached() {
        // Registration order is irrelevant: a host enabling AFTER ProxChat is picked up live,
        // and an unregister (host disabled/reloaded) falls straight back to allow.
        assertTrue(guard.maySpeak(alice));
        registered.set(muting(alice));
        assertFalse(guard.maySpeak(alice));
        registered.set(null);
        assertTrue(guard.maySpeak(alice));
    }

    @Test
    void unmuteAppliesOnTheNextMessage() {
        registered.set(muting(alice, bob));
        assertFalse(guard.maySpeak(alice));
        registered.set(muting(bob)); // alice unmuted
        assertTrue(guard.maySpeak(alice));
        assertFalse(guard.maySpeak(bob));
    }

    @Test
    void throwingProviderFailsOpenAndWarnsExactlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        registered.set(speaker -> {
            calls.incrementAndGet();
            throw new IllegalStateException("broken gate");
        });

        assertTrue(guard.maySpeak(alice)); // a broken gate must not silence the server
        assertTrue(guard.maySpeak(bob));
        assertTrue(guard.maySpeak(alice));

        assertEquals(3, calls.get()); // still consulted every time — no poisoning, it may recover
        assertEquals(1, warnings.size()); // once per server session, not once per message
        assertTrue(warnings.get(0).contains("broken gate"));
    }

    @Test
    void describeReportsAResolverFailureDistinctly() {
        // Only reachable if the services lookup itself blows up; an operator needs to see that
        // "gate=none" (nothing registered) and a broken lookup are not the same thing.
        SpeakGuard broken = new SpeakGuard(() -> {
            throw new IllegalStateException("no services manager");
        }, warnings::add);
        assertEquals("gate=error", broken.describe());
        assertTrue(broken.maySpeak(alice)); // and it still allows speech
        assertEquals(1, warnings.size());
    }
}
