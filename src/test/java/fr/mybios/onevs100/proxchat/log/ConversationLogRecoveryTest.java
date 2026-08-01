package fr.mybios.onevs100.proxchat.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the day file looks like after something went wrong — a restart, a kill -9, a power cut,
 * or an I/O error that aborted a write halfway.
 *
 * <p>This matters more than ordinary writer mechanics because the file is an interface: a future
 * relay lane tails it line by line. A consumer can skip one damaged line; what it cannot do is
 * notice that the record it just parsed was actually the tail of a truncated line with the next
 * record glued onto it. So the contract these tests hold is narrow and absolute — <b>an
 * interruption may cost the line it interrupted, and nothing else.</b>
 */
class ConversationLogRecoveryTest {

    private static final Logger LOG = Logger.getLogger("ConversationLogRecoveryTest");
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final long TS = 1784659263512L; // 2026-07-21T20:41:03.512+02:00
    private static final String DAY_FILE = "bubbles-2026-07-21.jsonl";

    @TempDir
    Path dir;

    private ConversationLog openLog() {
        return new ConversationLog(dir, () -> 0, LOG,
                Clock.fixed(Instant.ofEpochMilli(TS), PARIS), 64, true);
    }

    private void awaitLines(int lines) throws Exception {
        Path file = dir.resolve(DAY_FILE);
        for (int i = 0; i < 200; i++) {
            if (Files.exists(file) && Files.readAllLines(file).size() >= lines) {
                return;
            }
            Thread.sleep(10);
        }
    }

    private List<String> dayFile() throws Exception {
        return Files.readAllLines(dir.resolve(DAY_FILE));
    }

    // ---------------------------------------------------------------- restart

    @Test
    void aRestartAppendsToTheSameDayFileAndLosesNothing() throws Exception {
        try (ConversationLog first = openLog()) {
            first.submit(TS, "{\"n\":1}");
            awaitLines(1);
        }
        try (ConversationLog second = openLog()) { // the server came back up the same day
            second.submit(TS, "{\"n\":2}");
            awaitLines(2);
        }
        assertEquals(List.of("{\"n\":1}", "{\"n\":2}"), dayFile());
    }

    @Test
    void anIntactFileGainsNoBlankLineWhenItIsReopened() throws Exception {
        // The over-correction guard: a healthy file must not collect an empty line per restart.
        // Blank lines in a JSONL are noise a consumer has to special-case, and they would appear
        // on every rollover and after every recovered I/O error — far more often than real tears.
        for (int restart = 0; restart < 3; restart++) {
            try (ConversationLog log = openLog()) {
                log.submit(TS, "{\"restart\":" + restart + "}");
                awaitLines(restart + 1);
            }
        }
        List<String> lines = dayFile();
        assertEquals(3, lines.size());
        lines.forEach(line -> assertFalse(line.isEmpty(), "blank line crept into the day file"));
    }

    // ---------------------------------------------------------------- torn lines

    @Test
    void aFileLeftMidLineIsTerminatedSoTheNextRecordStaysParseable() throws Exception {
        // Exactly what a kill -9 during a flush leaves behind: a complete line, then a fragment
        // with no newline. Appending straight onto that fragment would glue the next record to
        // it and cost two lines instead of one.
        Files.writeString(dir.resolve(DAY_FILE),
                "{\"type\":\"msg\",\"n\":1}\n{\"type\":\"msg\",\"trunc");
        try (ConversationLog log = openLog()) {
            log.submit(TS, "{\"type\":\"msg\",\"n\":2}");
            awaitLines(3);
        }
        List<String> lines = dayFile();
        assertEquals(3, lines.size());
        assertEquals("{\"type\":\"msg\",\"n\":1}", lines.get(0));
        assertEquals("{\"type\":\"msg\",\"trunc", lines.get(1)); // the damage, still one line
        assertEquals("{\"type\":\"msg\",\"n\":2}", lines.get(2)); // intact, on its own line

        // The contract, stated the way a consumer experiences it: exactly one line is bad.
        long unparseable = lines.stream().filter(line -> !parses(line)).count();
        assertEquals(1, unparseable);
    }

    @Test
    void aFileThatIsNothingButAFragmentStillRecovers() throws Exception {
        // The crash landed on the very first line of the day.
        Files.writeString(dir.resolve(DAY_FILE), "{\"type\":\"ms");
        try (ConversationLog log = openLog()) {
            log.submit(TS, "{\"type\":\"msg\",\"n\":1}");
            awaitLines(2);
        }
        List<String> lines = dayFile();
        assertEquals(2, lines.size());
        assertTrue(parses(lines.get(1)));
    }

    @Test
    void anEmptyDayFileIsNotMistakenForATornOne() throws Exception {
        // Zero bytes is what CREATE leaves behind if the process died before the first flush.
        Files.createFile(dir.resolve(DAY_FILE));
        try (ConversationLog log = openLog()) {
            log.submit(TS, "{\"n\":1}");
            awaitLines(1);
        }
        assertEquals(List.of("{\"n\":1}"), dayFile());
    }

    @Test
    void aTornFileIsOnlyRepairedOnceNoMatterHowOftenItIsReopened() throws Exception {
        Files.writeString(dir.resolve(DAY_FILE), "{\"trunc");
        for (int restart = 0; restart < 3; restart++) {
            try (ConversationLog log = openLog()) {
                log.submit(TS, "{\"n\":" + restart + "}");
                awaitLines(restart + 2);
            }
        }
        List<String> lines = dayFile();
        assertEquals(4, lines.size()); // the fragment + three records, no accumulating blanks
        assertEquals(1, lines.stream().filter(line -> !parses(line)).count());
    }

    private static boolean parses(String line) {
        try {
            return JsonParser.parseString(line).isJsonObject();
        } catch (RuntimeException notJson) {
            return false;
        }
    }
}
