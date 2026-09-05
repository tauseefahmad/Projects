package cowboyshootout;

import java.security.SecureRandom;

// Not part of the actual assignment. This is a Monte Carlo check for
// whether the game is fair: it plays a large number of games and looks
// at whether a cowboy's seat, relative to that game's randomly chosen
// starter, affects how often they end up winning.
// Program arguments: <numberOfCowboys> [numberOfGames]
public class Stats {

    static final int START_HP = 10;
    static final int SHOT_SAMPLE_SIZE = 20_000;

    public static void main(String[] args) {
        int cowboyCount = args.length >= 1 ? Integer.parseInt(args[0].trim()) : 8;
        int gameCount = args.length >= 2 ? Integer.parseInt(args[1].trim()) : 200_000;

        Game game = new Game(cowboyCount, START_HP);
        SecureRandom seedGenerator = new SecureRandom();

        long[] winsBySeatOffset = tallyWinsBySeatOffset(game, cowboyCount, gameCount, seedGenerator);
        ShotSample shotSample = sampleShots(game, seedGenerator, Math.min(gameCount, SHOT_SAMPLE_SIZE));

        printReport(cowboyCount, gameCount, winsBySeatOffset, shotSample);
    }

    // Plays gameCount games with shot logging switched off (we only need
    // the winner from each one) and counts, for every seat offset from
    // that game's starter, how often the cowboy sitting there won.
    private static long[] tallyWinsBySeatOffset(Game game, int cowboyCount, int gameCount, SecureRandom seedGenerator) {
        long[] winsBySeatOffset = new long[cowboyCount];
        for (int i = 0; i < gameCount; i++) {
            Game.Result result = game.play(seedGenerator.nextLong(), false);
            winsBySeatOffset[seatOffsetFromStarter(result, cowboyCount)]++;
        }
        return winsBySeatOffset;
    }

    // A separate, smaller batch of games played with full shot logging on,
    // just to measure per-shot facts (first-shot direction, game length)
    // that don't need hundreds of thousands of trials to be conclusive.
    private static ShotSample sampleShots(Game game, SecureRandom seedGenerator, int sampleSize) {
        long totalShots = 0;
        long firstShotWentRight = 0;

        for (int i = 0; i < sampleSize; i++) {
            Game.Result result = game.play(seedGenerator.nextLong(), true);
            totalShots += result.shots.size();
            if (!result.shots.isEmpty() && result.shots.get(0).side == Game.Side.RIGHT) {
                firstShotWentRight++;
            }
        }

        return new ShotSample(sampleSize, totalShots, firstShotWentRight);
    }

    // How many seats clockwise the winner sits from the cowboy who started
    // the game, e.g. 0 = the starter won, 1 = the starter's right neighbour
    // won. floorMod handles the wrap-around correctly for either sign.
    private static int seatOffsetFromStarter(Game.Result result, int cowboyCount) {
        int winnerSeat = Integer.parseInt(result.winner.substring("Cowboy-".length()));
        int starterSeat = Integer.parseInt(result.starter.substring("Cowboy-".length()));
        return Math.floorMod(winnerSeat - starterSeat, cowboyCount);
    }

    private static final int CHART_WIDTH = 40;

    private static void printReport(int cowboyCount, int gameCount, long[] winsBySeatOffset, ShotSample shotSample) {
        System.out.println(cowboyCount + " cowboys, " + gameCount + " games played "
                + "(plus " + shotSample.gamesPlayed + " more, sampled separately for shot-level stats)");
        System.out.println();
        System.out.printf("first shot went RIGHT in %d / %d sampled games (%.1f%%)%n",
                shotSample.firstShotWentRight, shotSample.gamesPlayed, shotSample.firstShotRightPercent());
        System.out.printf("average shots per game: %.1f%n", shotSample.averageShotsPerGame());
        System.out.println();
        System.out.println("win rate by seat, counted from the starter (seat 0 = the starter, seat 1 = the starter's right neighbour):");
        System.out.println();

        double[] winPercentBySeat = new double[cowboyCount];
        double fairSharePercent = 100.0 / cowboyCount;
        double highestPercent = fairSharePercent;
        for (int seat = 0; seat < cowboyCount; seat++) {
            winPercentBySeat[seat] = 100.0 * winsBySeatOffset[seat] / gameCount;
            highestPercent = Math.max(highestPercent, winPercentBySeat[seat]);
        }

        for (int seat = 0; seat < cowboyCount; seat++) {
            String bar = winRateBar(winPercentBySeat[seat], fairSharePercent, highestPercent);
            System.out.printf("  seat %-2d %5.1f%%  %s%n", seat, winPercentBySeat[seat], bar);
        }
        System.out.printf("  (# = win rate, | = fair share if seat didn't matter = %.1f%%)%n", fairSharePercent);
    }

    // One text row of the bar chart: a run of '#' proportional to this
    // seat's win rate, with '|' marking where the fair-share line falls
    // so an over- or under-performing seat is visible at a glance.
    private static String winRateBar(double winPercent, double fairSharePercent, double highestPercent) {
        int filledColumns = (int) Math.round(CHART_WIDTH * winPercent / highestPercent);
        int fairShareColumn = (int) Math.round(CHART_WIDTH * fairSharePercent / highestPercent);

        StringBuilder bar = new StringBuilder(CHART_WIDTH);
        for (int column = 0; column < CHART_WIDTH; column++) {
            if (column < filledColumns) {
                bar.append('#');
            } else if (column == fairShareColumn) {
                bar.append('|');
            } else {
                bar.append(' ');
            }
        }
        return bar.toString();
    }

    // Bundles the results of sampleShots() so they don't have to be passed
    // around main() as several loose counters.
    private static class ShotSample {
        final int gamesPlayed;
        final long totalShots;
        final long firstShotWentRight;

        ShotSample(int gamesPlayed, long totalShots, long firstShotWentRight) {
            this.gamesPlayed = gamesPlayed;
            this.totalShots = totalShots;
            this.firstShotWentRight = firstShotWentRight;
        }

        double averageShotsPerGame() {
            return (double) totalShots / gamesPlayed;
        }

        double firstShotRightPercent() {
            return 100.0 * firstShotWentRight / gamesPlayed;
        }
    }
}
