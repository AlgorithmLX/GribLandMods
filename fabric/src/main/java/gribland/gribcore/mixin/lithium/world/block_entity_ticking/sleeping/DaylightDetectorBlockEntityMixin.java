package gribland.gribcore.mixin.lithium.world.block_entity_ticking.sleeping;

import gribland.gribcore.lithium.common.world.blockentity.SleepingBlockEntity;
import net.minecraft.world.level.block.entity.DaylightDetectorBlockEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(DaylightDetectorBlockEntity.class)
public class DaylightDetectorBlockEntityMixin implements SleepingBlockEntity {
    @Override
    public boolean canTickOnSide(boolean isClient) {
        return !isClient;
    }
}