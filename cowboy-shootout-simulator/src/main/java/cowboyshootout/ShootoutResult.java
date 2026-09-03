package cowboyshootout;

import java.util.List;

/**
 * Everything needed to write the protocol and print a summary:
 * the seating order the circle started with, which cowboy opened fire,
 * the seed that drove the random numbers (so the run can be replayed),
 * the full list of shots and who was left standing.
 */
record ShootoutResult(
        int cowboyCount,
        int startingHp,
        long randomSeed,
        List<String> initialSeatingOrder,
        String startingCowboy,
        List<ShotRecord> shots,
        String winner,
        int winnerHpRemaining
) {
}
