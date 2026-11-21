package com.example.godzilla.client;

import com.example.godzilla.GodzillaMod;
import com.example.godzilla.client.fx.Fx;
import com.example.godzilla.entity.FxEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/** The blinding white-to-orange flash when an atomic blast goes off, strongest nearby and fading with distance. */
@EventBusSubscriber(modid = GodzillaMod.MODID, value = Dist.CLIENT)
public final class ScreenFlash {
    private ScreenFlash() {}

    @SubscribeEvent
    public static void onGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        float best = 0.0F, warm = 0.0F;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof FxEntity fx) || fx.getKind() != FxEntity.KIND_BLAST) continue;
            float age = fx.tickCount;
            float intensity = age < 2.0F ? age / 2.0F : (float) Math.pow(Fx.clamp01(1.0F - (age - 2.0F) / 40.0F), 1.6);
            float dist = (float) mc.player.distanceTo(fx);
            float near = Fx.clamp01(1.0F - dist / 420.0F);
            float a = intensity * near * near;
            if (a > best) { best = a; warm = Fx.clamp01(age / 22.0F); }
        }
        if (best < 0.01F) return;
        GuiGraphics g = event.getGuiGraphics();
        int color = Fx.lerpRgb(0xFFFFFF, 0xFFB060, warm);
        int alpha = Fx.ch(Math.min(0.96F, best));
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), (alpha << 24) | color);
    }
}
