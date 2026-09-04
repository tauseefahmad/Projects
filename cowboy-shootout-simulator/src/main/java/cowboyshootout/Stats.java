package cowboyshootout;

import java.security.SecureRandom;

// not needed for the actual assignment, just here to check whether the
// game is fair: runs a lot of games and counts wins by seat compared to
// the cowboy who started.
// Program arguments: <numberOfCowboys> [numberOfGames]
public class Stats {

    static final int START_HP = 10;

    public static void main(String[] args) {
        int cowboys = args.length >= 1 ? Integer.parseInt(args[0].trim()) : 8;
        int games = args.length >= 2 ? Integer.parseInt(args[1].trim()) : 200_000;

        Game game = new Game(cowboys, START_HP);
        SecureRandom seedGen = new SecureRandom();

        long[] winsBySeat = new long[cowboys]; // 0 = the starter, 1 = starter's right neighbor, ...
        int sample = Math.min(games, 20_000);
        long shotsInSample = 0;
        long firstShotRight = 0;

        for (int i = 0; i < games; i++) {
            boolean log = i < sample;
            Game.Result r = game.play(seedGen.nextLong(), log);

            int winnerSeat = Integer.parseInt(r.winner.substring("Cowboy-".length()));
            int starterSeat = Integer.parseInt(r.starter.substring("Cowboy-".length()));
            winsBySeat[Math.floorMod(winnerSeat - starterSeat, cowboys)]++;

            if (log) {
                shotsInSample += r.shots.size();
                if (!r.shots.isEmpty() && r.shots.get(0).side == Game.Side.RIGHT) firstShotRight++;
            }
        }

        System.out.println(cowboys + " cowboys, " + games + " games (" + sample + " sampled for extra stats)");
        System.out.println();
        System.out.printf("first shot went RIGHT: %d / %d (%.1f%%)%n", firstShotRight, sample, 100.0 * firstShotRight / sample);
        System.out.printf("average shots per game: %.1f%n", (double) shotsInSample / sample);
        System.out.println();
        System.out.println("win rate by seat, counted from the starter (seat 0 = starter, seat 1 = starter's right neighbor):");
        double fairShare = 100.0 / cowboys;
        for (int seat = 0; seat < cowboys; seat++) {
            double pct = 100.0 * winsBySeat[seat] / games;
            System.out.printf("  seat %-2d: %5.1f%%   (fair share: %.1f%%)%n", seat, pct, fairShare);
        }
    }
}
