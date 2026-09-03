package cowboyshootout;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

// runs one shootout from start to finish
class Game {

    static final int MIN_DMG = 1;
    static final int MAX_DMG = 5;

    int count;
    int startHp;

    Game(int count, int startHp) {
        if (count < 1) throw new IllegalArgumentException("need at least 1 cowboy");
        if (startHp < 1) throw new IllegalArgumentException("need at least 1 hp");
        this.count = count;
        this.startHp = startHp;
    }

    // logShots=false skips building the Shot list, used when we just want
    // to know who won (e.g. running thousands of games for Stats.java)
    Result play(long seed, boolean logShots) {
        Random rng = new Random(seed);

        // seat the cowboys in a circle
        Cowboy[] seats = new Cowboy[count];
        for (int i = 0; i < count; i++) {
            seats[i] = new Cowboy(i + 1, startHp);
        }
        for (int i = 0; i < count; i++) {
            seats[i].next = seats[(i + 1) % count];
            seats[i].prev = seats[(i - 1 + count) % count];
        }

        List<String> order = new ArrayList<>();
        for (Cowboy c : seats) order.add(c.name);

        Cowboy active = seats[rng.nextInt(count)];
        String starter = active.name;

        List<Shot> shots = logShots ? new ArrayList<>() : List.of();
        int alive = count;
        int shotNum = 0;

        while (alive > 1) {
            Side side = active.hpIsEven() ? Side.RIGHT : Side.LEFT;
            Cowboy target = (side == Side.RIGHT) ? active.next : active.prev;

            int dmg = MIN_DMG + rng.nextInt(MAX_DMG - MIN_DMG + 1);
            int before = target.hp;
            target.hp -= dmg;
            boolean killed = target.hp <= 0;
            shotNum++;

            if (logShots) {
                shots.add(new Shot(shotNum, active.name, active.hp, side, target.name,
                        dmg, before, Math.max(0, target.hp), killed));
            }

            if (killed) {
                target.prev.next = target.next;
                target.next.prev = target.prev;
                alive--;
                // active shoots again, so we just loop without switching active
            } else {
                active = target;
            }
        }

        Result r = new Result();
        r.count = count;
        r.startHp = startHp;
        r.seed = seed;
        r.order = order;
        r.starter = starter;
        r.shots = shots;
        r.winner = active.name;
        r.winnerHp = active.hp;
        return r;
    }
}
