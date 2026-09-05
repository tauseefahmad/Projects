# Wild West Shootout Simulator

A Java simulation of the circular cowboy shootout described in the task,
producing a JSON protocol of every shot and a SHA-256 checksum of that
protocol file.

## How to run it

**In IntelliJ:** open this folder as a project (`File > Open`, select
`cowboy-shootout-simulator` — IntelliJ will recognize the `pom.xml` and
import it as a Maven project automatically, downloading JUnit for you),
then create a Run Configuration for `cowboyshootout.Main` with a program
argument for the number of cowboys.

**From the command line, with Maven** (also runs the tests, see below):

```
mvn compile
java -cp target/classes cowboyshootout.Main 8
```

**From the command line, without Maven** (just the program, no tests):

```
javac -d out $(find src/main -name "*.java")
java -cp out cowboyshootout.Main 8
```

Program arguments: `<numberOfCowboys> [seed]`

- `8` – simulate 8 cowboys with a freshly generated random seed
- `8 42` – simulate 8 cowboys with a fixed seed (reproducible run, useful
  for testing/grading — this is exactly how `sample-output/` was produced)

The program prints the fight to the console as a short story, then writes
`protocol_<n>cowboys_<timestamp>.json` plus a `.sha256` sidecar file (in the
`sha256sum -c` compatible format) into the working directory, and prints
the checksum.

A pre-generated example lives in [`sample-output/`](sample-output) and
verifies with `sha256sum -c protocol_8cowboys_seed42.json.sha256`.

## Project structure

```
pom.xml                          only needed for running the tests (see below)
src/main/java/cowboyshootout/
  Cowboy.java     one fighter: id, name, hp, prev/next link in the circle
  Game.java       the game rules; plays one game and returns a Game.Result
                  (also holds the small Side/Shot/Result types it works with)
  Protocol.java   writes the JSON file and computes its SHA-256 checksum
  Main.java       CLI entry point, prints the story and calls Protocol
  Stats.java      extra: runs many games to check whether it's fair
src/test/java/cowboyshootout/
  GameTest.java     the game rules: direction, turn order, damage bounds, kills, reproducibility
  ProtocolTest.java JSON output shape, checksum properties
```

`Side` (LEFT/RIGHT), `Shot` (one protocol line) and `Result` (the outcome
of a game) are nested inside `Game` rather than top-level files — they're
small data types that only ever appear alongside `Game`, so folding them
in means one less file to open for the same amount of code. `Protocol`,
`Main`, and `Stats` refer to them as `Game.Side`, `Game.Shot`,
`Game.Result`.

## Tests

Run them with:

```
mvn test
```

`pom.xml` only exists to pull in JUnit 5 (`org.junit.jupiter:junit-jupiter`,
test scope) and run it — the program itself has no dependencies and still
compiles fine with plain `javac` as shown above. IntelliJ auto-detects the
`pom.xml` and lets you run/debug individual tests from the gutter icons.

What's covered:

- **`GameTest`** — the actual rules, checked against real played-out games
  rather than isolated methods: a lone cowboy wins instantly with no shots
  fired; the same seed always replays identically (checked by comparing
  every shot, not just the winner); **every** shot's side matches the
  shooter's hp parity, not just the first one; a kill always makes the
  same cowboy fire again and a survival always passes the turn to the
  target, checked shot-by-shot across a whole game; a cowboy who has been
  killed never appears as a shooter or target again; two cowboys always
  target each other and never themselves; every damage roll lands in 1–5;
  hp is never reported negative; exactly `count - 1` cowboys get killed
  per game (there's always exactly one survivor); and — since every cowboy
  starts at an even 10 hp — the very first shot of a game always goes
  `RIGHT`, checked across several different seeds. Also checks that
  `Game`'s constructor rejects 0 cowboys or 0 starting hp.
- **`ProtocolTest`** — the written file starts/ends with `{`/`}`, has
  balanced braces and brackets, and contains the fields we expect; hashing
  the same content twice gives the same SHA-256 checksum, hashing
  different content gives a different one, and the checksum always looks
  like 64 lowercase hex characters.

These are unit tests against the game logic and file output directly —
they don't re-validate the JSON with a real parser (that was done manually
with `python3 -m json.tool` against `sample-output/`, see above), and they
don't test `Stats.java` (it's a statistical tool, not something with a
single correct output to assert against).

## Design decisions (and why)

- **Circle = circular doubly linked list.** Each `Cowboy` has `prev`
  and `next` pointers. "Closing the circle" after a kill is then a plain
  unlink (`target.prev.next = target.next; target.next.prev = target.prev;`)
  instead of shifting an array or list — it directly mirrors the physical
  picture of two neighbors stepping toward each other.
- **Plain classes with a couple of fields, not records/builders.** `Shot`
  and `Result` are just small data holders, so they're written as
  straightforward classes with public fields and a small constructor
  where useful — nothing to look up an accessor for.
