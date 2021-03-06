package gribland.gribcore.mixin.lithium.ai.task;

import gribland.gribcore.lithium.common.ai.WeightedListIterable;
import net.minecraft.world.entity.ai.behavior.WeightedList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Iterator;
import java.util.List;

@Mixin(WeightedList.class)
public class WeightedListMixin<U> implements WeightedListIterable<U> {
    @Shadow
    @Final
    protected List<WeightedList.WeightedEntry<? extends U>> entries;

    @Override
    public Iterator<U> iterator() {
        return new ListIterator<>(this.entries.iterator());
    }
}
