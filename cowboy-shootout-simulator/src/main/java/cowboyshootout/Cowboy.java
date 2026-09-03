package cowboyshootout;

// One fighter standing in the circle. The circle itself is just these
// objects linked to each other (prev/next), so removing a dead cowboy
// is as simple as pointing his neighbors at each other.
class Cowboy {

    int id;
    String name;
    int hp;
    Cowboy prev;
    Cowboy next;

    Cowboy(int id, int hp) {
        this.id = id;
        this.name = "Cowboy-" + id;
        this.hp = hp;
    }

    boolean hpIsEven() {
        return hp % 2 == 0;
    }

    @Override
    public String toString() {
        return name;
    }
}
