package com.example.godzilla;

/** Every balance number in one place. 20 ticks = 1 second. Everything is sized for the 94 ft (28.65 block) model. */
public final class GodzillaTuning {
    private GodzillaTuning() {}

    // ---- body ----
    public static final double MAX_HEALTH = 5000.0;
    public static final double ARMOR = 30.0;             // vanilla attribute cap
    public static final double ARMOR_TOUGHNESS = 20.0;   // vanilla attribute cap
    public static final double MOVE_SPEED = 0.17;        // zombie = 0.23. Slow and lumbering (but his stride is huge)
    public static final double FOLLOW_RANGE = 64.0;      // aggro radius (players, villagers, iron golems)
    public static final double STEP_HEIGHT = 4.0;
    public static final float HITBOX_WIDTH = 9.0F;
    public static final float HITBOX_HEIGHT = 28.7F;     // 94 ft = 28.65 blocks
    public static final float EYE_HEIGHT = 27.0F;
    public static final int XP_REWARD = 3000;

    // ---- mob griefing by walking ----
    public static final int GRIEF_INTERVAL_TICKS = 3;
    public static final int GRIEF_MAX_BLOCKS_PER_PASS = 500;
    public static final double GRIEF_HORIZONTAL_MARGIN = 1.5;

    // ---- footsteps ----
    public static final int STEP_INTERVAL_TICKS = 20;    // matches the walk animation (one foot plant per second)
    public static final float STOMP_DAMAGE = 12.0F;
    public static final double STOMP_RADIUS = 7.0;

    // ---- melee ----
    public static final double MELEE_REACH = 16.0;       // horizontal, from the body centre
    public static final float BITE_DAMAGE = 60.0F;
    public static final double BITE_RADIUS = 11.0;       // sphere around the mouth
    public static final int BITE_TICKS = 30;
    public static final int BITE_STRIKE_TICK = 14;
    public static final float TAIL_DAMAGE = 55.0F;
    public static final double TAIL_RADIUS = 34.0;       // his tail is ~40 blocks long
    public static final int TAIL_TICKS = 44;
    public static final int TAIL_STRIKE_TICK = 22;
    public static final int MELEE_COOLDOWN = 25;

    // ---- stomp attack (a real foot stomp with a shockwave, not just a footstep) ----
    public static final double STOMP_ATTACK_MAX_DISTANCE = 13.0;   // target must be this close (horizontal) to be stomped
    public static final double STOMP_ATTACK_REACH = 8.0;           // the foot lands this far ahead of him
    public static final double STOMP_ATTACK_RADIUS = 14.0;
    public static final float STOMP_ATTACK_DAMAGE = 50.0F;
    public static final float STOMP_ATTACK_CRATER = 5.0F;          // vanilla explosion power under the foot
    public static final int STOMP_ATTACK_TICKS = 28;
    public static final int STOMP_ATTACK_STRIKE_TICK = 14;
    public static final int STOMP_ATTACK_COOLDOWN = 30;

    // ---- atomic breath ----
    public static final double BREATH_MIN_RANGE = 18.0;  // closer than this he bites / stomps / tail-swipes instead
    public static final double BREATH_MAX_RANGE = 64.0;
    public static final int BREATH_COOLDOWN = 400;
    public static final int BREATH_COOLDOWN_ENRAGED = 220; // below 50% health
    public static final int ROAR_TICKS = 60;
    public static final int CHARGE_TICKS = 80;           // dorsal plates push out tail -> head
    public static final int INHALE_TICKS = 30;           // plates snap back, head rears, chest swells, air rushes in
    public static final int FIRE_TICKS = 100;            // locked in place
    public static final int VENT_TICKS = 40;
    public static final int PLATE_COUNT = 14;            // must equal PlateProfile.FORWARD.length (generated with the model)
    public static final double BEAM_RANGE = 130.0;
    public static final double BEAM_RADIUS = 4.5;
    public static final double BEAM_SWEEP_DEGREES = 0.0; // 0 = completely locked. >0 sweeps the ray that many degrees each way
    public static final float BEAM_DAMAGE = 40.0F;       // applied every 5 ticks to everything in the beam
    public static final int BEAM_BLOCKS_PER_TICK = 900;  // carve budget per tick (server-lag safety valve)
    public static final int CRATER_INTERVAL_TICKS = 6;
    public static final float CRATER_POWER = 7.0F;       // TNT = 4
    public static final int RADIATION_DURATION_TICKS = 20 * 60;
    public static final float RADIATION_RADIUS = 8.0F;

    // ---- the atomic blast + mushroom cloud (erupts where the ray meets the ground as it ends) ----
    public static final float BLAST_POWER = 14.0F;           // vanilla explosion at ground zero (TNT = 4)
    public static final int BLAST_CRATER_RADIUS = 30;        // everything inside this half-sphere is deleted (not bedrock)
    public static final int BLAST_BLOCKS_PER_TICK = 6000;    // crater removal speed; lower this on slow servers
    public static final double BLAST_SHOCK_RADIUS = 95.0;    // ground shockwave reach (damage + throw)
    public static final int BLAST_FLATTEN_RADIUS = 75;       // the wave strips trees, wood, glass, wool... out to here
    public static final float BLAST_DAMAGE = 90.0F;          // at ground zero, falls off linearly to 0 at the edge
    public static final int BLAST_TICKS = 240;               // how long the cloud keeps forming
    public static final double BLAST_STEM_HEIGHT = 135.0;
    public static final double BLAST_CAP_RADIUS = 58.0;
}
