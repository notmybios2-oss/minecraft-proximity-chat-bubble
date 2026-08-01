package fr.mybios.onevs100.proxchat.text;

import java.text.Normalizer;

/**
 * Turns untrusted chat input into a plain, single-line, bounded string — or a verdict that no
 * bubble may render. Pure static logic, no Bukkit types (unit-fuzzable).
 *
 * Pipeline: NFC-normalize → map every whitespace-class code point (including newlines: client
 * lineWidth wrapping is the only line-break authority) to a plain space → drop ISO controls and
 * everything {@link #isNeverContent} names (all of Unicode category {@code Cf}, lone surrogates,
 * and U+00A7 `§`) → collapse space runs and trim → NFC-normalize once more, because stripping an
 * invisible can reunite a base character with its combining mark → count CODE POINTS against the
 * cap. Over-cap input is REJECTED, never truncated (silent truncation misquotes the speaker).
 * The result is NFC by construction, which makes sanitization idempotent: the text the
 * conversation log records is exactly what a second pass would produce.
 *
 * The OK text is only ever rendered via {@code Component.text(literal)} — never deserialized as
 * MiniMessage/legacy markup (the known abuse vector). French accents pass untouched (NFC; the
 * build pins UTF-8). Stripping U+200D (ZWJ) intentionally degrades composite emoji into their
 * components: a zero-width joiner is also a steganography channel, so it does not survive.
 */
public final class MessageSanitizer {

    private MessageSanitizer() {
    }

    public static SanitizeResult sanitize(String raw, int maxCodePoints) {
        if (raw == null || raw.isEmpty()) {
            return SanitizeResult.empty();
        }
        String nfc = Normalizer.normalize(raw, Normalizer.Form.NFC);
        StringBuilder out = new StringBuilder(nfc.length());
        boolean pendingSpace = false;
        int i = 0;
        while (i < nfc.length()) {
            int cp = nfc.codePointAt(i);
            i += Character.charCount(cp);
            // Whitespace first: \t\n\r\f are ISO controls too, but they separate words, so they
            // must become spaces rather than vanish ("line1\nline2" must not read "line1line2").
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                pendingSpace = out.length() > 0; // leading whitespace dies here, runs collapse
                continue;
            }
            if (Character.isISOControl(cp) || isNeverContent(cp)) {
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.appendCodePoint(cp);
        }
        if (out.isEmpty()) {
            return SanitizeResult.empty();
        }
        // Normalize AGAIN, because stripping changes what is normalizable. "a<ZWSP>◌́" arrives
        // already NFC — the invisible sits between the base and its combining mark and keeps
        // them apart — and removing it leaves "a◌́", which is decomposed. Without this second
        // pass the output is not NFC and sanitize() is not idempotent: re-sanitizing the text
        // yields different bytes for the same rendered glyphs, so the conversation log's "text
        // exactly as it rendered" would not survive a round trip through its own reader.
        // Composition can only merge a base with its marks, so it can never resurrect anything
        // the strip above just removed.
        String text = Normalizer.normalize(out.toString(), Normalizer.Form.NFC);
        if (text.codePointCount(0, text.length()) > maxCodePoints) {
            return SanitizeResult.tooLong();
        }
        return SanitizeResult.ok(text);
    }

    /**
     * Code points that can never be legitimate message content.
     *
     * <p>This used to be a hand-written list (U+200B–200F, U+202A–202E, U+2060–2064, U+FEFF,
     * U+00AD). Every entry on that list was in Unicode category {@code Cf} FORMAT — it was an
     * incomplete enumeration of a category, and it missed 153 other {@code Cf} code points,
     * including the whole TAG block (U+E0001, U+E0020–E007F): invisible, arbitrary-payload, and
     * the textbook steganography channel to smuggle through an anonymous chat. Naming the
     * category instead closes those and every {@code Cf} a future Unicode release adds.
     *
     * <p>{@code Cs} SURROGATE covers lone halves of a surrogate pair, which are never text —
     * they survive into the conversation-log JSONL as replacement characters and are a fidelity
     * hazard for anything that parses it. A well-formed pair is a single code point here and is
     * unaffected.
     *
     * <p>U+00A7 {@code §} is category {@code Po}, so it is named explicitly: it is the legacy
     * formatting lead-in that some client render paths honour even in raw display text, which is
     * what makes every legacy code — colour, bold, obfuscate, and the hex form
     * {@code §x§R§R§G§G§B§B} — inert once it is gone.
     *
     * <p>Deliberately NOT stripped, with reasons, because each has legitimate uses: variation
     * selectors ({@code Mn} — emoji presentation), Hangul fillers and the braille blank (real
     * letters and symbols that merely render blank), and the private-use area ({@code Co} —
     * resource-pack glyphs, which some servers rely on).
     */
    private static boolean isNeverContent(int cp) {
        int type = Character.getType(cp);
        return type == Character.FORMAT
                || type == Character.SURROGATE
                || cp == 0x00A7;
    }
}
