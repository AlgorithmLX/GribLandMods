package gribland.gribcore.mixin.lithium.ai.task;

import gribland.gribcore.lithium.common.ai.WeightedListIterable;
import me.shedaniel.architectury.event.events.TickEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.GateBehavior;
import net.minecraft.world.entity.ai.behavior.WeightedList;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Set;

@Mixin(GateBehavior.class)
public class CompositeTaskMixin<E extends LivingEntity> {
    @Shadow
    @Final
    private WeightedList<Behavior<? super E>> behaviors;

    @Shadow
    @Final
    private Set<MemoryModuleType<?>> exitErasedMemories;

    /**
     * @reason Replace stream code with traditional iteration
     * @author JellySquid
     */
    @Overwrite
    public boolean canStillUse(ServerLevel world, E entity, long time) {
        for (Behavior<? super E> task : WeightedListIterable.cast(this.behaviors)) {
            if (task.getStatus() == Behavior.Status.RUNNING) {
                if (task.tryStart(world, entity, time)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * @reason Replace stream code with traditional iteration
     * @author JellySquid
     */
    @Overwrite
    public void tick(ServerLevel world, E entity, long time) {
        for (Behavior<? super E> task : WeightedListIterable.cast(this.behaviors)) {
            if (task.getStatus() == Behavior.Status.RUNNING) {
                task.tickOrStop(world, entity, time);
            }
        }
    }

    /**
     * @reason Replace stream code with traditional iteration
     * @author JellySquid
     */
    @Overwrite
    public void stop(ServerLevel world, E entity, long time) {
        for (Behavior<? super E> task : WeightedListIterable.cast(this.behaviors)) {
            if (task.getStatus() == Behavior.Status.RUNNING) {
                task.doStop(world, entity, time);
            }
        }

        Brain<?> brain = entity.getBrain();

        for (MemoryModuleType<?> module : this.exitErasedMemories) {
            brain.eraseMemory(module);
        }
    }
}
