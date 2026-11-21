package com.example.godzilla.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import com.example.godzilla.registry.ModEntities;

/**
 * An invisible, short-lived marker entity whose client renderer (client/FxRenderer + BlastFx) draws a custom visual effect
 * as a pure function of its age. This is how the server triggers effects without custom networking. Never saved to disk.
 */
public class FxEntity extends Entity {
    public static final int KIND_BLAST = 0, KIND_STOMP = 1, KIND_STEP = 2, KIND_BURST = 3, KIND_RADIATION = 4;

    private static final EntityDataAccessor<Integer> DATA_KIND = SynchedEntityData.defineId(FxEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_SCALE = SynchedEntityData.defineId(FxEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_LIFE = SynchedEntityData.defineId(FxEntity.class, EntityDataSerializers.INT);

    public FxEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.noCulling = true;
    }

    public static int defaultLife(int kind) {
        return switch (kind) {
            case KIND_BLAST -> 250;
            case KIND_STOMP -> 50;
            case KIND_STEP -> 34;
            case KIND_BURST -> 44;
            default -> 1200;
        };
    }

    public static FxEntity spawn(ServerLevel level, int kind, double x, double y, double z, float scale) {
        return spawn(level, kind, x, y, z, scale, defaultLife(kind));
    }

    public static FxEntity spawn(ServerLevel level, int kind, double x, double y, double z, float scale, int life) {
        FxEntity e = new FxEntity(ModEntities.FX.get(), level);
        e.setPos(x, y, z);
        e.entityData.set(DATA_KIND, kind);
        e.entityData.set(DATA_SCALE, scale);
        e.entityData.set(DATA_LIFE, life);
        level.addFreshEntity(e);
        return e;
    }

    public int getKind() { return this.entityData.get(DATA_KIND); }
    public float getScale() { return this.entityData.get(DATA_SCALE); }
    public int getLife() { return this.entityData.get(DATA_LIFE); }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_KIND, KIND_STEP);
        builder.define(DATA_SCALE, 1.0F);
        builder.define(DATA_LIFE, 40);
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide && this.tickCount > getLife()) {
            this.discard();
        }
    }

    @Override public boolean shouldRenderAtSqrDistance(double distance) { return true; }
    @Override public boolean isPickable() { return false; }
    @Override public boolean isPushedByFluid() { return false; }
    @Override public AABB getBoundingBoxForCulling() { return this.getBoundingBox().inflate(300.0); }
    @Override protected void readAdditionalSaveData(CompoundTag tag) { }
    @Override protected void addAdditionalSaveData(CompoundTag tag) { }
}
