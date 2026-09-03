package cowboyshootout;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Serializes a {@link ShootoutResult} to a pretty-printed, UTF-8 JSON file
 * and computes a SHA-256 checksum over the exact bytes written.
 * <p>
 * The JSON is built by hand instead of pulling in a third-party library
 * (Gson/Jackson/org.json): the document shape is fixed and fully controlled
 * by this program (no untrusted free-text besides generated cowboy names),
 * so a ~40-line writer with correct escaping is enough to guarantee valid,
 * dependency-free JSON that still passes strict validators like jsonlint.
 */
final class ProtocolWriter {

    private ProtocolWriter() {
    }

    static Path writeProtocol(ShootoutResult result, Path targetFile) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");

        json.append("  \"metadata\": {\n");
        json.append("    \"generatedAt\": ").append(quote(Instant.now().toString())).append(",\n");
        json.append("    \"numberOfCowboys\": ").append(result.cowboyCount()).append(",\n");
        json.append("    \"startingHealthPoints\": ").append(result.startingHp()).append(",\n");
        json.append("    \"minDamagePerShot\": ").append(ShootoutSimulator.MIN_DAMAGE).append(",\n");
        json.append("    \"maxDamagePerShot\": ").append(ShootoutSimulator.MAX_DAMAGE).append(",\n");
        json.append("    \"randomSeed\": ").append(result.randomSeed()).append(",\n");
        json.append("    \"startingCowboy\": ").append(quote(result.startingCowboy())).append("\n");
        json.append("  },\n");

        json.append("  \"initialSeatingOrder\": [\n");
        appendStringArray(json, result.initialSeatingOrder(), "    ");
        json.append("  ],\n");

        json.append("  \"shots\": [\n");
        List<ShotRecord> shots = result.shots();
        for (int i = 0; i < shots.size(); i++) {
            ShotRecord s = shots.get(i);
            json.append("    {\n");
            json.append("      \"shotNumber\": ").append(s.shotNumber()).append(",\n");
            json.append("      \"shooter\": ").append(quote(s.shooter())).append(",\n");
            json.append("      \"shooterHp\": ").append(s.shooterHp()).append(",\n");
            json.append("      \"direction\": ").append(quote(s.direction().name())).append(",\n");
            json.append("      \"target\": ").append(quote(s.target())).append(",\n");
            json.append("      \"damage\": ").append(s.damage()).append(",\n");
            json.append("      \"targetHpBefore\": ").append(s.targetHpBefore()).append(",\n");
            json.append("      \"targetHpAfter\": ").append(s.targetHpAfter()).append(",\n");
            json.append("      \"targetKilled\": ").append(s.targetKilled()).append("\n");
            json.append("    }").append(i < shots.size() - 1 ? ",\n" : "\n");
        }
        json.append("  ],\n");

        json.append("  \"result\": {\n");
        json.append("    \"totalShots\": ").append(shots.size()).append(",\n");
        json.append("    \"winner\": ").append(quote(result.winner())).append(",\n");
        json.append("    \"winnerHpRemaining\": ").append(result.winnerHpRemaining()).append("\n");
        json.append("  }\n");

        json.append("}\n");

        try {
            Files.writeString(targetFile, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write protocol file: " + targetFile, e);
        }
        return targetFile;
    }

    private static void appendStringArray(StringBuilder json, List<String> values, String indent) {
        for (int i = 0; i < values.size(); i++) {
            json.append(indent).append(quote(values.get(i)));
            json.append(i < values.size() - 1 ? ",\n" : "\n");
        }
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** SHA-256 checksum of a file's exact byte content, as a lowercase hex string. */
    static String sha256Hex(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(file));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available on every standard Java platform
            throw new AssertionError("SHA-256 not available", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read protocol file for checksum: " + file, e);
        }
    }
}
