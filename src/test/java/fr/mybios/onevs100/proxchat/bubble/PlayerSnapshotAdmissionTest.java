package fr.mybios.onevs100.proxchat.bubble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The admission predicate — the anonymity guarantee itself, in isolation.
 *
 * <p>Everything else in the plugin defers to this rule: a client that is not admitted never
 * receives the bubble entity, so it cannot read the text no matter what it is running. That
 * makes these the highest-value assertions in the suite, and they are cheap because the rule is
 * pure snapshot math with no Bukkit types in sight.
 *
 * <p>The concurrency angle (region merges) is covered structurally rather than by hammering
 * threads: what makes a merge harmless is that everything crossing a thread boundary here is an
 * immutable record, so a reader either sees the old sample or the new one and never a torn mix.
 * {@link #snapshotComponentsAreImmutableSoARegionHandoffCannotTearThem} pins exactly that, and
 * fails the moment someone adds a mutable component.
 */
class PlayerSnapshotAdmissionTest {

    private static final UUID OVERWORLD = UUID.fromString("00000000-0000-0000-0000-00000000ffff");
    private static final UUID NETHER = UUID.fromString("00000000-0000-0000-0000-00000000fffe");

    /** The shipped default radius after the 2026-08-01 ruling: 32 blocks. */
    private static final double R2 = 32.0 * 32.0;

    private static PlayerSnapshot at(UUID world, double x, double y, double z) {
        return new PlayerSnapshot("player", world, x, y, z);
    }

    private static PlayerSnapshot origin() {
        return at(OVERWORLD, 0, 0, 0);
    }

    // ---------------------------------------------------------------- the rule

    @Test
    void aSpeakerAlwaysAdmitsThemself() {
        // Q2: the speaker sees their own bubble, so they are in their own desired set.
        PlayerSnapshot me = at(OVERWORLD, 100.5, 64, -20.25);
        assertTrue(me.admits(me, R2));
        assertTrue(me.admits(me, 0.0)); // even a zero radius: distance to self is exactly 0
    }

    @Test
    void admissionIsSymmetric() {
        // If A can read B, B can read A. An asymmetry would mean someone is readable by a player
        // they cannot themselves read — a one-way mirror, which is not what "proximity" means.
        Random rng = new Random(5);
        for (int i = 0; i < 20_000; i++) {
            PlayerSnapshot a = at(OVERWORLD, rng.nextDouble() * 80 - 40,
                    rng.nextDouble() * 80 - 40, rng.nextDouble() * 80 - 40);
            PlayerSnapshot b = at(OVERWORLD, rng.nextDouble() * 80 - 40,
                    rng.nextDouble() * 80 - 40, rng.nextDouble() * 80 - 40);
            assertEquals(a.admits(b, R2), b.admits(a, R2));
        }
    }

    @Test
    void exactlyAtTheRadiusIsAdmittedAndAHairBeyondIsNot() {
        // The boundary the book's walk-in/walk-out entry paces out. <= is the documented choice.
        assertTrue(origin().admits(at(OVERWORLD, 32.0, 0, 0), R2));
        assertFalse(origin().admits(at(OVERWORLD, 32.0001, 0, 0), R2));
        assertTrue(origin().admits(at(OVERWORLD, 31.9999, 0, 0), R2));
    }

    @Test
    void theRadiusIsSphericalNotACylinder() {
        // Height counts. A player 40 blocks straight up is out of range even though they are
        // directly overhead — the natural "optimization" to horizontal distance would silently
        // widen the guarantee for anyone in a tower, a mineshaft, or the void.
        assertFalse(origin().admits(at(OVERWORLD, 0, 40, 0), R2));
        assertTrue(origin().admits(at(OVERWORLD, 0, 30, 0), R2));
        // 3-4-5 style check: (20, 20, 20) is 34.6 blocks away, so out, despite each axis being in.
        assertFalse(origin().admits(at(OVERWORLD, 20, 20, 20), R2));
    }

    @Test
    void differentWorldsNeverAdmitEvenAtIdenticalCoordinates() {
        // The nether sits at 1/8 scale, so overworld and nether players constantly share
        // coordinates. World identity is checked first for exactly this reason.
        assertFalse(origin().admits(at(NETHER, 0, 0, 0), R2));
        assertFalse(origin().admits(at(NETHER, 1, 0, 0), Double.MAX_VALUE));
    }

    // ---------------------------------------------------------------- failure directions

    @Test
    void corruptCoordinatesFailClosed() {
        // A NaN or infinite sample can only come from something already broken. Every comparison
        // against NaN is false, so the rule denies — the safe direction: no bubble, never a leak.
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY}) {
            assertFalse(origin().admits(at(OVERWORLD, bad, 0, 0), R2), "admitted on " + bad);
            assertFalse(at(OVERWORLD, bad, 0, 0).admits(origin(), R2), "admitted from " + bad);
        }
        assertFalse(origin().admits(at(OVERWORLD, 1, 0, 0), Double.NaN));
    }

    @Test
    void worldBorderCoordinatesKeepTheirPrecision() {
        // At the vanilla border (~30 million) doubles still hold whole blocks exactly, so two
        // players standing next to each other out there are admitted and the far side is not.
        double edge = 29_999_984.0;
        assertTrue(at(OVERWORLD, edge, 0, 0).admits(at(OVERWORLD, edge - 1, 0, 0), R2));
        assertFalse(at(OVERWORLD, edge, 0, 0).admits(at(OVERWORLD, -edge, 0, 0), R2));
    }

    @Test
    void theRuleIsExactlyItsDefinitionOverRandomInput() {
        // Property form: admitted <=> same world AND squared distance within the radius. Spans
        // worlds and a range wide enough to straddle the boundary constantly.
        Random rng = new Random(2026);
        for (int i = 0; i < 50_000; i++) {
            UUID wa = rng.nextBoolean() ? OVERWORLD : NETHER;
            UUID wb = rng.nextBoolean() ? OVERWORLD : NETHER;
            PlayerSnapshot a = at(wa, rng.nextDouble() * 100 - 50, rng.nextDouble() * 100 - 50,
                    rng.nextDouble() * 100 - 50);
            PlayerSnapshot b = at(wb, rng.nextDouble() * 100 - 50, rng.nextDouble() * 100 - 50,
                    rng.nextDouble() * 100 - 50);
            boolean expected = wa.equals(wb) && a.distanceSquaredTo(b) <= R2;
            assertEquals(expected, a.admits(b, R2));
        }
    }

    // ---------------------------------------------------------------- the cross-thread contract

    @Test
    void snapshotComponentsAreImmutableSoARegionHandoffCannotTearThem() {
        // Admission reads other players' samples from whatever thread the speaker's region
        // happens to be on, and Folia moves regions between threads as they split and merge.
        // That is only safe because a sample is an immutable value: a reader sees the whole old
        // sample or the whole new one. Adding a mutable component (a Location, an array, a
        // collection) would quietly reintroduce the shared-mutable-state hazard the whole design
        // avoids — so the allowed set is spelled out and this fails if it grows.
        Set<Class<?>> immutable = Set.of(String.class, UUID.class, double.class);
        for (RecordComponent component : PlayerSnapshot.class.getRecordComponents()) {
            assertTrue(immutable.contains(component.getType()),
                    "PlayerSnapshot." + component.getName() + " is a " + component.getType()
                            + ", which is not known-immutable — admission crosses region threads");
        }
        for (Field field : PlayerSnapshot.class.getDeclaredFields()) {
            assertTrue(Modifier.isFinal(field.getModifiers()), field + " is not final");
        }
    }
}
