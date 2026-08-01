package fr.mybios.onevs100.proxchat.bubble;

import java.util.UUID;

/**
 * Cross-thread-readable position sample, published by each player's heartbeat on their OWNING
 * region thread and read by every speaker's admission pass. Plain immutable data — the whole
 * point is that admission math never reads a live entity from a foreign region (Folia's
 * cross-region guards make that fatal). Carries the name so the conversation log resolves
 * audience names from the same snapshot the admission came from.
 */
public record PlayerSnapshot(String name, UUID worldId, double x, double y, double z) {

    public double distanceSquaredTo(PlayerSnapshot other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * THE admission predicate — the anonymity guarantee in one line: a viewer is admitted only if
     * they share the speaker's world AND sit within the radius. Exactly-at-radius is admitted
     * ({@code <=}), which is what the walk-in/walk-out test measures against.
     *
     * <p>Pure, total and side-effect-free on purpose: this is the rule everything else defers to,
     * so it is property-testable without a server. Never widen it to "or the viewer asked nicely".
     */
    public boolean admits(PlayerSnapshot viewer, double radiusSquared) {
        return worldId.equals(viewer.worldId()) && distanceSquaredTo(viewer) <= radiusSquared;
    }
}
