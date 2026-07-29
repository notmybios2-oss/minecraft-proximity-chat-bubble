package fr.mybios.onevs100.proxchat.api;

import java.util.UUID;

/**
 * Optional per-speaker gate: lets a host plugin's moderation surface silence ONE player's
 * bubbles without touching the plugin-wide {@link Mode}. Part of the frozen integration
 * surface — the signature must not change once host plugins compile against it.
 *
 * <p><b>Registration is the reverse of {@link ProxChatService}:</b> the HOST registers an
 * implementation, ProxChat consults it. Register at any {@code ServicePriority} and unregister
 * on disable:
 * {@code getServer().getServicesManager().register(BubbleGate.class, impl, plugin, ServicePriority.Normal)}.
 * ProxChat resolves the provider lazily on every message (never caches it), so registration
 * order is irrelevant and a hot-reloaded host is picked up without a ProxChat restart.
 *
 * <p><b>Absence is allow.</b> No registered provider means the feature is absent and every
 * player may speak — deliberately the opposite polarity of the mode machine's fail-closed
 * posture: {@link Mode} gates the whole channel, this gate only filters individual speakers, so
 * a missing moderation plugin must never silence a server. A provider that THROWS is treated as
 * absent for that message (allowed) and logged once per server session. Operators can tell the
 * two apart with {@code /proxchat status}, which reports {@code gate=registered} or
 * {@code gate=none}.
 *
 * <p><b>Thread contract: {@link #maySpeak} is called from the async chat thread AND from the
 * speaker's region thread</b> (once before the rate guard, once more immediately before the
 * bubble is rendered, so a mute landing mid-flight still drops the bubble). Implementations MUST
 * therefore be pure, non-blocking and thread-safe: take the UUID, consult in-memory state, return.
 * Do NOT touch entities, worlds, scoreboards or any other region-owned state — on a regionised
 * server that state is owned by a thread that is not the caller — and do no I/O, no database
 * round-trip and no lock that a tick thread could hold. A concurrent set of muted UUIDs is the
 * intended shape.
 *
 * <p><b>What a denial does and does not do:</b> the message is dropped silently and consumes no
 * rate-limit slot — player feedback belongs to the moderation command that muted them, not to
 * every swallowed message. A denial produces no bubble, so nothing is written to the conversation
 * log either. It does NOT un-cancel the chat event (the broadcast stays dead, fail-closed
 * anonymity) and it does NOT retract bubbles the player already had in flight: those fade on
 * their own {@code lifetime-seconds}, so a mute silences new speech immediately and residual
 * speech within one bubble lifetime. Clear them at once with
 * {@link ProxChatService#clearAll()} if a host needs instant silence server-wide.
 */
@FunctionalInterface
public interface BubbleGate {

    /**
     * Whether this player's next message may become a bubble. Pure, non-blocking, any-thread —
     * see the interface contract. Throwing is not a way to deny: an exception is absorbed as
     * "allowed" so a broken provider cannot silence the server.
     *
     * @param speaker the speaking player's UUID, never null
     * @return true to let the bubble render, false to drop the message silently
     */
    boolean maySpeak(UUID speaker);
}
