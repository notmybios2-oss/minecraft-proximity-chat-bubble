package fr.mybios.onevs100.proxchat.bubble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The whole admission pass — who ends up in a speaker's desired set — including what happens to
 * it while other players are moving and changing worlds underneath.
 *
 * <p>Region merges are the reason this matters. A speaker's admission runs on whatever thread
 * currently owns their region, and Folia moves regions between threads as they split and merge,
 * while every other player's heartbeat republishes their own sample concurrently. The pass is
 * safe because the map is concurrent and each sample is an immutable value read exactly once —
 * so a viewer's world and position are always judged as one consistent pair. Read a sample twice
 * and a player crossing a portal between the two reads could pass the world check on their old
 * sample and the distance check on their new one, which is a cross-world leak.
 */
class AdmissionPassTest {

    private static final UUID OVERWORLD = UUID.fromString("00000000-0000-0000-0000-00000000ffff");
    private static final UUID NETHER = UUID.fromString("00000000-0000-0000-0000-00000000fffe");
    private static final double R2 = 32.0 * 32.0;

    private static PlayerSnapshot at(UUID world, double x) {
        return new PlayerSnapshot("p", world, x, 0, 0);
    }

    // ---------------------------------------------------------------- the pass, deterministic

    @Test
    void theSpeakerIsInTheirOwnAudienceAndDistantPlayersAreNot() {
        UUID speaker = UUID.randomUUID();
        UUID near = UUID.randomUUID();
        UUID edge = UUID.randomUUID();
        UUID far = UUID.randomUUID();
        UUID otherWorld = UUID.randomUUID();
        Map<UUID, PlayerSnapshot> snapshots = new ConcurrentHashMap<>();
        snapshots.put(speaker, at(OVERWORLD, 0));
        snapshots.put(near, at(OVERWORLD, 5));
        snapshots.put(edge, at(OVERWORLD, 32));       // exactly at the radius: admitted
        snapshots.put(far, at(OVERWORLD, 32.5));      // just outside: not
        snapshots.put(otherWorld, at(NETHER, 0));     // same coordinates, different world: not

        Set<UUID> desired = BubbleService.desiredFor(speaker, snapshots, R2);
        assertEquals(Set.of(speaker, near, edge), desired);
    }

    @Test
    void aSpeakerWithNoPublishedSampleRendersToNobody() {
        // A message typed within a tick of joining, before the first heartbeat published a
        // sample. The answer must be the empty set, not "everyone" — a bubble with no admission
        // math behind it would be a broadcast.
        Map<UUID, PlayerSnapshot> snapshots = new ConcurrentHashMap<>();
        snapshots.put(UUID.randomUUID(), at(OVERWORLD, 1));
        assertEquals(Set.of(), BubbleService.desiredFor(UUID.randomUUID(), snapshots, R2));
    }

    @Test
    void anEmptyServerAdmitsNobodyRatherThanFailing() {
        assertEquals(Set.of(),
                BubbleService.desiredFor(UUID.randomUUID(), new ConcurrentHashMap<>(), R2));
    }

    // ---------------------------------------------------------------- the pass, under churn

    @Test
    void aPlayerFlippingWorldsIsNeverAdmittedOnAStaleWorldCheck() throws Exception {
        // The torn-read hazard, made as likely as possible: a "portal hopper" sits at the
        // speaker's exact coordinates and does nothing but flip worlds as fast as it can, while
        // a control player stays permanently in the nether and a neighbour stays permanently in
        // range. If the pass ever judged world and position from two different samples, the
        // hopper would appear in an overworld audience while standing in the nether.
        UUID speaker = UUID.randomUUID();
        UUID hopper = UUID.randomUUID();
        UUID neighbour = UUID.randomUUID();
        UUID alwaysNether = UUID.randomUUID();

        Map<UUID, PlayerSnapshot> snapshots = new ConcurrentHashMap<>();
        snapshots.put(speaker, at(OVERWORLD, 0));
        snapshots.put(hopper, at(OVERWORLD, 0));
        snapshots.put(neighbour, at(OVERWORLD, 10));
        snapshots.put(alwaysNether, at(NETHER, 0));

        AtomicReference<Throwable> failure = new AtomicReference<>();
        int rounds = 200_000;

        Thread mover = new Thread(() -> {
            try {
                for (int i = 0; i < rounds; i++) {
                    // Same coordinates in both worlds: only the world id can exclude them.
                    snapshots.put(hopper, at((i & 1) == 0 ? OVERWORLD : NETHER, 0));
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, "admission-mover");

        Thread drifter = new Thread(() -> {
            try {
                for (int i = 0; i < rounds; i++) {
                    // A fourth player walking in and out of range, to keep the map churning.
                    snapshots.put(UUID.nameUUIDFromBytes(new byte[] {(byte) (i % 7)}),
                            at(OVERWORLD, (i % 100) - 50));
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, "admission-drifter");

        mover.start();
        drifter.start();
        int passes = 0;
        while (mover.isAlive() || drifter.isAlive()) {
            Set<UUID> desired = BubbleService.desiredFor(speaker, snapshots, R2);
            assertTrue(desired.contains(speaker), "speaker fell out of their own audience");
            assertTrue(desired.contains(neighbour), "a stationary in-range viewer was dropped");
            assertFalse(desired.contains(alwaysNether), "admitted a player in another world");
            passes++;
        }
        mover.join();
        drifter.join();

        if (failure.get() != null) {
            throw new AssertionError("admission churn threw", failure.get());
        }
        assertTrue(passes > 0, "the admission pass never ran");
    }
}
