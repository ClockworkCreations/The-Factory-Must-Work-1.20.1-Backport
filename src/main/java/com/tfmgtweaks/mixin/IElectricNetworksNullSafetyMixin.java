package com.tfmgtweaks.mixin;

import com.drmangotea.tfmg.content.electricity.base.ElectricalNetwork;
import com.drmangotea.tfmg.content.electricity.base.IElectric;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.HashMap;
import java.util.Map;

/**
 * Three of IElectric's default methods call
 * ElectricNetworkManager.networks.get() with no null check, even though
 * that same map's own getOrCreateNetworkFor() uses computeIfAbsent(),
 * proving it needs the guard -- a null here NPE-crashes electric block
 * placement/removal. Redirects to the same null-safe pattern.
 */
@Mixin(IElectric.class)
public interface IElectricNetworksNullSafetyMixin {

    @Redirect(
        method = {"getOrCreateElectricNetwork", "onRemoved", "setNetwork"},
        at = @At(value = "INVOKE", target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"))
    default Object tfmgtweaks$safeNetworksGet(Map<LevelAccessor, Map<Long, ElectricalNetwork>> map, Object key) {
        LevelAccessor level = (LevelAccessor) key;
        return map.computeIfAbsent(level, $ -> new HashMap<>());
    }
}
