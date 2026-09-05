package cowboyshootout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
