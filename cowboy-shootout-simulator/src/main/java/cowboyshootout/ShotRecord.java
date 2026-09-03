package cowboyshootout;

/**
 * One immutable line of the sheriff's protocol: who fired, who was hit,
 * how much damage was dealt and how many health points the target has left.
 * The extra fields (shooter HP, direction, before/after HP, kill flag) are
 * not strictly required by the task but make the protocol self-explanatory
 * and let a reader reconstruct/verify the whole fight without re-running it.
 */
record ShotRecord(
        int shotNumber,
        String shooter,
        int shooterHp,
        Direction direction,
        String target,
        int damage,
        int targetHpBefore,
        int targetHpAfter,
        boolean targetKilled
) {
}
