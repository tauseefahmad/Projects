package cowboyshootout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

// writes the game to a JSON file and hashes it, so the sheriff has proof
// nobody edited the story afterward
class Protocol {

    static Path write(Game.Result r, Path file) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        out.append("  \"cowboys\": ").append(r.count).append(",\n");
        out.append("  \"startHp\": ").append(r.startHp).append(",\n");
        out.append("  \"seed\": ").append(r.seed).append(",\n");
        out.append("  \"order\": ").append(toJsonArray(r.order)).append(",\n");
        out.append("  \"starter\": ").append(quote(r.starter)).append(",\n");

        out.append("  \"shots\": [\n");
        for (int i = 0; i < r.shots.size(); i++) {
            Game.Shot s = r.shots.get(i);
            out.append("    {");
            out.append("\"num\":").append(s.num).append(",");
            out.append("\"shooter\":").append(quote(s.shooter)).append(",");
            out.append("\"shooterHp\":").append(s.shooterHp).append(",");
            out.append("\"side\":").append(quote(s.side.name())).append(",");
            out.append("\"target\":").append(quote(s.target)).append(",");
            out.append("\"dmg\":").append(s.dmg).append(",");
            out.append("\"hpBefore\":").append(s.hpBefore).append(",");
            out.append("\"hpAfter\":").append(s.hpAfter).append(",");
            out.append("\"killed\":").append(s.killed);
            out.append("}");
            out.append(i < r.shots.size() - 1 ? ",\n" : "\n");
        }
        out.append("  ],\n");

        out.append("  \"winner\": ").append(quote(r.winner)).append(",\n");
        out.append("  \"winnerHp\": ").append(r.winnerHp).append("\n");
        out.append("}\n");

        Files.writeString(file, out, StandardCharsets.UTF_8);
        return file;
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(file));
            return HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e); // SHA-256 always exists on the JVM
        }
    }

    private static String toJsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            sb.append(quote(values.get(i)));
            if (i < values.size() - 1) sb.append(", ");
        }
        return sb.append("]").toString();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }
}
