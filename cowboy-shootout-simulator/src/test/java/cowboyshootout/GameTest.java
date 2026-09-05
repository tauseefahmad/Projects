package cowboyshootout;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameTest {

    @Test
    void damageIsAlwaysBetweenOneAndFive() {
        Game.Result r = new Game(10, 10).play(7, true);

        for (Game.Shot s : r.shots) {
            assertTrue(s.dmg >= Game.MIN_DMG && s.dmg <= Game.MAX_DMG,
                    "damage " + s.dmg + " out of range in shot " + s.num);
        }
    }

    @Test
    void exactlyOneCowboyIsEliminatedPerSurvivorLessThanTheStart() {
        int count = 8;
        Game.Result r = new Game(count, 10).play(7, true);

        long kills = r.shots.stream().filter(s -> s.killed).count();
        assertEquals(count - 1, kills);
    }

    @Test
    void everyShotsSideMatchesTheShootersHpParity() {
        Game.Result r = new Game(10, 10).play(7, true);

        for (Game.Shot s : r.shots) {
            Game.Side expected = (s.shooterHp % 2 == 0) ? Game.Side.RIGHT : Game.Side.LEFT;
            assertEquals(expected, s.side, "wrong side for shooterHp=" + s.shooterHp + " in shot " + s.num);
        }
    }

    @Test
    void aKillMakesTheSameShooterFireAgainOtherwiseTheTargetTakesTheTurn() {
        Game.Result r = new Game(8, 10).play(7, true);

        for (int i = 0; i < r.shots.size() - 1; i++) {
            Game.Shot current = r.shots.get(i);
            Game.Shot next = r.shots.get(i + 1);
            String expectedNextShooter = current.killed ? current.shooter : current.target;
            assertEquals(expectedNextShooter, next.shooter, "wrong next shooter after shot " + current.num);
        }
    }

    @Test
    void aKilledCowboyNeverAppearsInAnyLaterShot() {
        Game.Result r = new Game(8, 10).play(7, true);

        Set<String> eliminated = new HashSet<>();
        for (Game.Shot s : r.shots) {
            assertFalse(eliminated.contains(s.shooter), s.shooter + " fired after being eliminated (shot " + s.num + ")");
            assertFalse(eliminated.contains(s.target), s.target + " was targeted after being eliminated (shot " + s.num + ")");
            if (s.killed) {
                eliminated.add(s.target);
            }
        }
    }
}
