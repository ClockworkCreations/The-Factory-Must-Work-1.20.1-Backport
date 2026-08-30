package com.tfmgtweaks.mixin;

import com.drmangotea.tfmg.content.electricity.connection.cables.CableConnection;
import com.drmangotea.tfmg.content.electricity.connection.cables.CableConnectorBlockEntity;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * After removing a cable insulator, the other end keeps showing stale
 * voltage/network data, since three places here read
 * connection.blockPos1 assuming it's always the other end, when it's
 * actually whichever endpoint wasn't stored as blockPos2. Reads
 * whichever endpoint isn't this connector's own position instead.
 * require = 0, since CE rewrote this logic and no longer has these
 * field accesses to redirect.
 */
@Mixin(CableConnectorBlockEntity.class)
public abstract class CableConnectorBlockEntityMixin {

    @Redirect(
        require = 0,
        method = {
            "notifyRemoval",
            "lambda$removeConnection$1",
            "getConnectedWires(Ljava/util/List;)Ljava/util/List;"
        },
        at = @At(value = "FIELD",
            target = "Lcom/drmangotea/tfmg/content/electricity/connection/cables/CableConnection;blockPos1:Lnet/minecraft/core/BlockPos;"))
    private BlockPos tfmgtweaks$getActualPartnerPos(CableConnection connection) {
        CableConnectorBlockEntity self = (CableConnectorBlockEntity) (Object) this;
        return connection.blockPos1.equals(self.getBlockPos()) ? connection.blockPos2 : connection.blockPos1;
    }
}
