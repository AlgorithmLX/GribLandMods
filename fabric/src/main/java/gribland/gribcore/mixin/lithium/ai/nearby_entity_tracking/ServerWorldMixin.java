package gribland.gribcore.mixin.lithium.ai.nearby_entity_tracking;

import gribland.gribcore.entity.tracker.EntityTrackerEngine;
import gribland.gribcore.entity.tracker.EntityTrackerEngineProvider;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Iterator;

/**
 * Installs event listeners to the world class which will be used to notify the {@link EntityTrackerEngine} of changes.
 */
@Mixin(ServerLevel.class)
public class ServerWorldMixin {
    /**
     * Notify the entity tracker when an entity is removed from the world.
     */
    @Redirect(
            method = "unload",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Iterator;next()Ljava/lang/Object;"
            )
    )
    private Object onEntityRemoved(Iterator<Entity> iterator) {
        Entity entity = iterator.next();
        if (!(entity instanceof LivingEntity)) {
            return entity;
        }

        int chunkX = Mth.floor(entity.getX()) >> 4;
        int chunkY = Mth.clamp(Mth.floor(entity.getY()) >> 4, 0, 15);
        int chunkZ = Mth.floor(entity.getZ()) >> 4;

        EntityTrackerEngine tracker = EntityTrackerEngineProvider.gribcore$getEntityTracker(this);
        tracker.onEntityRemoved(chunkX, chunkY, chunkZ, (LivingEntity) entity);
        return entity;
    }
}
