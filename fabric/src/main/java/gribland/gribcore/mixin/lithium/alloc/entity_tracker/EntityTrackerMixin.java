package gribland.gribcore.mixin.lithium.alloc.entity_tracker;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;
//ChunkMap$TrackedEntity
@Mixin(ChunkMap.TrackedEntity.class)
public class EntityTrackerMixin {
    @Mutable
    @Shadow
    @Final
    private Set<ServerPlayer> seenBy;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void reinit(ChunkMap parent /* non-static class parent */, Entity entity, int maxDistance,
                        int tickInterval, boolean alwaysUpdateVelocity, CallbackInfo ci) {
        // Uses less memory, and will cache the returned iterator
        this.seenBy = new ObjectOpenHashSet<>(this.seenBy);
    }
}
