package com.example.godzilla.ai;

import com.example.godzilla.GodzillaTuning;
import com.example.godzilla.entity.GodzillaEntity;
import com.example.godzilla.entity.FxEntity;
import com.example.godzilla.registry.ModSounds;
import java.util.EnumSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Three close-range attacks, each wind-up -> strike -> recover with the strike tick aligned to its animation:
 *   BITE   - target in front: jaw snap, victims launched skyward
 *   TAIL   - target behind / on the flanks: the whole tail sweeps the rear arc
 *   STOMP  - target close in front: he lifts a foot and slams it down; a shockwave rolls out, cracks the ground
 *            and throws everything near the foot (picked about half the time for close front targets)
 */
public class GodzillaMeleeGoal extends Goal {
    private static final int NONE = 0, BITE = 1, TAIL = 2, STOMP = 3;

    private final GodzillaEntity g;
    private int attack = NONE;
    private int tick;
    private long nextAttackTime;

    public GodzillaMeleeGoal(GodzillaEntity g) {
        this.g = g;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));   // plants his feet while attacking
    }

    @Override public boolean canUse() {
        LivingEntity t = g.getTarget();
        if (t == null || !t.isAlive() || g.getAction() != GodzillaEntity.ACT_IDLE) return false;
        if (g.level().getGameTime() < nextAttackTime) return false;
        double dx = t.getX() - g.getX(), dz = t.getZ() - g.getZ();
        if (Math.sqrt(dx * dx + dz * dz) > GodzillaTuning.MELEE_REACH) return false;
        return t.getY() > g.getY() - 6.0 && t.getY() < g.getY() + 34.0;
    }

    private int totalTicks() {
        return switch (attack) {
            case BITE -> GodzillaTuning.BITE_TICKS;
            case TAIL -> GodzillaTuning.TAIL_TICKS;
            default -> GodzillaTuning.STOMP_ATTACK_TICKS;
        };
    }

    @Override public boolean canContinueToUse() { return attack != NONE && g.isAlive() && tick < totalTicks(); }
    @Override public boolean requiresUpdateEveryTick() { return true; }

    @Override public void start() {
        LivingEntity t = g.getTarget();
        float yawToTarget = t == null ? g.getYRot() : GodzillaEntity.yawTo(g.position(), t.position());
        float rel = Mth.wrapDegrees(yawToTarget - g.getYRot());
        double dx = t == null ? 99 : t.getX() - g.getX(), dz = t == null ? 99 : t.getZ() - g.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (Math.abs(rel) > 100.0F) {
            attack = TAIL;
        } else if (dist < GodzillaTuning.STOMP_ATTACK_MAX_DISTANCE && g.getRandom().nextFloat() < 0.5F) {
            attack = STOMP;
        } else {
            attack = BITE;
        }
        tick = 0;
        g.setAction(switch (attack) {
            case BITE -> GodzillaEntity.ACT_BITE;
            case TAIL -> GodzillaEntity.ACT_TAIL;
            default -> GodzillaEntity.ACT_STOMP;
        });
        g.getNavigation().stop();
    }

    @Override public void stop() {
        if (g.isAlive()) g.setAction(GodzillaEntity.ACT_IDLE);
        nextAttackTime = g.level().getGameTime() + (attack == STOMP ? GodzillaTuning.STOMP_ATTACK_COOLDOWN : GodzillaTuning.MELEE_COOLDOWN);
        attack = NONE;
    }

    @Override public void tick() {
        tick++;
        LivingEntity t = g.getTarget();
        boolean windup = (attack == BITE && tick < GodzillaTuning.BITE_STRIKE_TICK)
                || (attack == STOMP && tick < GodzillaTuning.STOMP_ATTACK_STRIKE_TICK);
        if (windup && t != null) {
            g.turnBodyTowards(GodzillaEntity.yawTo(g.position(), t.position()), 6.0F);
        }
        if (!(g.level() instanceof ServerLevel level)) return;
        if (attack == BITE && tick == GodzillaTuning.BITE_STRIKE_TICK) strikeBite(level);
        if (attack == TAIL && tick == GodzillaTuning.TAIL_STRIKE_TICK) strikeTail(level);
        if (attack == STOMP && tick == GodzillaTuning.STOMP_ATTACK_STRIKE_TICK) strikeStomp(level);
    }

    // ---------------------------------------------------------------- bite
    private void strikeBite(ServerLevel level) {
        Vec3 mouth = g.getMouthPosition();
        Vec3 fwd = g.getForwardHoriz();
        g.playSound(ModSounds.BITE.get(), 8.0F, 0.7F);
        double r = GodzillaTuning.BITE_RADIUS;
        AABB zone = new AABB(mouth, mouth).inflate(r + 1.0);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, zone, g::isValidVictim)) {
            Vec3 c = e.getBoundingBox().getCenter();
            if (c.distanceTo(mouth) > r + e.getBbWidth() / 2.0) continue;
            Vec3 flat = c.subtract(g.position()).multiply(1, 0, 1);
            if (flat.lengthSqr() > 1.0E-4 && fwd.dot(flat.normalize()) < 0.2) continue;   // must be in front
            e.hurt(g.damageSources().mobAttack(g), GodzillaTuning.BITE_DAMAGE);
            g.launch(e, g.position(), 2.6, 1.7);
        }
    }

    // ---------------------------------------------------------------- tail
    private void strikeTail(ServerLevel level) {
        Vec3 fwd = g.getForwardHoriz();
        g.playSound(ModSounds.TAIL.get(), 8.0F, 0.6F);
        double reach = GodzillaTuning.TAIL_RADIUS;
        FxEntity.spawn(level, FxEntity.KIND_STOMP, g.getX(), g.getY(), g.getZ(), 1.5F);   // custom dust shock around the sweep
        AABB zone = g.getBoundingBox().inflate(reach, 6.0, reach);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, zone, g::isValidVictim)) {
            Vec3 flat = e.position().subtract(g.position()).multiply(1, 0, 1);
            double dist = flat.length();
            if (dist > reach + e.getBbWidth() / 2.0) continue;
            if (dist > 1.0E-4 && fwd.dot(flat.normalize()) > 0.3) continue;   // rear and flanks only
            e.hurt(g.damageSources().mobAttack(g), GodzillaTuning.TAIL_DAMAGE);
            g.launch(e, g.position(), 3.4, 1.9);
        }
    }

    // ---------------------------------------------------------------- stomp
    private void strikeStomp(ServerLevel level) {
        Vec3 fwd = g.getForwardHoriz();
        Vec3 foot = g.position().add(fwd.scale(GodzillaTuning.STOMP_ATTACK_REACH));
        int groundY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(foot.x), Mth.floor(foot.z));
        Vec3 impact = new Vec3(foot.x, Math.max(groundY, g.getY()), foot.z);

        g.playSound(ModSounds.STOMP.get(), 14.0F, 0.7F);
        level.broadcastEntityEvent(g, GodzillaEntity.EVENT_STOMP_ATTACK);   // client: heavy screen shake
        level.explode(g, impact.x, impact.y + 0.5, impact.z, GodzillaTuning.STOMP_ATTACK_CRATER, false, Level.ExplosionInteraction.MOB);

        FxEntity.spawn(level, FxEntity.KIND_STOMP, impact.x, impact.y, impact.z, 1.0F);   // custom shockwave, dust wall and debris

        AABB zone = new AABB(impact, impact).inflate(GodzillaTuning.STOMP_ATTACK_RADIUS, 8.0, GodzillaTuning.STOMP_ATTACK_RADIUS);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, zone, g::isValidVictim)) {
            double ex = e.getX() - impact.x, ez = e.getZ() - impact.z;
            double dist = Math.sqrt(ex * ex + ez * ez);
            if (dist > GodzillaTuning.STOMP_ATTACK_RADIUS + e.getBbWidth() / 2.0) continue;
            float scale = (float) Math.max(0.25, 1.0 - dist / (GodzillaTuning.STOMP_ATTACK_RADIUS * 1.2));
            e.hurt(g.damageSources().mobAttack(g), GodzillaTuning.STOMP_ATTACK_DAMAGE * scale);
            g.launch(e, impact, 1.6 * scale + 0.4, 1.3 * scale + 0.4);
        }
    }

    private static Vec3 rotateY(Vec3 v, double radians) {
        double c = Math.cos(radians), s = Math.sin(radians);
        return new Vec3(v.x * c - v.z * s, 0.0, v.x * s + v.z * c);
    }
}
