package com.tfmgtweaks.mixin;

import com.drmangotea.tfmg.recipes.VatMachineRecipe;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * CE-only counterpart to ChemicalVatCategoryMixin: vat recipes with a
 * minSize requirement never show it in JEI, still true in CE's
 * relocated integration.jei.category package. String-targeted since
 * that class doesn't exist at this path when compiling against vanilla
 * TFMG. TFMGTweaksMixinPlugin ensures exactly one of the two mixins is
 * ever active.
 */
@Mixin(targets = "com.drmangotea.tfmg.integration.jei.category.ChemicalVatCategory")
public abstract class ChemicalVatCategoryMixinCE {

    @Inject(
        method = "draw(Lcom/drmangotea/tfmg/recipes/VatMachineRecipe;Lmezz/jei/api/gui/ingredient/IRecipeSlotsView;Lnet/minecraft/client/gui/GuiGraphics;DD)V",
        at = @At("TAIL"))
    private void tfmgtweaks$drawMinimumVatSize(VatMachineRecipe recipe, IRecipeSlotsView iRecipeSlotsView,
                                                GuiGraphics graphics, double mouseX, double mouseY, CallbackInfo ci) {
        if (recipe.minSize <= 0) {
            return;
        }
        // Position/color match the original fix in ChemicalVatCategoryMixin.
        graphics.drawString(Minecraft.getInstance().font, "Min. Size: " + recipe.minSize, 106, 9, 16579836);
    }
}
