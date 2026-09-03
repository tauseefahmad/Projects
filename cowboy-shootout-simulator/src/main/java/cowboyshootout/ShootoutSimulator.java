package cowboyshootout;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Core game engine. The rules implemented here are exactly the ones from
 * the task description:
 *
 * <ol>
 *   <li>All cowboys start with the same {@code startingHp}.</li>
 *   <li>They stand in a circle; a random cowboy becomes the first active
 *       shooter.</li>
 *   <li>The active cowboy shoots his direct right neighbor if his own
 *       current HP is even, otherwise his direct left neighbor.</li>
 *   <li>The target loses a random amount of 1-5 HP.</li>
 *   <li>If the target dies (HP &lt;= 0) it is removed and the circle closes
 *       (its former neighbors become direct neighbors); the very same
 *       active cowboy immediately shoots again.</li>
 *   <li>If the target survives, it becomes the new active cowboy.</li>
 *   <li>The fight ends as soon as only one cowboy remains.</li>
 * </ol>
 */
final class ShootoutSimulator {

    static final int MIN_DAMAGE = 1;
    static final int MAX_DAMAGE = 5;

    private final int cowboyCount;
    private final int startingHp;

    ShootoutSimulator(int cowboyCount, int startingHp) {
        if (cowboyCount < 1) {
            throw new IllegalArgumentException("Number of cowboys must be at least 1, was: " + cowboyCount);
        }
        if (startingHp < 1) {
            throw new IllegalArgumentException("Starting HP must be at least 1, was: " + startingHp);
        }
        this.cowboyCount = cowboyCount;
        this.startingHp = startingHp;
    }

    /**
     * Runs one full shootout.
     *
     * @param seed           seed for the RNG, so a run can be reproduced later
     * @param recordProtocol if false, individual shots are not collected
     *                       (used by the Monte-Carlo fairness analysis, which
     *                       only cares about the winner and would otherwise
     *                       waste memory on millions of ShotRecords)
     */
    ShootoutResult run(long seed, boolean recordProtocol) {
        Random random = new Random(seed);

        // build the circle: Cowboy-1 .. Cowboy-n, circularly linked
        Cowboy[] seats = new Cowboy[cowboyCount];
        for (int i = 0; i < cowboyCount; i++) {
            seats[i] = new Cowboy(i + 1, "Cowboy-" + (i + 1), startingHp);
        }
        for (int i = 0; i < cowboyCount; i++) {
            seats[i].next = seats[(i + 1) % cowboyCount];
            seats[i].prev = seats[(i - 1 + cowboyCount) % cowboyCount];
        }

        List<String> initialOrder = new ArrayList<>(cowboyCount);
        for (Cowboy c : seats) {
            initialOrder.add(c.name);
        }

        Cowboy active = seats[random.nextInt(cowboyCount)];
        String startingCowboy = active.name;

        List<ShotRecord> shots = recordProtocol ? new ArrayList<>() : List.of();
        int remaining = cowboyCount;
        int shotNumber = 0;

        while (remaining > 1) {
            Direction direction = active.isEven() ? Direction.RIGHT : Direction.LEFT;
            Cowboy target = (direction == Direction.RIGHT) ? active.next : active.prev;

            int damage = MIN_DAMAGE + random.nextInt(MAX_DAMAGE - MIN_DAMAGE + 1);
            int hpBefore = target.hp;
            target.hp -= damage;
            boolean killed = target.hp <= 0;
            shotNumber++;

            if (recordProtocol) {
                shots.add(new ShotRecord(
                        shotNumber,
                        active.name,
                        active.hp,
                        direction,
                        target.name,
                        damage,
                        hpBefore,
                        Math.max(0, target.hp),
                        killed
                ));
            }

            if (killed) {
                // close the circle
                target.prev.next = target.next;
                target.next.prev = target.prev;
                remaining--;
                // active cowboy shoots again -> loop continues with the same 'active'
            } else {
                active = target; // turn passes to the target
            }
        }

        return new ShootoutResult(
                cowboyCount,
                startingHp,
                seed,
                initialOrder,
                startingCowboy,
                shots,
                active.name,
                active.hp
        );
    }
}
