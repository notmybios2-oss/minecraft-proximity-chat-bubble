package fr.mybios.onevs100.proxchat.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import fr.mybios.onevs100.proxchat.text.SanitizeResult.Verdict;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The formatting grammar attacked in FULL, not one character at a time.
 *
 * <p>Two mechanisms could put styling on a bubble, and they fail for different reasons:
 * <ul>
 *   <li><b>Legacy §-codes</b> — colour, style, and the 1.16+ hex form {@code §x§R§R§G§G§B§B} —
 *       are honoured by client render paths straight out of raw display text. These are killed
 *       at the source: no U+00A7 survives sanitization, so the grammar has no lead-in left and
 *       every code letter is demoted to ordinary content.</li>
 *   <li><b>MiniMessage / Adventure tags</b> ({@code <red>}, {@code <gradient:…>}) are killed by
 *       never being parsed: the bubble is built with {@code Component.text(literal)}. So the
 *       test here is the opposite of stripping — the tags must come through byte-for-byte, which
 *       is the observable signature of "nothing deserialized this".</li>
 * </ul>
 *
 * <p>Live context (T17, 2026-08-01): the current vanilla client refuses § in the chat box
 * entirely, so this whole surface is now reachable only from modified clients — which is exactly
 * why it is pinned here in machine-checked form rather than left to an eyeball leg.
 */
class MessageSanitizerFormattingGrammarTest {

    private static final int CAP = 96;
    private static final char SECTION = '§';

    /** Every legacy code letter: 0-9 and a-f colours, k-o styles, r reset. */
    private static final String LEGACY_CODES = "0123456789abcdefklmnor";

    private static SanitizeResult run(String raw) {
        return MessageSanitizer.sanitize(raw, CAP);
    }

    // ---------------------------------------------------------------- legacy grammar

    @Test
    void everyLegacyCodeLosesItsLeadInAndKeepsItsLetter() {
        for (char code : LEGACY_CODES.toCharArray()) {
            SanitizeResult r = run(SECTION + "" + code + "bonjour");
            assertEquals(Verdict.OK, r.verdict());
            // The letter is what the abuser typed; it stays, as content, formatting-dead.
            assertEquals(code + "bonjour", r.text(), "code " + code);
            assertFalse(r.text().indexOf(SECTION) >= 0, "lead-in survived for " + code);
        }
    }

    @Test
    void uppercaseCodesAreDefusedToo() {
        // Clients accept §C as readily as §c; the strip is case-blind because it removes the
        // lead-in, not the letter — there is no letter table to keep in sync.
        assertEquals("CROUGE", run(SECTION + "CROUGE").text());
        assertEquals("Lgras", run(SECTION + "Lgras").text());
    }

    @Test
    void theSpigotHexFormCollapsesToPlainText() {
        // §x§F§F§5§5§5§5 is how a hex colour reaches a 1.16+ client: one lead-in per nibble.
        // Remove them all and what is left is the literal string xFF5555 — text, not a colour.
        String hex = SECTION + "x" + SECTION + "F" + SECTION + "F" + SECTION + "5"
                + SECTION + "5" + SECTION + "5" + SECTION + "5" + "rouge";
        assertEquals("xFF5555rouge", run(hex).text());
    }

    @Test
    void aLegacyGradientIsFlattenedToItsLetters() {
        // A "gradient" in legacy grammar is just one colour code per character. Every lead-in
        // dies, so the effect is interleaved letters: ugly, and completely inert.
        StringBuilder sb = new StringBuilder();
        String word = "gradient";
        for (int i = 0; i < word.length(); i++) {
            sb.append(SECTION).append(LEGACY_CODES.charAt(i)).append(word.charAt(i));
        }
        SanitizeResult r = run(sb.toString());
        assertEquals("0g1r2a3d4i5e6n7t", r.text());
        assertFalse(r.text().indexOf(SECTION) >= 0);
    }

    @Test
    void aWallOfLeadInsWithNoLettersRendersNothingAtAll() {
        assertEquals(Verdict.EMPTY, run(String.valueOf(SECTION).repeat(64)).verdict());
    }

    @Test
    void anOverlongMessageOfLeadInsIsNotRescuedIntoTheCap() {
        // 200 lead-ins + 96 letters: the cap prices what RENDERS, so this is exactly at cap and
        // must pass — the strip must not be mistaken for a truncation budget.
        String padded = String.valueOf(SECTION).repeat(200) + "a".repeat(96);
        assertEquals(Verdict.OK, run(padded).verdict());
        assertEquals("a".repeat(96), run(padded).text());
        assertEquals(Verdict.TOO_LONG, run(padded + "a").verdict());
    }

    // ---------------------------------------------------------------- tag grammars

    @Test
    void miniMessageTagsSurviveLiterallyBecauseNothingParsesThem() {
        // Byte-for-byte passthrough IS the assertion: if any of these ever came back altered or
        // rendered, something downstream started deserializing user text as markup.
        String[] tags = {"<red>", "</red>", "<#ff5555>", "<bold>", "<obf>", "<reset>",
                "<gradient:#ff0000:#0000ff>", "<rainbow>", "<transition:#fff:#000:0.5>",
                "<click:run_command:'/op me'>", "<hover:show_text:'x'>", "<insert:x>",
                "<lang:block.minecraft.stone>", "<score:me:obj>", "<key:key.jump>"};
        for (String tag : tags) {
            assertEquals(tag + " ok", run(tag + " ok").text(), "tag was altered: " + tag);
        }
    }

    @Test
    void aLiteralNewlineTagNeverBecomesALineBreak() {
        SanitizeResult r = run("un<newline>deux<br>trois");
        assertEquals("un<newline>deux<br>trois", r.text());
        assertFalse(r.text().contains("\n"));
    }

    @Test
    void ampersandCodesArePreservedBecauseNothingTranslatesThem() {
        // Confirmed live 2026-08-01: &cHekki reached the record raw and rendered untranslated.
        // Preserving them is correct — & is ordinary punctuation and translating it would be us
        // INVENTING a formatting grammar the client never had.
        assertEquals("&cHekki", run("&cHekki").text());
        assertEquals("&&l&r", run("&&l&r").text());
        assertEquals("&#FF5555rouge", run("&#FF5555rouge").text());
    }

    // ---------------------------------------------------------------- the crown property

    @Test
    void noSectionSignEverSurvivesAnyInput() {
        // The whole legacy grammar — colour, style, hex, gradient — is unreachable if and only
        // if this holds for every input. Seeded, so a failure is reproducible.
        Random rng = new Random(0xC0FFEE);
        int[] alphabet = {SECTION, 'x', 'a', '0', 'F', '5', 'k', 'l', 'r', '&', '<', '>', '#',
                ':', '/', ' ', 0x00E9, 0x1F600, 0x200B, 0x202E, 0xE0041};
        for (int round = 0; round < 5000; round++) {
            StringBuilder sb = new StringBuilder();
            int len = rng.nextInt(120);
            for (int i = 0; i < len; i++) {
                sb.appendCodePoint(alphabet[rng.nextInt(alphabet.length)]);
            }
            String out = MessageSanitizer.sanitize(sb.toString(), CAP).text();
            assertFalse(out.indexOf(SECTION) >= 0, () -> "§ survived: " + describe(out));
        }
    }

    private static String describe(String s) {
        StringBuilder sb = new StringBuilder();
        s.codePoints().forEach(cp -> sb.append("U+").append(Integer.toHexString(cp)).append(' '));
        return sb.toString();
    }
}
