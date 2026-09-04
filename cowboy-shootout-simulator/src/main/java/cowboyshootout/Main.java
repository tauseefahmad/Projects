package cowboyshootout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

// Program arguments: <numberOfCowboys> [seed]
// Example: 8        -> 8 cowboys, random seed
//          8 42      -> 8 cowboys, fixed seed (reproducible run)
public class Main {

    static final int START_HP = 10;

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("usage: java cowboyshootout.Main <numberOfCowboys> [seed]");
            return;
        }

        int cowboys = Integer.parseInt(args[0].trim());
        long seed = args.length >= 2 ? Long.parseLong(args[1].trim()) : new SecureRandom().nextLong();

        System.out.println("Wild West Shootout - " + cowboys + " cowboys, " + START_HP + " hp each, seed " + seed);
        System.out.println();

        Game game = new Game(cowboys, START_HP);
        Game.Result result = game.play(seed, true);

        tellStory(result);

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path file = Path.of("protocol_" + cowboys + "cowboys_" + stamp + ".json");
        Protocol.write(result, file);
        String hash = Protocol.sha256(file);
        Files.writeString(Path.of(file + ".sha256"), hash + "  " + file.getFileName() + "\n");

        System.out.println();
        System.out.println(result.winner + " wins with " + result.winnerHp + " hp left! (" + result.shots.size() + " shots fired)");
        System.out.println();
        System.out.println("protocol file : " + file.toAbsolutePath());
        System.out.println("sha-256       : " + hash);
    }

    // print the fight as a little story instead of a bare table of numbers
    static void tellStory(Game.Result result) {
        System.out.println("The circle: " + String.join(" - ", result.order));
        System.out.println(result.starter + " draws first.");
        System.out.println();

        for (Game.Shot s : result.shots) {
            String turn = s.side == Game.Side.RIGHT ? "turns right" : "turns left";
            String line = s.num + ". " + s.shooter + " (" + s.shooterHp + " hp) " + turn
                    + " and fires at " + s.target + " -> " + s.dmg + " dmg, "
                    + s.target + " drops to " + s.hpAfter + " hp";
            if (s.killed) {
                line += ". " + s.target + " falls! The circle closes and " + s.shooter + " shoots again.";
            }
            System.out.println(line);
        }
    }
}
