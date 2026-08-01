package fr.mybios.onevs100.proxchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Config parsing against in-memory sections (MemoryConfiguration is pure Bukkit API — no server,
 * no YAML parser needed; the YAML file itself is deserialized by Bukkit's own loader at runtime).
 */
class ProxChatConfigTest {

    private final List<String> warnings = new ArrayList<>();

    @Test
    void emptyConfigYieldsDefaultsWithAWarning() {
        ProxChatConfig cfg = ProxChatConfig.from(new MemoryConfiguration(), warnings::add);
        assertEquals(ProxChatConfig.DEFAULTS, cfg);
        assertEquals(1, warnings.size()); // missing section is worth telling the console about
    }

    @Test
    void defaultsMatchTheApprovedSpec() {
        // Owner ruling 2026-08-01 ("if you can see a player you can read them"): radius 32 with
        // the paired 0.6 cull belt. height-above-head is the live-tuned ride-anchor 0.3 — what
        // config.yml has shipped since 0.4.0, and now what a config MISSING the key falls to.
        ProxChatConfig d = ProxChatConfig.DEFAULTS;
        assertEquals(32.0, d.radiusBlocks());
        assertEquals(8, d.lifetimeSeconds());
        assertEquals(3, d.maxPerPlayer());
        assertEquals(0.3, d.heightAboveHead());
        assertEquals(0.30, d.stackSpacing());
        assertTrue(d.hideOnSneak());
        assertEquals(96, d.maxMessageLength());
        assertEquals(200, d.lineWidth());
        assertEquals(0.6f, d.viewRange());
        assertEquals(750, d.minMessageIntervalMs());
        // Conversation log ships OFF, keep-forever, admits recorded.
        assertFalse(d.conversationLogEnabled());
        assertEquals(0, d.conversationRetentionDays());
        assertTrue(d.conversationLogAdmits());
    }

    @Test
    void explicitValuesParse() {
        MemoryConfiguration root = new MemoryConfiguration();
        var s = root.createSection("bubbles");
        s.set("radius-blocks", 32.0);
        s.set("lifetime-seconds", 10);
        s.set("max-per-player", 2);
        s.set("height-above-head", 1.4);
        s.set("stack-spacing", 0.25);
        s.set("hide-on-sneak", false);
        s.set("max-message-length", 120);
        s.set("line-width", 180);
        s.set("view-range", 0.6);
        s.set("min-message-interval-ms", 1000);
        var log = root.createSection("conversation-log");
        log.set("enabled", true);
        log.set("retention-days", 45);
        log.set("log-admits", false);
        ProxChatConfig cfg = ProxChatConfig.from(root, warnings::add);
        assertEquals(new ProxChatConfig(32.0, 10, 2, 1.4, 0.25, false, 120, 180, 0.6f, 1000,
                true, 45, false), cfg);
        assertTrue(warnings.isEmpty());
    }

    @Test
    void missingConversationLogSectionSplicesDefaultsSilently() {
        // A deployed config predating the feature must parse warning-free with the log OFF.
        MemoryConfiguration root = new MemoryConfiguration();
        root.createSection("bubbles");
        ProxChatConfig cfg = ProxChatConfig.from(root, warnings::add);
        assertFalse(cfg.conversationLogEnabled());
        assertEquals(0, cfg.conversationRetentionDays());
        assertTrue(cfg.conversationLogAdmits());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void negativeRetentionClampsToKeepForever() {
        MemoryConfiguration root = new MemoryConfiguration();
        root.createSection("bubbles");
        root.createSection("conversation-log").set("retention-days", -7);
        ProxChatConfig cfg = ProxChatConfig.from(root, warnings::add);
        assertEquals(0, cfg.conversationRetentionDays()); // 0 = never prune
        assertEquals(1, warnings.size());
    }

    @Test
    void missingKeysFallBackPerKey() {
        // 20 is deliberately INSIDE the default cull belt (0.6 × 64 = 38.4) so this stays a pure
        // splice test — raising the radius past the belt is its own test below.
        MemoryConfiguration root = new MemoryConfiguration();
        root.createSection("bubbles").set("radius-blocks", 20.0);
        ProxChatConfig cfg = ProxChatConfig.from(root, warnings::add);
        assertEquals(20.0, cfg.radiusBlocks());
        assertEquals(8, cfg.lifetimeSeconds()); // untouched keys keep their defaults
        assertTrue(warnings.isEmpty());
    }

    // ---------------------------------------------------------------- cull-belt pairing

    @Test
    void theShippedDefaultsSatisfyTheirOwnPairingRule() {
        // The regression that matters most: nobody may change one of the pair and not the other.
        ProxChatConfig d = ProxChatConfig.DEFAULTS;
        double culledAt = d.viewRange() * ProxChatConfig.VIEW_RANGE_BLOCK_BASE;
        assertTrue(culledAt >= d.radiusBlocks() * ProxChatConfig.CULL_BELT_FACTOR,
                "default view-range culls at " + culledAt + " blocks, too tight for a "
                        + d.radiusBlocks() + "-block radius");
        // ...and parsing the defaults back must not "fix" them: no warning, no change.
        MemoryConfiguration root = new MemoryConfiguration();
        var s = root.createSection("bubbles");
        s.set("radius-blocks", d.radiusBlocks());
        s.set("view-range", (double) d.viewRange());
        assertEquals(d.viewRange(), ProxChatConfig.from(root, warnings::add).viewRange());
        assertTrue(warnings.isEmpty(), () -> "defaults tripped their own pairing: " + warnings);
    }

    @Test
    void raisingTheRadiusAloneRaisesTheCullBeltAndSaysSo() {
        // The real migration hazard: an operator bumps the radius and leaves view-range behind,
        // so the client stops DRAWING bubbles well inside a radius the server still admits.
        MemoryConfiguration root = new MemoryConfiguration();
        var s = root.createSection("bubbles");
        s.set("radius-blocks", 48.0);
        s.set("view-range", 0.5);
        ProxChatConfig cfg = ProxChatConfig.from(root, warnings::add);
        assertEquals(48.0, cfg.radiusBlocks());
        assertEquals(0.9f, cfg.viewRange()); // 48 × 1.2 / 64
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("view-range"), warnings.get(0));
        assertTrue(warnings.get(0).contains("raised to"), warnings.get(0));
    }

    @Test
    void aGenerousCullBeltIsLeftExactlyAsConfigured() {
        // Widening costs nothing (only admitted viewers ever receive the entity), so an operator
        // who deliberately over-provisions must not be second-guessed.
        MemoryConfiguration root = new MemoryConfiguration();
        var s = root.createSection("bubbles");
        s.set("radius-blocks", 32.0);
        s.set("view-range", 1.5);
        ProxChatConfig cfg = ProxChatConfig.from(root, warnings::add);
        assertEquals(1.5f, cfg.viewRange());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void anUnreachablePairingCapsAndWarnsDistinctly() {
        // At the maximum radius the belt simply cannot cover it (128 × 1.2 / 64 = 2.4 > 2.0).
        // Cap, and say plainly that some admitted viewers may see nothing — never pretend.
        MemoryConfiguration root = new MemoryConfiguration();
        root.createSection("bubbles").set("radius-blocks", 128.0);
        ProxChatConfig cfg = ProxChatConfig.from(root, warnings::add);
        assertEquals((float) ProxChatConfig.VIEW_RANGE_MAX, cfg.viewRange());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("cannot cover"), warnings.get(0));
    }

    @Test
    void pairingNeverLowersAnAlreadySufficientBelt() {
        // Property sweep over the whole legal radius range: the result always clears the radius
        // when reachable, and is never quietly reduced below what the operator asked for.
        for (double radius = 1.0; radius <= 128.0; radius += 0.5) {
            for (float configured : new float[] {0.05f, 0.5f, 0.6f, 1.0f, 2.0f}) {
                warnings.clear();
                float paired = ProxChatConfig.pairCullBelt(radius, configured, warnings::add);
                assertTrue(paired >= configured,
                        "lowered " + configured + " to " + paired + " at radius " + radius);
                boolean reachable = radius * ProxChatConfig.CULL_BELT_FACTOR
                        <= ProxChatConfig.VIEW_RANGE_MAX * ProxChatConfig.VIEW_RANGE_BLOCK_BASE;
                if (reachable) {
                    // Tolerance is in BLOCKS, deliberately. view-range is stored as a float, so a
                    // clean 0.9 lands ~1.5e-6 blocks under the ideal belt. The pairing is a
                    // physical margin, not an exact real — and keeping the stored values round
                    // (0.6, 0.9) matters more, because they are what operators read in warnings
                    // and write in config.yml.
                    assertTrue(paired * ProxChatConfig.VIEW_RANGE_BLOCK_BASE
                                    >= radius * ProxChatConfig.CULL_BELT_FACTOR - 1e-3,
                            "belt " + paired + " too tight for radius " + radius);
                }
                assertTrue(warnings.size() <= 1, "at most one pairing warning per parse");
            }
        }
    }

    @Test
    void outOfRangeValuesClampWithWarnings() {
        MemoryConfiguration root = new MemoryConfiguration();
        var s = root.createSection("bubbles");
        s.set("radius-blocks", -5.0);          // typo must not brick the enable mid-event
        s.set("lifetime-seconds", 100000);
        s.set("max-per-player", 0);
        ProxChatConfig cfg = ProxChatConfig.from(root, warnings::add);
        assertEquals(1.0, cfg.radiusBlocks());
        assertEquals(120, cfg.lifetimeSeconds());
        assertEquals(1, cfg.maxPerPlayer());
        assertEquals(3, warnings.size());
        assertFalse(warnings.get(0).isEmpty());
    }

    @Test
    void derivedValuesAreConsistent() {
        ProxChatConfig d = ProxChatConfig.DEFAULTS;
        assertEquals(1024.0, d.radiusSquared()); // 32²
        assertEquals(8_000_000_000L, d.lifetimeNanos());
        assertEquals(160L, d.lifetimeTicks());
    }
}
