package cowboyshootout;

/**
 * A single combatant. The circle itself is modelled as a circular doubly
 * linked list of Cowboy nodes (prev/next), which makes "closing the circle"
 * after a kill an O(1) unlink instead of shifting elements in an array/list.
 */
final class Cowboy {

    final int seatNumber;   // 1-based position in the original circle, used only for display/identification
    final String name;
    int hp;

    Cowboy prev;
    Cowboy next;

    Cowboy(int seatNumber, String name, int hp) {
        this.seatNumber = seatNumber;
        this.name = name;
        this.hp = hp;
    }

    boolean isEven() {
        return hp % 2 == 0;
    }

    @Override
    public String toString() {
        return name;
    }
}
