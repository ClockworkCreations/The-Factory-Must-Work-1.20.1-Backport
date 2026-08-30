package com.tfmgtweaks.mixin;

import com.drmangotea.tfmg.content.electricity.base.IElectric;
import com.drmangotea.tfmg.content.electricity.measurement.MultimeterItem;
import com.simibubi.create.content.equipment.goggles.GogglesItem;
import com.simibubi.create.content.equipment.goggles.GoggleOverlayRenderer;
import com.simibubi.create.foundation.gui.RemovedGuiUtils;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Wearing goggles blocks the multimeter overlay entirely -- TFMG's own
 * GoggleOverlayRendererMixin gates its whole tooltip on
 * `isElectricBlock && !hasGoggles`, which looks like a typo for
 * !holdsMultimeter (the inner check already handles that correctly).
 * Rather than modify TFMG's own injected code (an earlier @Redirect
 * attempt was fragile and had no visible effect), this adds an
 * independent injection that only activates for the goggles-worn gap,
 * rendering the same tooltip and cancelling the rest of renderOverlay.
 */
@Mixin(GoggleOverlayRenderer.class)
public abstract class GoggleOverlayRendererMultimeterFixMixin {

    @Inject(method = "renderOverlay", at = @At("HEAD"), cancellable = true, remap = false)
    private static void tfmgtweaks$renderMultimeterEvenWithGoggles(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (!GogglesItem.isWearingGoggles(mc.player)) {
            return;
        }
        if (!MultimeterItem.isHeldByPlayer(mc.player)) {
            return;
        }

        HitResult objectMouseOver = mc.hitResult;
        if (!(objectMouseOver instanceof BlockHitResult result)) {
            return;
        }

        ClientLevel world = mc.level;
        BlockPos pos = GoggleOverlayRenderer.proxiedOverlayPosition(world, result.getBlockPos());
        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof IElectric electric)) {
            return;
        }

        boolean isShifting = mc.player.isShiftKeyDown();
        List<Component> tooltip = new ArrayList<>();
        electric.makeMultimeterTooltip(tooltip, isShifting);
        if (tooltip.isEmpty()) {
            return;
        }

        int width = guiGraphics.guiWidth();
        int height = guiGraphics.guiHeight();
        int posX = width / 2;
        int posY = height / 2;

        // Standard vanilla tooltip colors -- same ones GuiGraphics itself
        // uses internally, avoids depending on Create's config-driven
        // color/fade logic.
        RemovedGuiUtils.drawHoveringText(guiGraphics, tooltip, posX, posY, width, height, -1,
                0xF0100010, 0x505000FF, 0x5028007F, mc.font);

        ci.cancel();
    }
}
