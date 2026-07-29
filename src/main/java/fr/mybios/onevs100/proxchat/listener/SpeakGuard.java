package fr.mybios.onevs100.proxchat.listener;

import fr.mybios.onevs100.proxchat.api.BubbleGate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Consults the optional {@link BubbleGate} a host plugin may have registered, and holds the
 * whole absence/failure policy so the chat path stays a straight line. Sits beside the
 * {@code RateGuard} in the accept path: this one asks "may this player speak at all", the rate
 * guard asks "not too fast".
 *
 * <p>The provider is resolved through a supplier on EVERY call — never cached — so registration
 * order between plugins is irrelevant and a host that registers late (or hot-reloads) is picked
 * up without a ProxChat restart. Resolution is a lock-cheap map lookup in Bukkit's
 * {@code ServicesManager}; the alternative (cache + service-event invalidation) would buy
 * nothing measurable on a path that already serialises a chat component.
 *
 * <p><b>Fail-OPEN, twice over</b>: no provider means the feature is absent and everyone speaks,
 * and a provider that throws is treated as absent for that message. A moderation feature that is
 * missing or broken must never silence a server — the opposite posture from the mode machine,
 * which fails closed because it gates the whole channel. The throw is logged once per server
 * session (a broken provider is a programming error, and per-message logging on the async chat
 * thread would be its own outage at a hundred players).
 *
 * <p>Thread-safe and non-blocking by construction: no state but a one-shot flag, and the gate
 * contract requires the same of the provider.
 */
public final class SpeakGuard {

    private final Supplier<BubbleGate> provider;
    private final Consumer<String> warnSink;
    private final AtomicBoolean warned = new AtomicBoolean();

    /**
     * @param provider resolves the registered gate; null result = none registered
     * @param warnSink one-shot English warning sink (the plugin logger)
     */
    public SpeakGuard(Supplier<BubbleGate> provider, Consumer<String> warnSink) {
        this.provider = provider;
        this.warnSink = warnSink;
    }

    /** Whether this player may turn a message into a bubble. Any thread; never throws. */
    public boolean maySpeak(UUID speaker) {
        try {
            BubbleGate gate = provider.get();
            return gate == null || gate.maySpeak(speaker);
        } catch (RuntimeException e) {
            warnOnce(e);
            return true;
        }
    }

    /** {@code /proxchat status} fragment: makes an absent gate visible, since absence = allow. */
    public String describe() {
        BubbleGate gate;
        try {
            gate = provider.get();
        } catch (RuntimeException e) {
            return "gate=error";
        }
        return gate == null ? "gate=none" : "gate=registered";
    }

    private void warnOnce(RuntimeException e) {
        if (warned.compareAndSet(false, true)) {
            warnSink.accept("bubble gate provider threw " + e
                    + " — that message was allowed through; the gate contract requires a pure,"
                    + " non-blocking, thread-safe check. Further occurrences are not logged.");
        }
    }
}
