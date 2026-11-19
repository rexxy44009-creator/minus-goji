package com.example.godzilla.ai;

import com.example.godzilla.GodzillaTuning;
import com.example.godzilla.entity.GodzillaEntity;
import com.example.godzilla.registry.ModSounds;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The atomic blast at the end of the ray. In order:
 *   t=0     white flash, a vanilla explosion, then a huge crater (30-block radius) is eaten out over ~10 ticks
 *   t=1-37  a ground shockwave races out to ~95 blocks: dust wall, damage + throw, and it strips trees / wood /
 *           glass / wool / leaves (anything flimsy) out to 75 blocks (terrain itself survives outside the crater)
 *   t=0-200 a fire-and-smoke stem rises ~135 blocks; a rolling mushroom cap (58-block radius) rides its top
 * All of it respects the mobGriefing gamerule except the particles, damage and throw. Server-side only; particles are
 * sent with count 0 (= "these numbers are a velocity").
 */
public final class AtomicBlast {
    private static final List<AtomicBlast> ACTIVE = new ArrayList<>();
    private static final double TAU = Math.PI * 2.0;
    private static final int CRATER_SCANS_PER_TICK = 40000;
    private static final int FLATTEN_COLUMNS_PER_TICK = 900;
    private static int[] craterOffsets;   // packed dx/dy/dz, sorted from the centre outward

    private final ServerLevel level;
    private final Vec3 center;
    private final UUID ownerId;
    private final boolean grief;
    private final Set<UUID> alreadyHit = new HashSet<>();
    private int age;
    private int craterCursor;
    private double flattenedTo;

    private AtomicBlast(ServerLevel level, Vec3 center, UUID ownerId) {
        this.level = level;
        this.center = center;
        this.ownerId = ownerId;
        this.grief = level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
    }

    public static void spawn(ServerLevel level, Vec3 center, GodzillaEntity owner) {
        AtomicBlast blast = new AtomicBlast(level, center, owner.getUUID());
        ACTIVE.add(blast);
        blast.ignite(owner);
    }

    static void tickAll(ServerLevel level) {
        Iterator<AtomicBlast> it = ACTIVE.iterator();
        while (it.hasNext()) {
            AtomicBlast b = it.next();
            if (b.level == level && !b.tick()) it.remove();
        }
    }

    static void clearAll() { ACTIVE.clear(); }

    // ---------------------------------------------------------------- crater shape: wide bowl, ~14 deep, 30 radius
    private static synchronized int[] offsets() {
        if (craterOffsets == null) {
            int R = GodzillaTuning.BLAST_CRATER_RADIUS, depth = Math.max(8, R / 2);
            List<Long> tmp = new ArrayList<>();
            for (int dx = -R; dx <= R; dx++) {
                for (int dy = -depth; dy <= R; dy++) {
                    for (int dz = -R; dz <= R; dz++) {
                        double ry = dy < 0 ? depth : R;
                        double d = (dx * dx + dz * dz) / (double) (R * R) + (dy * dy) / (ry * ry);
                        if (d > 1.0) continue;
                        long key = (long) (d * 1_000_000) << 24 | ((long) (dx + 128) << 16) | ((long) (dy + 128) << 8) | (dz + 128);
                        tmp.add(key);
                    }
                }
            }
            long[] keys = tmp.stream().mapToLong(Long::longValue).toArray();
            Arrays.sort(keys);
            craterOffsets = new int[keys.length];
            for (int i = 0; i < keys.length; i++) craterOffsets[i] = (int) (keys[i] & 0xFFFFFF);
        }
        return craterOffsets;
    }

    // ---------------------------------------------------------------- t = 0: flash + bang
    private void ignite(GodzillaEntity owner) {
        double cx = center.x, cy = center.y, cz = center.z;
        level.explode(owner, cx, cy + 1.0, cz, GodzillaTuning.BLAST_POWER, true, Level.ExplosionInteraction.MOB);

        AtomicFx.send(level, ParticleTypes.FLASH, cx, cy + 10.0, cz, 24, 12.0, 8.0, 12.0, 0.0);
        AtomicFx.send(level, ParticleTypes.END_ROD, cx, cy + 6.0, cz, 700, 0.1, 0.1, 0.1, 2.4);            // white fireball
        AtomicFx.send(level, ParticleTypes.EXPLOSION_EMITTER, cx, cy + 4.0, cz, 16, 14.0, 6.0, 14.0, 0.0);
        AtomicFx.send(level, ParticleTypes.SOUL_FIRE_FLAME, cx, cy + 4.0, cz, 260, 0.3, 0.3, 0.3, 1.6);    // blue-white heart
        AtomicFx.send(level, ParticleTypes.FLAME, cx, cy + 3.0, cz, 300, 0.3, 0.3, 0.3, 1.2);

        level.playSound(null, cx, cy, cz, ModSounds.BLAST.get(), SoundSource.HOSTILE, 120.0F, 0.8F);
        level.playSound(null, cx, cy, cz, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.HOSTILE, 120.0F, 0.45F);
        owner.level().broadcastEntityEvent(owner, GodzillaEntity.EVENT_BLAST);   // client: biggest screen shake
    }

