package cowboyshootout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameTest {

    @Test
    void oneCowboyWinsWithoutAShotBeingFired() {
        Game.Result r = new Game(1, 10).play(1, true);

        assertEquals("Cowboy-1", r.winner);
        assertEquals(10, r.winnerHp);
        assertTrue(r.shots.isEmpty());
    }

    @Test
    void sameSeedAlwaysPlaysOutTheSameWay() {
        Game game = new Game(8, 10);

        Game.Result first = game.play(42, true);
        Game.Result second = game.play(42, true);

        assertEquals(first.winner, second.winner);
        assertEquals(first.winnerHp, second.winnerHp);
        assertEquals(shotsAsText(first.shots), shotsAsText(second.shots));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 42, 100, 999})
    void firstShotAlwaysGoesRightBecauseEveryoneStartsAtAnEvenHp(long seed) {
        Game.Result r = new Game(6, 10).play(seed, true);

        assertEquals(Game.Side.RIGHT, r.shots.get(0).side);
    }

    @Test
    void damageIsAlwaysBetweenOneAndFive() {
        Game.Result r = new Game(10, 10).play(7, true);

        for (Game.Shot s : r.shots) {
            assertTrue(s.dmg >= Game.MIN_DMG && s.dmg <= Game.MAX_DMG,
                    "damage " + s.dmg + " out of range in shot " + s.num);
        }
    }

    @Test
    void hpAfterAShotIsNeverReportedNegative() {
        Game.Result r = new Game(10, 10).play(7, true);

        for (Game.Shot s : r.shots) {
            assertTrue(s.hpAfter >= 0, "negative hp reported in shot " + s.num);
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
    void winnerAlwaysEndsWithPositiveHp() {
        Game.Result r = new Game(8, 10).play(7, true);
        assertTrue(r.winnerHp > 0);
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

    @Test
    void twoCowboysAlwaysTargetEachOther() {
        Game.Result r = new Game(2, 10).play(3, true);

        assertTrue(r.shots.size() >= 1);
        for (Game.Shot s : r.shots) {
            assertFalse(s.shooter.equals(s.target), "a cowboy can't shoot themself");
        }
    }

    @Test
    void constructorRejectsZeroCowboys() {
        assertThrows(IllegalArgumentException.class, () -> new Game(0, 10));
    }

    @Test
    void constructorRejectsZeroStartingHp() {
        assertThrows(IllegalArgumentException.class, () -> new Game(5, 0));
    }

    private static List<String> shotsAsText(List<Game.Shot> shots) {
        List<String> lines = new ArrayList<>();
        for (Game.Shot s : shots) {
            lines.add(s.num + " " + s.shooter + " " + s.side + " " + s.target + " " + s.dmg + " " + s.killed);
        }
        return lines;
    }
}
