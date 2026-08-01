package fr.mybios.onevs100.proxchat;

import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Immutable view of config.yml (height default is the in-game-tuned value). Parsed once at
 * enable; /proxchat reload re-parses and swaps the whole record (readers hold a Supplier,
 * never a stale field). Out-of-range values are clamped with an English warning rather than
 * refused: a typo in a deployed config must never keep the plugin from enabling.
 */
public record ProxChatConfig(
        double radiusBlocks,
        int lifetimeSeconds,
        int maxPerPlayer,
        double heightAboveHead,
        double stackSpacing,
        boolean hideOnSneak,
        int maxMessageLength,
        int lineWidth,
        float viewRange,
        long minMessageIntervalMs,
        boolean conversationLogEnabled,
        int conversationRetentionDays,
        boolean conversationLogAdmits) {

    /**
     * A Display's client-side cull distance is {@code view-range × this} blocks. The vanilla
     * client additionally scales it by its own Entity Distance video setting, so a client below
     * 100% culls proportionally earlier — client-side and not observable from here.
     */
    public static final double VIEW_RANGE_BLOCK_BASE = 64.0;

    /** How far the cull belt must clear the admission radius. See {@link #pairCullBelt}. */
    public static final double CULL_BELT_FACTOR = 1.2;

    /** Upper bound of the view-range key (128 blocks of cull at the 64-block base). */
    public static final double VIEW_RANGE_MAX = 2.0;

    /** Absorbs float↔double round-tripping so an exactly-paired config never trips the raise. */
    private static final double PAIRING_EPSILON = 1e-6;

    public static final ProxChatConfig DEFAULTS = new ProxChatConfig(
            // radius 32 + view-range 0.6 are a PAIR (owner ruling 2026-08-01): 0.6 × 64 = 38.4
            // blocks of cull around a 32-block radius. Changing one without the other is the
            // mistake pairCullBelt exists to catch. Height 0.3 is the live-tuned ride-anchor
            // offset for servers running without nametags, and matches the shipped config.yml.
            32.0, 8, 3, 0.3, 0.30, true, 96, 200, 0.6f, 750,
            // Conversation log ships OFF, keep-forever (retention 0 = never prune — owner
            // ruling: footage editing can happen up to a year later), admits recorded.
            false, 0, true);

    public static ProxChatConfig from(ConfigurationSection root, Consumer<String> warn) {
        ConfigurationSection s = root.getConfigurationSection("bubbles");
        if (s == null) {
            warn.accept("missing 'bubbles' section - using defaults");
            return DEFAULTS;
        }
        ProxChatConfig d = DEFAULTS;
        // Missing section = all defaults: the splice mechanism for deployments predating the key.
        ConfigurationSection log = root.getConfigurationSection("conversation-log");
        double radiusBlocks =
                clamp(s.getDouble("radius-blocks", d.radiusBlocks), 1.0, 128.0, "radius-blocks", warn);
        float viewRange = (float) clamp(s.getDouble("view-range", d.viewRange),
                0.05, VIEW_RANGE_MAX, "view-range", warn);
        return new ProxChatConfig(
                radiusBlocks,
                (int) clamp(s.getInt("lifetime-seconds", d.lifetimeSeconds), 1, 120, "lifetime-seconds", warn),
                (int) clamp(s.getInt("max-per-player", d.maxPerPlayer), 1, 10, "max-per-player", warn),
                clamp(s.getDouble("height-above-head", d.heightAboveHead), 0.0, 10.0, "height-above-head", warn),
                clamp(s.getDouble("stack-spacing", d.stackSpacing), 0.05, 2.0, "stack-spacing", warn),
                s.getBoolean("hide-on-sneak", d.hideOnSneak),
                (int) clamp(s.getInt("max-message-length", d.maxMessageLength), 1, 512, "max-message-length", warn),
                (int) clamp(s.getInt("line-width", d.lineWidth), 10, 1000, "line-width", warn),
                pairCullBelt(radiusBlocks, viewRange, warn),
                (long) clamp(s.getLong("min-message-interval-ms", d.minMessageIntervalMs), 0, 60_000, "min-message-interval-ms", warn),
                log != null && log.getBoolean("enabled", d.conversationLogEnabled),
                log == null ? d.conversationRetentionDays
                        : (int) clamp(log.getInt("retention-days", d.conversationRetentionDays),
                                0, 3650, "conversation-log.retention-days", warn),
                log == null ? d.conversationLogAdmits
                        : log.getBoolean("log-admits", d.conversationLogAdmits));
    }

    /**
     * The cull-belt pairing rule — owner ruling 2026-08-01, "if you can see a player you can
     * read them".
     *
     * <p>Three belts decide whether an admitted viewer actually SEES a bubble:
     * <ol>
     *   <li><b>admission</b> — {@code radius-blocks}, server-authoritative, the hard guarantee:
     *       nobody outside it ever receives the entity;</li>
     *   <li><b>server tracking</b> — {@code spigot.yml entity-tracking-range.display} (128 by
     *       default) further bounded by the server's view-distance: below the radius, the client
     *       never receives the entity at all;</li>
     *   <li><b>client cull</b> — {@code view-range × 64} blocks: below the radius, the client
     *       receives the entity and declines to draw it.</li>
     * </ol>
     *
     * <p>Only belt 3 is ours to set, and it is the one an operator silently breaks by raising the
     * radius alone — the cull then bites INSIDE the radius, so people standing well within
     * earshot see nothing, or see edge bubbles flicker. That is precisely the failure the ruling
     * forbids, so a too-tight {@code view-range} is raised to the paired minimum with a warning,
     * in the same clamp-and-warn spirit as every other key here: a config mistake must never
     * quietly break rendering mid-event.
     *
     * <p>Widening the belt is close to free — only admitted viewers ever receive the entity, so
     * {@code view-range} decides <i>drawing</i>, never <i>delivery</i>. The {@value #CULL_BELT_FACTOR}
     * factor is what absorbs the ≤ 0.5 s admission-refresh lag (a sprinting player covers ~3
     * blocks in that window) plus client-side rounding.
     */
    static float pairCullBelt(double radiusBlocks, float viewRange, Consumer<String> warn) {
        double needed = radiusBlocks * CULL_BELT_FACTOR / VIEW_RANGE_BLOCK_BASE;
        if (viewRange >= needed - PAIRING_EPSILON) {
            return viewRange;
        }
        double raised = Math.min(VIEW_RANGE_MAX,
                Math.ceil(needed * 100.0 - PAIRING_EPSILON) / 100.0);
        if (raised < needed - PAIRING_EPSILON) {
            warn.accept("view-range cannot cover radius-blocks=" + radiusBlocks
                    + ": the cull belt caps at " + VIEW_RANGE_MAX + " ("
                    + (VIEW_RANGE_MAX * VIEW_RANGE_BLOCK_BASE)
                    + " blocks) - admitted viewers past that distance may not see bubbles at all");
        } else {
            warn.accept("view-range=" + viewRange + " culls at "
                    + (viewRange * VIEW_RANGE_BLOCK_BASE) + " blocks, inside the " + radiusBlocks
                    + "-block admission radius - raised to " + raised
                    + " so admitted viewers still see bubbles at the edge of the radius");
        }
        return (float) raised;
    }

    private static double clamp(double value, double min, double max, String key, Consumer<String> warn) {
        if (value < min || value > max) {
            double clamped = Math.max(min, Math.min(max, value));
            warn.accept(key + "=" + value + " out of range [" + min + ", " + max + "] - clamped to " + clamped);
            return clamped;
        }
        return value;
    }

    public double radiusSquared() {
        return radiusBlocks * radiusBlocks;
    }

    public long lifetimeNanos() {
        return lifetimeSeconds * 1_000_000_000L;
    }

    public long lifetimeTicks() {
        return lifetimeSeconds * 20L;
    }
}
