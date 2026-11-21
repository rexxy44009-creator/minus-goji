package com.example.godzilla.entity;

import com.example.godzilla.GodzillaTuning;
import com.example.godzilla.ai.GodzillaAtomicBreathGoal;
import com.example.godzilla.ai.GodzillaMeleeGoal;
import com.example.godzilla.ai.GodzillaMoveControl;
import com.example.godzilla.ai.GodzillaRampageMoveGoal;
import com.example.godzilla.client.GodzillaClientHooks;
import com.example.godzilla.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.SpectralArrow;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * Godzilla Minus One boss mob.
 *
 * Server owns all behaviour; the client only receives DATA_ACTION (which animation / which plates glow)
 * and the beam yaw/pitch/length (so the client can render the beam). Everything else is server-side.
 */
public class GodzillaEntity extends Monster implements GeoEntity {

    // ------------------------------------------------------------------ action state (synced)
    public static final int ACT_IDLE = 0;
    public static final int ACT_ROAR = 1;
    public static final int ACT_CHARGE = 2;
    public static final int ACT_FIRE = 3;
    public static final int ACT_VENT = 4;
    public static final int ACT_BITE = 5;
    public static final int ACT_TAIL = 6;
    public static final int ACT_DEATH = 7;
    public static final int ACT_STOMP = 8;
    public static final int ACT_INHALE = 9;

    public static final byte EVENT_STOMP = 110;
    public static final byte EVENT_ROAR = 111;
    public static final byte EVENT_BLAST = 112;
    public static final byte EVENT_STOMP_ATTACK = 113;

    public static final int DEATH_TICKS = 100;
    /** Where the mouth is relative to the entity, in blocks (matches the placeholder model; tune for yours). */
    public static final double MOUTH_FORWARD = PlateProfile.MOUTH_FORWARD;
    public static final double MOUTH_HEIGHT = PlateProfile.MOUTH_UP;

    private static final EntityDataAccessor<Integer> DATA_ACTION = SynchedEntityData.defineId(GodzillaEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_BEAM_YAW = SynchedEntityData.defineId(GodzillaEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_BEAM_PITCH = SynchedEntityData.defineId(GodzillaEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_BEAM_LENGTH = SynchedEntityData.defineId(GodzillaEntity.class, EntityDataSerializers.FLOAT);

    private static final EntityDataAccessor<Float> DATA_WALK_PHASE = SynchedEntityData.defineId(GodzillaEntity.class, EntityDataSerializers.FLOAT);

    /** Client-side procedural animation state (bone pose blending). Unused on the server. */
    public final GodzillaAnim.State animState = new GodzillaAnim.State();
    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    private final ServerBossEvent bossEvent =
            new ServerBossEvent(this.getDisplayName(), BossEvent.BossBarColor.BLUE, BossEvent.BossBarOverlay.NOTCHED_10);

    private int actionStartTick;   // tickCount when DATA_ACTION last changed (both sides)
    private float walkPhase, walkPhasePrev;   // radians; one foot lands every PI. Server computes, client reads.
    private boolean phaseInit;
    private long breathReadyTime;  // game time when the atomic breath is available again (server)

    public GodzillaEntity(EntityType<? extends GodzillaEntity> type, Level level) {
        super(type, level);
        this.xpReward = GodzillaTuning.XP_REWARD;
        this.moveControl = new GodzillaMoveControl(this);
        this.setPersistenceRequired();
    }

    // ------------------------------------------------------------------ geckolib
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // Register animation controllers here if needed
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    // ------------------------------------------------------------------ attributes / data / goals
    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, GodzillaTuning.MAX_HEALTH)
                .add(Attributes.ARMOR, GodzillaTuning.ARMOR)
                .add(Attributes.ARMOR_TOUGHNESS, GodzillaTuning.ARMOR_TOUGHNESS)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.MOVEMENT_SPEED, GodzillaTuning.MOVE_SPEED)
                .add(Attributes.FOLLOW_RANGE, GodzillaTuning.FOLLOW_RANGE)
                .add(Attributes.ATTACK_DAMAGE, GodzillaTuning.BITE_DAMAGE)
                .add(Attributes.STEP_HEIGHT, GodzillaTuning.STEP_HEIGHT);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ACTION, ACT_IDLE);
        builder.define(DATA_BEAM_YAW, 0.0F);
        builder.define(DATA_BEAM_PITCH, 0.0F);
        builder.define(DATA_BEAM_LENGTH, 0.0F);
        builder.define(DATA_WALK_PHASE, 0.0F);
    }

    @Override
    protected void registerGoals() {
        // Priority: lower number wins when goals share a flag (MOVE/LOOK).
        this.goalSelector.addGoal(1, new GodzillaAtomicBreathGoal(this)); // roar -> charge -> fire -> vent
        this.goalSelector.addGoal(2, new GodzillaMeleeGoal(this));        // jaw bite / tail swipe
        this.goalSelector.addGoal(3, new GodzillaRampageMoveGoal(this));  // lumber toward the target
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 64.0F));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        // FOLLOW_RANGE (64) is the acquisition radius; mustSee=false so he hunts you through terrain.
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, 10, false, false, null));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Villager.class, 10, false, false, null));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, IronGolem.class, 10, false, false, null));
    }

    // ------------------------------------------------------------------ action / beam accessors
    public int getAction() { return this.entityData.get(DATA_ACTION); }

    /** Server only. Resets the action clock on both sides via onSyncedDataUpdated. */
    public void setAction(int action) { this.entityData.set(DATA_ACTION, action); }

    public int getActionTicks()
