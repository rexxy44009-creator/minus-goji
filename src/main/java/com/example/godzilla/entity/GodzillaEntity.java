package com.example.godzilla.entity;

import com.example.godzilla.GodzillaTuning;
import com.example.godzilla.ai.GodzillaAtomicBreathGoal;
import com.example.godzilla.ai.GodzillaMeleeGoal;
import com.example.godzilla.ai.GodzillaMoveControl;
import com.example.godzilla.ai.GodzillaRampageMoveGoal;
import com.example.godzilla.client.GodzillaClientHooks;
import com.example.godzilla.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.monster.Monster;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

public class GodzillaEntity extends Monster implements GeoEntity {
    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // Register animation controllers here if needed
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    // Keep everything below line 30 from your existing GodzillaEntity.java here
