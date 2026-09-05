package cowboyshootout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CowboyTest {

    @Test
    void nameIsBuiltFromId() {
        Cowboy c = new Cowboy(3, 10);
        assertEquals("Cowboy-3", c.name);
    }

    @Test
    void toStringIsJustTheName() {
        Cowboy c = new Cowboy(5, 10);
        assertEquals("Cowboy-5", c.toString());
    }

    @Test
    void hpIsEvenTrueForEvenHp() {
        Cowboy c = new Cowboy(1, 10);
        assertTrue(c.hpIsEven());
    }

    @Test
    void hpIsEvenFalseForOddHp() {
        Cowboy c = new Cowboy(1, 9);
        assertFalse(c.hpIsEven());
    }
}
