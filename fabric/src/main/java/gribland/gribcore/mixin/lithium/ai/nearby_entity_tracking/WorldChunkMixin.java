package gribland.gribcore.mixin.lithium.ai.nearby_entity_tracking;

import gribland.gribcore.entity.tracker.EntityTrackerEngineProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelChunk.class)
public class WorldChunkMixin {

    @Shadow
    @Final
    private Level level;

    @Shadow
    @Final
    private ChunkPos chunkPos;

    @Inject(
            method = "addEntity",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/ClassInstanceMultiMap;add(Ljava/lang/Object;)Z"
            )
    )
    private void onEntityAdded(Entity entity, CallbackInfo ci) {
        if (entity instanceof LivingEntity) {
            EntityTrackerEngineProvider.gribcore$getEntityTracker(this.level).onEntityAdded(entity.xChunk, entity.yChunk, entity.zChunk, (LivingEntity) entity);
        }
    }

    @Inject(
            method = "removeEntity(Lnet/minecraft/world/entity/Entity;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/ClassInstanceMultiMap;remove(Ljava/lang/Object;)Z"
            )
    )
    private void onEntityRemoved(Entity entity, int i, CallbackInfo ci) {
        if (entity instanceof LivingEntity) {
            EntityTrackerEngineProvider.gribcore$getEntityTracker(this.level).onEntityRemoved(this.chunkPos.x, i, this.chunkPos.z, (LivingEntity) entity);
        }
    }
}
