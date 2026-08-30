package com.tfmgtweaks.mixin;

import com.tfmgtweaks.TFMGTweaks;
import com.tfmgtweaks.compat.TFMGEdition;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Gates which mixins apply based on which TFMG build is loaded (see
 * TFMGEdition). Three categories: bugs CE fixed independently
 * (redundant there), fixes CE's own version would be overwritten by
 * (dangerous), and edition-specific package/signature variants (only
 * one of a matching pair should ever apply). Everything else applies
 * unconditionally on both editions.
 */
public class TFMGTweaksMixinPlugin implements IMixinConfigPlugin {

    /** Bugs CE has independently fixed -- our patch is redundant there. */
    private static final Set<String> REDUNDANT_UNDER_CE = Set.of(
            "RegularEngineTypeMismatchFixMixin",
            "SmartBlockEntityEngineDropFixMixin",
            "LargeTransformerBlockEntityMixin",
            "LargeSwitchBlockEntityMixin",
            "IElectricConnectionEndpointFixMixin",
            "CableConnectorBlockEntityMixin",
            "CastingBasinBlockEntityCapabilityFixMixin",
            "ExhaustBlockEntityCapabilityFixMixin",
            "FlarestackBlockEntityCapabilityFixMixin",
            "DistillationControllerBlockEntityCapabilityFixMixin",
            "IElectricNetworksNullSafetyMixin",
            "BlastStoveBlockEntityMixin",
            "CokeOvenBlockEntityScanDirectionFixMixin",
            "VatBlockEntityRemoveControllerClientFixMixin",
            "TrafficLightGreenPhaseFixMixin",
            "WindingMachineBlockEntityMixin",
            "PolarizerBlockEntityMixin",
            "SteelTankBlockEntityLazyTickMixin",
            "BlastFurnaceOutputBlockEntityMixin",
            "SteelTankBlockEntityMixin",
            "SteelTankBlockUpdateTowerStateMixin",
            "IndustrialBlastingCategoryMixin",
            "TFMGJeiMixin"
    );

    /** CE's own fix went further than ours (added checks/conditions ours lacks) -- leaving ours enabled would overwrite it. */
    private static final Set<String> DANGEROUS_UNDER_CE = Set.of(
            "VatBlockEntityHandleRecipeFixMixin"
    );

    /**
     * Targets a class or method signature CE changed, so the mixin
     * can't attach under CE at all -- a guaranteed apply failure, not
     * just redundant.
     */
    private static final Set<String> VANILLA_ONLY = Set.of(
            "ChemicalVatCategoryMixin",
            "SurfaceScannerBlockEntityMixin"
    );

    /** CE-only equivalents of the VANILLA_ONLY set above, string-targeted so they compile without CE on the classpath. */
    private static final Set<String> CE_ONLY = Set.of(
            "ChemicalVatCategoryMixinCE",
            "SurfaceScannerBlockEntityMixinCE"
    );

    @Override
    public void onLoad(String mixinPackage) {
        TFMGEdition edition = TFMGEdition.current();
        TFMGTweaks.LOGGER.info("[tfmgtweaks] Detected TFMG edition: {}", edition);
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String simpleName = simpleName(mixinClassName);
        boolean isCE = TFMGEdition.isCommunityEdition();

        if (isCE) {
            if (REDUNDANT_UNDER_CE.contains(simpleName)) return false;
            if (DANGEROUS_UNDER_CE.contains(simpleName)) return false;
            if (VANILLA_ONLY.contains(simpleName)) return false;
        } else {
            if (CE_ONLY.contains(simpleName)) return false;
        }

        return true;
    }

    private static String simpleName(String mixinClassName) {
        int idx = mixinClassName.lastIndexOf('.');
        return idx >= 0 ? mixinClassName.substring(idx + 1) : mixinClassName;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