    // ---------------------------------------------------------------- per tick
    private boolean tick() {
        if (++age > GodzillaTuning.BLAST_TICKS) return false;
        Entity owner = level.getEntity(ownerId);
        if (grief) {
            crater();
            flatten();
        }
        shockwave(owner);
        stem();
        cap();
        if (age == 20 || age == 60) {   // the rolling rumble after the bang
            level.playSound(null, center.x, center.y, center.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.HOSTILE, 120.0F, 0.35F + age / 400.0F);
        }
        return true;
    }

    private double surfaceY(double x, double z) {
        BlockPos bp = BlockPos.containing(x, center.y, z);
        if (!level.hasChunkAt(bp)) return center.y;
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bp.getX(), bp.getZ());
    }

    // ---- crater: eat the bowl out, nearest blocks first, within a per-tick budget ---------------------------------
    private void crater() {
        int[] offs = offsets();
        if (craterCursor >= offs.length) return;
        int cx = Mth.floor(center.x), cy = Mth.floor(center.y), cz = Mth.floor(center.z);
        int scans = 0, removed = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (craterCursor < offs.length && scans < CRATER_SCANS_PER_TICK && removed < GodzillaTuning.BLAST_BLOCKS_PER_TICK) {
            int o = offs[craterCursor++];
            scans++;
            pos.set(cx + ((o >> 16) & 0xFF) - 128, cy + ((o >> 8) & 0xFF) - 128, cz + (o & 0xFF) - 128);
            if (!level.hasChunkAt(pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || !state.getFluidState().isEmpty() || state.getDestroySpeed(level, pos) < 0.0F) continue;
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            removed++;
        }
    }

    // ---- the wave strips everything flimsy out to the flatten radius ----------------------------------------------
    private boolean isGround(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(Blocks.GRAVEL)
                || s.is(Blocks.CLAY) || s.is(BlockTags.TERRACOTTA) || s.is(Blocks.SNOW_BLOCK);
    }

    private void flatten() {
        if (age > 30) return;
        double to = Math.min(GodzillaTuning.BLAST_FLATTEN_RADIUS, age * (GodzillaTuning.BLAST_FLATTEN_RADIUS / 28.0));
        double from = flattenedTo;
        flattenedTo = to;
        if (to <= from + 0.01) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int n = 0; n < FLATTEN_COLUMNS_PER_TICK; n++) {
            double r = Math.sqrt(from * from + level.random.nextDouble() * (to * to - from * from));   // uniform over the annulus
            double ang = level.random.nextDouble() * TAU;
            int x = Mth.floor(center.x + Math.cos(ang) * r), z = Mth.floor(center.z + Math.sin(ang) * r);
            pos.set(x, center.y, z);
            if (!level.hasChunkAt(pos)) continue;
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            for (int y = top + 30; y >= top - 14; y--) {
                pos.set(x, y, z);
                BlockState s = level.getBlockState(pos);
                if (s.isAir()) continue;
                if (isGround(s)) break;                                         // never dig into the terrain itself
                if (!s.getFluidState().isEmpty() || s.getDestroySpeed(level, pos) < 0.0F) continue;
                if (s.getBlock().getExplosionResistance() < 4.0F) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    // ---- expanding ground ring: dust wall + damage/launch -------------------------------------------------------
    private void shockwave(Entity owner) {
        if (age > 38) return;
        double r = age * 2.6;
        int n = Math.max(12, Math.min(52, (int) (TAU * r / 2.6)));
        for (int k = 0; k < n; k++) {
            double ang = TAU * k / n + age * 0.37;
            double dx = Math.cos(ang), dz = Math.sin(ang);
            double x = center.x + dx * r, z = center.z + dz * r, y = surfaceY(x, z) + 0.5;
            AtomicFx.send(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, x, y, z, 0, dx * 0.2, 0.07, dz * 0.2, 1.0);
            AtomicFx.send(level, ParticleTypes.POOF, x, y + 1.0, z, 4, 1.4, 1.0, 1.4, 0.2);
        }

        double reach = Math.min(r + 3.0, GodzillaTuning.BLAST_SHOCK_RADIUS);
        AABB zone = new AABB(center, center).inflate(reach, 60.0, reach);
        DamageSource src = level.damageSources().explosion(owner, owner);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, zone, this::canHurt)) {
            if (alreadyHit.contains(e.getUUID())) continue;
            double ex = e.getX() - center.x, ez = e.getZ() - center.z;
            double dist = Math.sqrt(ex * ex + ez * ez);
            if (dist > reach) continue;
            alreadyHit.add(e.getUUID());
            float scale = (float) Math.max(0.0, 1.0 - dist / GodzillaTuning.BLAST_SHOCK_RADIUS);
            e.hurt(src, GodzillaTuning.BLAST_DAMAGE * scale);
            double inv = dist < 1.0E-3 ? 0.0 : 1.0 / dist;
            e.setDeltaMovement(ex * inv * 2.4 * scale, 0.6 + 1.1 * scale, ez * inv * 2.4 * scale);
            e.hurtMarked = true;
        }
    }

    private boolean canHurt(LivingEntity e) {
        if (e instanceof GodzillaEntity || !e.isAlive()) return false;
        return !(e instanceof Player p && (p.isCreative() || p.isSpectator()));
    }

    // ---- the stem: a thick column of fire and smoke, widest at the base ------------------------------------------
    private double stemHeight(int t) {
        return GodzillaTuning.BLAST_STEM_HEIGHT * (1.0 - Math.exp(-t / 45.0));
    }

    private void stem() {
        if (age > 190) return;
        double H = GodzillaTuning.BLAST_STEM_HEIGHT;
        double h = stemHeight(age);
        for (int i = 0; i < 26; i++) {
            double frac = level.random.nextDouble();
            double y = center.y + 1.0 + frac * h;
            double radius = (5.0 + 12.0 * (1.0 - frac) * (1.0 - frac)) * level.random.nextDouble();
            double ang = level.random.nextDouble() * TAU;
            double x = center.x + Math.cos(ang) * radius, z = center.z + Math.sin(ang) * radius;
            double up = 0.16 + level.random.nextDouble() * 0.16;
            double roll = level.random.nextDouble();
            if (age < 70 && frac < 0.55 && roll < 0.45) {
                AtomicFx.send(level, ParticleTypes.FLAME, x, y, z, 0, 0.0, up * 1.3, 0.0, 1.0);
            } else if (roll < 0.62) {
                AtomicFx.send(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, x, y, z, 0, 0.0, up * 0.7, 0.0, 1.0);
            } else if (roll < 0.82) {
                AtomicFx.send(level, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, x, y, z, 0, 0.0, up * 0.5, 0.0, 1.0);
            } else {
                AtomicFx.send(level, ParticleTypes.LARGE_SMOKE, x, y, z, 0, 0.0, up, 0.0, 1.0);
            }
        }
        if (age < 40) {   // blue-white atomic glow at the base
            AtomicFx.send(level, ParticleTypes.SOUL_FIRE_FLAME, center.x, center.y + 3.0, center.z, 14, 6.0, 3.0, 6.0, 0.08);
        }
    }

    // ---- the cap: a rolling torus riding the top of the stem (rises in the middle, spreads on top, falls outside) ---
    private void cap() {
        if (age < 8 || age > 200) return;
        double R = GodzillaTuning.BLAST_CAP_RADIUS * (1.0 - Math.exp(-(age - 7) / 55.0));
        double m = 0.36 * R + 3.0;                                  // tube radius
        double cy = center.y + stemHeight(age) + 6.0;
        for (int i = 0; i < 40; i++) {
            double theta = level.random.nextDouble() * TAU;         // around the ring
            double phi = level.random.nextDouble() * TAU;           // around the tube
            double rx = Math.cos(theta), rz = Math.sin(theta);
            double ring = R + m * Math.cos(phi);
            double x = center.x + rx * ring, z = center.z + rz * ring, y = cy + m * Math.sin(phi) * 0.8;
            double out = Math.sin(phi) * 0.2;                       // top: outward, bottom: inward
            double vy = -Math.cos(phi) * 0.2;                       // outer edge: down, inner edge: up
            double roll = level.random.nextDouble();
            if (Math.sin(phi) < 0.0 && age < 110 && roll < 0.35) {
                AtomicFx.send(level, ParticleTypes.FLAME, x, y, z, 0, rx * out, vy, rz * out, 1.0);   // glowing underside
            } else if (roll < 0.55) {
                AtomicFx.send(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, x, y, z, 0, rx * out, vy * 0.6, rz * out, 1.0);
            } else if (roll < 0.8) {
                AtomicFx.send(level, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, x, y, z, 0, rx * out, vy * 0.4, rz * out, 1.0);
            } else {
                AtomicFx.send(level, ParticleTypes.LARGE_SMOKE, x, y, z, 0, rx * out, vy, rz * out, 1.0);
            }
        }
    }
}
