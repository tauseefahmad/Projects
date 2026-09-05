package cowboyshootout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolTest {

    @TempDir
    Path tempDir;

    @Test
    void writtenFileIsUtf8AndEndsWithTheExpectedFields() throws IOException {
        Game.Result r = new Game(3, 10).play(5, true);
        Path file = tempDir.resolve("protocol.json");

        Protocol.write(r, file);
        String text = Files.readString(file, StandardCharsets.UTF_8);

        assertTrue(text.startsWith("{"));
        assertTrue(text.trim().endsWith("}"));
        assertTrue(text.contains("\"cowboys\": 3"));
        assertTrue(text.contains("\"winner\": \"" + r.winner + "\""));
        assertBalancedBracesAndBrackets(text);
    }

    @Test
    void checksumLooksLikeSha256() throws IOException {
        Game.Result r = new Game(3, 10).play(5, true);
        Path file = tempDir.resolve("protocol.json");
        Protocol.write(r, file);

        String hash = Protocol.sha256(file);

        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]+"));
    }

    @Test
    void differentContentProducesDifferentChecksum() throws IOException {
        Path file = tempDir.resolve("protocol.json");

        Protocol.write(new Game(3, 10).play(1, true), file);
        String hashA = Protocol.sha256(file);

        Protocol.write(new Game(3, 10).play(2, true), file);
        String hashB = Protocol.sha256(file);

        assertTrue(!hashA.equals(hashB));
    }

    // not a full JSON parser, just a quick sanity check that every
    // { has a matching } and every [ has a matching ]
    private static void assertBalancedBracesAndBrackets(String json) {
        int braces = 0;
        int brackets = 0;
        for (char c : json.toCharArray()) {
            if (c == '{') braces++;
            if (c == '}') braces--;
            if (c == '[') brackets++;
            if (c == ']') brackets--;
        }
        assertEquals(0, braces, "unbalanced { }");
        assertEquals(0, brackets, "unbalanced [ ]");
    }
}
