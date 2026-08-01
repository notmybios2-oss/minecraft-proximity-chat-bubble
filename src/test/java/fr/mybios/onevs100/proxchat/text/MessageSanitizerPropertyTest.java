package fr.mybios.onevs100.proxchat.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.mybios.onevs100.proxchat.text.SanitizeResult.Verdict;
import java.text.Normalizer;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Properties that must hold for EVERY input, rather than examples that hold for the inputs
 * someone thought of. The example-based coverage lives in {@link MessageSanitizerTest}; this
 * class states the invariants and then tries hard to break them.
 *
 * <p>Written during the anonymity sweep, which found the gap these pin: the invisible-character
 * strip used to be a hand-written list of code points, and every entry on it happened to be
 * Unicode category {@code Cf}. It was an incomplete enumeration of a category — it missed 153
 * other {@code Cf} code points, including the whole TAG block (U+E0001, U+E0020–E007F), which is
 * an invisible arbitrary-payload channel: perfect for smuggling a signature, a name, or a
 * coordinate through a chat whose entire promise is that messages carry no identity.
 */
class MessageSanitizerPropertyTest {

    private static final int CAP = 96;

    private static String cp(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    /**
     * Broad adversarial corpus: real text, the old hand-written strip list, code points the old
     * list missed, lone surrogate halves, astral planes, and exotic whitespace.
     */
    private static String fuzz(Random rng) {
        int[] alphabet = {
            'a', 'Z', '9', ' ', '!', 0x00E9, 0x00E7, 0x00A7, '&', '<', '>',
            0x0000, 0x0007, 0x001B, 0x007F, 0x0085, 0x009F,           // controls
            0x00A0, 0x2000, 0x3000, 0x000A, 0x000D, 0x0009,           // whitespace classes
            0x00AD, 0x200B, 0x200D, 0x200F, 0x202E, 0x2060, 0xFEFF,   // the old list
            0x061C, 0x180E, 0xFFF9, 0xFFFB, 0x110BD, 0x1D173,         // Cf the old list missed
            0xE0001, 0xE0020, 0xE0041, 0xE007F,                       // the TAG block
            0xD800, 0xDC00, 0xDBFF, 0xDFFF,                           // lone surrogate halves
            0xFE0F, 0x3164, 0x2800, 0xE000,                           // kept on purpose
            0x0301, 0x1F600, 0x1F9D1, 0x10FFFD,
        };
        StringBuilder sb = new StringBuilder();
        int len = rng.nextInt(200);
        for (int i = 0; i < len; i++) {
            int c = alphabet[rng.nextInt(alphabet.length)];
            if (Character.isSurrogate((char) c)) {
                sb.append((char) c); // deliberately unpaired — appendCodePoint would refuse
            } else {
                sb.appendCodePoint(c);
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- the invariants

    @Test
    void noFormatCategoryCodePointEverSurvives() {
        // The generalized rule that replaced the hand-written list. Cf is invisible by
        // definition, so it is never legitimate content and always a covert channel.
        Random rng = new Random(7);
        for (int round = 0; round < 4000; round++) {
            String out = MessageSanitizer.sanitize(fuzz(rng), CAP).text();
            out.codePoints().forEach(c -> assertFalse(Character.getType(c) == Character.FORMAT,
                    () -> "Cf survived: U+" + Integer.toHexString(c)));
        }
    }

    @Test
    void theTagBlockIsStripped() {
        // U+E0020–E007F mirror ASCII invisibly: "MyBios" written in tag characters renders as
        // nothing and reads back perfectly. Named explicitly because it is THE steganography
        // channel for an anonymous chat, and the old strip list let all of it through.
        StringBuilder hidden = new StringBuilder("salut");
        for (char c : "MyBios".toCharArray()) {
            hidden.appendCodePoint(0xE0000 + c);
        }
        assertEquals("salut", MessageSanitizer.sanitize(hidden.toString(), CAP).text());
        assertEquals(Verdict.EMPTY,
                MessageSanitizer.sanitize(cp(0xE0001) + cp(0xE007F), CAP).verdict());
    }

    @Test
    void loneSurrogatesAreStrippedAndRealPairsAreNot() {
        // A lone half is not text: it survives into the conversation-log JSONL as a replacement
        // character, so a consumer of that file sees something the speaker never typed.
        assertEquals("ab", MessageSanitizer.sanitize("a" + (char) 0xD800 + "b", CAP).text());
        assertEquals("ab", MessageSanitizer.sanitize("a" + (char) 0xDFFF + "b", CAP).text());
        String pair = cp(0x1F600); // a well-formed pair is ONE code point and must be untouched
        assertEquals(pair, MessageSanitizer.sanitize(pair, CAP).text());
    }

    @Test
    void sanitizationIsIdempotent() {
        // Anything that renders must survive a second pass unchanged, or "the text as rendered"
        // in the conversation log would not be the text that rendered.
        Random rng = new Random(11);
        for (int round = 0; round < 4000; round++) {
            SanitizeResult first = MessageSanitizer.sanitize(fuzz(rng), CAP);
            if (first.verdict() != Verdict.OK) {
                continue;
            }
            SanitizeResult second = MessageSanitizer.sanitize(first.text(), CAP);
            assertEquals(Verdict.OK, second.verdict());
            assertEquals(first.text(), second.text(), "not idempotent");
        }
    }

    @Test
    void outputIsAlwaysNfcNormalized() {
        Random rng = new Random(13);
        for (int round = 0; round < 2000; round++) {
            String out = MessageSanitizer.sanitize(fuzz(rng), CAP).text();
            assertTrue(Normalizer.isNormalized(out, Normalizer.Form.NFC),
                    () -> "not NFC: " + out);
        }
    }

    @Test
    void everyStructuralInvariantHoldsTogether() {
        // One pass, all of it at once: an OK verdict is non-empty, within the cap, trimmed,
        // single-spaced, single-line, and free of controls, Cf, and surrogates. A non-OK verdict
        // carries no text at all — rejected input must never travel.
        Random rng = new Random(2026);
        for (int round = 0; round < 6000; round++) {
            SanitizeResult r = MessageSanitizer.sanitize(fuzz(rng), CAP);
            if (r.verdict() != Verdict.OK) {
                assertEquals("", r.text());
                continue;
            }
            String t = r.text();
            assertFalse(t.isEmpty());
            assertTrue(t.codePointCount(0, t.length()) <= CAP);
            assertEquals(t, t.trim(), "not trimmed");
            assertFalse(t.contains("  "), "uncollapsed space run");
            assertFalse(t.contains("\n") || t.contains("\r"), "line break survived");
            t.codePoints().forEach(c -> {
                int type = Character.getType(c);
                assertFalse(Character.isISOControl(c),
                        () -> "control survived: U+" + Integer.toHexString(c));
                assertFalse(type == Character.FORMAT,
                        () -> "Cf survived: U+" + Integer.toHexString(c));
                assertFalse(type == Character.SURROGATE,
                        () -> "surrogate survived: U+" + Integer.toHexString(c));
                assertFalse(c == 0x00A7, "section sign survived");
                assertFalse(c != ' ' && (Character.isWhitespace(c) || Character.isSpaceChar(c)),
                        () -> "exotic whitespace survived: U+" + Integer.toHexString(c));
            });
        }
    }

    // ---------------------------------------------------------------- deliberate non-strips

    @Test
    void charactersKeptOnPurposeStayKept() {
        // Documented decisions, pinned so a future "strip more" sweep has to argue with a test:
        // variation selectors carry emoji presentation, Hangul fillers and the braille blank are
        // real letters and symbols that merely render blank, and the private-use area is where
        // resource packs put their glyphs. Each renders blank-ish and none carries a payload the
        // way Cf does.
        assertEquals("a" + cp(0xFE0F), MessageSanitizer.sanitize("a" + cp(0xFE0F), CAP).text());
        assertEquals(cp(0x3164), MessageSanitizer.sanitize(cp(0x3164), CAP).text());
        assertEquals(cp(0x2800), MessageSanitizer.sanitize(cp(0x2800), CAP).text());
        assertEquals(cp(0xE000), MessageSanitizer.sanitize(cp(0xE000), CAP).text());
    }
}
