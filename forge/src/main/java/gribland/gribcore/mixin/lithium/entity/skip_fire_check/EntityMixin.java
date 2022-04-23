package gribland.gribcore.mixin.lithium.entity.skip_fire_check;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.stream.Stream;

@Mixin(Entity.class)
public abstract class EntityMixin {
    @Shadow
    private int remainingFireTicks;

    @Shadow
    protected abstract int getFireImmuneTicks();

    @Redirect(
            method = "move",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/core/BlockPos;betweenClosedStream(Lnet/minecraft/world/phys/AABB;)Ljava/util/stream/Stream;"
            )
    )
    private Stream<BlockPos> skipFireTestIfResultDoesNotMatter(AABB box) {
        // Skip scanning the blocks around the entity touches by returning an empty stream when the result does not matter
        if (this.remainingFireTicks > 0 || this.remainingFireTicks == -this.getFireImmuneTicks()) {
            return Stream.empty();
        }

        return BlockPos.betweenClosedStream(box);
    }
}
