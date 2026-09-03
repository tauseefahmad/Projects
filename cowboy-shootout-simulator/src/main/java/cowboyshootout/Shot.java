package cowboyshootout;

// one line of the protocol: who shot who, and what happened
class Shot {
    int num;
    String shooter;
    int shooterHp;
    Side side;
    String target;
    int dmg;
    int hpBefore;
    int hpAfter;
    boolean killed;

    Shot(int num, String shooter, int shooterHp, Side side, String target,
         int dmg, int hpBefore, int hpAfter, boolean killed) {
        this.num = num;
        this.shooter = shooter;
        this.shooterHp = shooterHp;
        this.side = side;
        this.target = target;
        this.dmg = dmg;
        this.hpBefore = hpBefore;
        this.hpAfter = hpAfter;
        this.killed = killed;
    }
}
