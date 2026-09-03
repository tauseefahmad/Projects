package cowboyshootout;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Entry point. Run configuration in IntelliJ:
 *   Program arguments: &lt;numberOfCowboys&gt; [randomSeed]
 * <p>
 * Example: {@code 8}                 -> simulate 8 cowboys, random seed
 *          {@code 8 42}              -> simulate 8 cowboys, fixed seed 42 (reproducible run)
 */
public final class Main {

    private static final int STARTING_HP = 10;

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java cowboyshootout.Main <numberOfCowboys> [randomSeed]");
            System.exit(1);
            return;
        }

        int cowboyCount;
        try {
            cowboyCount = Integer.parseInt(args[0].trim());
        } catch (NumberFormatException e) {
            System.err.println("Invalid number of cowboys: '" + args[0] + "' is not an integer.");
            System.exit(1);
            return;
        }
        if (cowboyCount < 1) {
            System.err.println("Number of cowboys must be at least 1.");
            System.exit(1);
            return;
        }

        long seed = args.length >= 2
                ? Long.parseLong(args[1].trim())
                : new SecureRandom().nextLong();

        System.out.println("=== Wild West Shootout Simulator ===");
        System.out.println("Cowboys: " + cowboyCount + " | Starting HP: " + STARTING_HP + " | Random seed: " + seed);
        System.out.println();

        ShootoutSimulator simulator = new ShootoutSimulator(cowboyCount, STARTING_HP);
        ShootoutResult result = simulator.run(seed, true);

        printProtocolToConsole(result);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path protocolFile = Path.of("protocol_" + cowboyCount + "cowboys_" + timestamp + ".json");
        ProtocolWriter.writeProtocol(result, protocolFile);
        String checksum = ProtocolWriter.sha256Hex(protocolFile);

        Path checksumFile = Path.of(protocolFile + ".sha256");
        try {
            java.nio.file.Files.writeString(checksumFile, checksum + "  " + protocolFile.getFileName() + "\n");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }

        System.out.println();
        System.out.println("Winner: " + result.winner() + " (" + result.winnerHpRemaining() + " HP remaining)");
        System.out.println("Total shots fired: " + result.shots().size());
        System.out.println();
        System.out.println("Protocol written to : " + protocolFile.toAbsolutePath());
        System.out.println("SHA-256 checksum     : " + checksum);
        System.out.println("Checksum file        : " + checksumFile.toAbsolutePath());
    }

    private static void printProtocolToConsole(ShootoutResult result) {
        List<String> order = result.initialSeatingOrder();
        System.out.println("Seating order: " + String.join(" - ", order) + " (circle)");
        System.out.println("First to draw: " + result.startingCowboy());
        System.out.println();

        for (ShotRecord s : result.shots()) {
            StringBuilder line = new StringBuilder();
            line.append(String.format("Shot #%-3d ", s.shotNumber()));
            line.append(s.shooter()).append(" (").append(s.shooterHp()).append(" HP) shoots ").append(s.direction());
            line.append(" -> hits ").append(s.target());
            line.append(" for ").append(s.damage()).append(" dmg");
            line.append(" (HP ").append(s.targetHpBefore()).append(" -> ").append(s.targetHpAfter()).append(")");
            if (s.targetKilled()) {
                line.append("  [ELIMINATED - circle closes]");
            }
            System.out.println(line);
        }
    }
}