- **`java.util.Random` seeded explicitly, always reported.** `Main` draws
  a seed from `SecureRandom` (or takes one on the command line) and seeds
  a single `Random` with it. The seed is written into the protocol file,
  so any game — "random" or not — can be replayed exactly.
- **Checksum via `java.security.MessageDigest`.** SHA-256 is computed with
  the standard JDK digest API over the exact bytes written to disk
  (`Files.readAllBytes` → `MessageDigest.digest`), formatted with
  `java.util.HexFormat`. No external crypto library needed.
- **Hand-rolled JSON in `Protocol.java`, on purpose.** The file's shape is
  fixed and entirely generated by this program — the only strings in it
  are program-generated cowboy names, so there's no untrusted free text to
  escape carefully. A short writer with correct quoting is enough to
  guarantee valid JSON without pulling in a dependency, which matters for
  a program meant to just be opened and run in IntelliJ. The output was
  checked against `python3 -m json.tool` (a strict JSON parser) and comes
  back well-formed.
- **`Game.play(seed, logShots)`.** The `logShots` flag skips building the
  `Shot` list entirely, so `Stats.java` can run hundreds of thousands of
  games for statistics without allocating and discarding millions of
  `Shot` objects it doesn't need.

## Protocol format

Each shot is one compact JSON object in the `shots` array:

```json
{"num":13,"shooter":"Cowboy-4","shooterHp":8,"side":"RIGHT","target":"Cowboy-5","dmg":3,"hpBefore":3,"hpAfter":0,"killed":true}
```

covering exactly what the sheriff asked for (who fired, who was hit, hp
lost, hp remaining), plus a couple of extra fields (shooter's hp, side,
kill flag) so the whole game — including *why* that side was picked — can
be replayed from the file alone. The surrounding object carries the game
setup (`cowboys`, `startHp`, `seed`, `order`, `starter`) and the outcome
(`winner`, `winnerHp`).

## Is the game fair? (theory + data)

**Short answer: no.** The rule "even hp → shoot right, odd hp → shoot
left" is not a coin flip — it's a fixed function of the *shooter's*
current hp. Randomness only enters through (a) who starts, and (b) the
1–5 damage rolls. That's enough structure to create a real, measurable
bias:

1. **The first shot is never random.** Every cowboy starts at 10 hp,
   which is even. Whoever is drawn as the starter therefore *always*
   shoots right — 100% of the time, not by chance. His immediate right
   neighbor is guaranteed to be the very first person shot at, before a
   single random damage roll has happened. Confirmed below: "first shot
   went RIGHT" comes out at 100.0% no matter how many cowboys are in the
   circle.

2. **Kill streaks snowball in one direction.** When a shot kills its
   target, the *same* cowboy shoots again — and since his own hp didn't
   change, his side doesn't change either. So a cowboy on a kill streak
   keeps shooting the *same* direction until he finally fails to land a
   killing blow. You can see this in `sample-output/protocol_8cowboys_seed42.json`:
   shots 13–15, Cowboy-4 kills three neighbors in a row without switching
   sides. That's a positive feedback loop, not a symmetric process.

3. **Hp carries over and never resets**, so a cowboy who survived an
   earlier hit is more likely to die on the next one — position in the
   circle interacts with *when* a cowboy's turn comes back around, which
   is itself a side effect of rule 1.

Because of this, a cowboy's odds depend a lot on **where they sit
relative to the randomly chosen starter**, not just on luck applied
equally to everyone. To turn that into a number instead of just
intuition, `Stats.java` runs large batches of games and tracks win rate
by seat, counted from the starter (seat 0 = the starter, seat 1 = the
starter's right neighbor = the very first target, and so on around the
circle):

```
  seat 0    7.4%  ################            |
  seat 1    3.4%  ########                    |            <- first victim, worst odds
  seat 2   10.3%  #######################     |
  seat 3   15.4%  ##################################
  seat 4   18.0%  ########################################  <- best odds
  seat 5   17.9%  ########################################  <- best odds
  seat 6   15.8%  ###################################
  seat 7   11.7%  ##########################  |
  (# = win rate, | = fair share if seat didn't matter = 12.5%)
```

The same shape shows up at other cowboy counts (tried 6 and 12): the
starter's immediate right neighbor wins only about **a third to a half**
of a fair share, the starter himself is also below average, and the
cowboys sitting roughly *across* the circle from the starter win
noticeably more than their fair share.

**Conclusion:** the game is not fair. The cowboy *least* likely to win is
whoever ends up seated directly to the right of the randomly chosen
starter — they take the one guaranteed, non-random first shot before the
game has produced any other information, so they're effectively "one hit
down" before anything random has even happened. The cowboys with the best
odds sit roughly opposite the starter: far enough away to dodge an early
kill streak from either side, but close enough to eventually inherit the
active role once a streak runs out of targets. Since the starter is
picked at random each game, no single *named* cowboy has a permanent
edge across many games — but within any one game, seat position relative
to that game's starter is a real, measurable predictor of who is most
likely to walk away.

Reproduce these numbers yourself:

```
java -cp out cowboyshootout.Stats 8 100000
```
