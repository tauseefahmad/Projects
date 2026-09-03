package cowboyshootout;

import java.security.SecureRandom;

/**
 * Not required by the task, but used to back the "is this game fair?"
 * write-up with numbers instead of just intuition: runs a Monte-Carlo
 * batch of shootouts (protocol recording switched off for speed/memory)
 * and reports win rate by seat position relative to the randomly chosen
 * starting cowboy.
 * <p>
 * Run configuration in IntelliJ:
 *   Program arguments: &lt;numberOfCowboys&gt; [numberOfTrials]
 * Example: {@code 8 200000}
 */
public final class FairnessAnalysis {

    private static final int STARTING_HP = 10;

    public static void main(String[] args) {
        int cowboyCount = args.length >= 1 ? Integer.parseInt(args[0].trim()) : 8;
        int trials = args.length >= 2 ? Integer.parseInt(args[1].trim()) : 200_000;

        ShootoutSimulator simulator = new ShootoutSimulator(cowboyCount, STARTING_HP);
        SecureRandom seedSource = new SecureRandom();

        // winsByOffset[k] = how often the cowboy k seats clockwise from the
        // starting cowboy (0 = the starter itself) won the fight
        long[] winsByOffset = new long[cowboyCount];

        // shot-level stats (direction of the very first shot, average fight length)
        // are only sampled from a subset with recordProtocol=true, since collecting
        // ShotRecords for millions of fast trials would waste memory for no benefit
        int sample = Math.min(trials, 20_000);
        long sampledShots = 0;
        long firstShotWasRight = 0;

        for (int i = 0; i < trials; i++) {
            boolean recordThisOne = i < sample;
            ShootoutResult result = simulator.run(seedSource.nextLong(), recordThisOne);

            int winnerSeat = Integer.parseInt(result.winner().substring("Cowboy-".length()));
            int starterSeat = Integer.parseInt(result.startingCowboy().substring("Cowboy-".length()));
            int offset = Math.floorMod(winnerSeat - starterSeat, cowboyCount);
            winsByOffset[offset]++;

            if (recordThisOne) {
                sampledShots += result.shots().size();
                if (!result.shots().isEmpty() && result.shots().get(0).direction() == Direction.RIGHT) {
                    firstShotWasRight++;
                }
            }
        }

        System.out.println("=== Fairness analysis: " + cowboyCount + " cowboys, " + trials + " trials (+" + sample + " sampled for shot stats) ===");
        System.out.println();
        System.out.printf("First shot went RIGHT in %d / %d sampled fights (%.2f%%)%n",
                firstShotWasRight, sample, 100.0 * firstShotWasRight / sample);
        System.out.printf("Average shots fired per fight: %.2f%n", (double) sampledShots / sample);
        System.out.println();
        System.out.println("Win rate by seat offset from the randomly chosen starting cowboy:");
        System.out.println("(offset 0 = the starter, offset 1 = the starter's RIGHT neighbor - i.e. the very first target, etc.)");
        double expected = 100.0 / cowboyCount;
        for (int offset = 0; offset < cowboyCount; offset++) {
            double pct = 100.0 * winsByOffset[offset] / trials;
            String label = offset == 0 ? "starter " : "starter+" + offset;
            System.out.printf("  offset %-11s: %6.2f%%  (expected if fair: %.2f%%)%n", label, pct, expected);
        }
    }
}
