package cowboyshootout;

import java.util.List;

// everything that happened during one game
class Result {
    int count;
    int startHp;
    long seed;
    List<String> order;
    String starter;
    List<Shot> shots;
    String winner;
    int winnerHp;
}
