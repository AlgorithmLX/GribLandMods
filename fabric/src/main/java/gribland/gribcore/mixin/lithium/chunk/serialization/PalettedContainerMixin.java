package gribland.gribcore.mixin.lithium.chunk.serialization;

import gribland.gribcore.lithium.common.world.chunk.CompactingPackedIntegerArray;
import gribland.gribcore.lithium.common.world.chunk.LithiumHashPalette;
import net.minecraft.core.IdMapper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.BitStorage;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Function;

@Mixin(PalettedContainer.class)
public abstract class PalettedContainerMixin<T> {
    private static final ThreadLocal<short[]> cachedCompactionArrays = ThreadLocal.withInitial(() -> new short[4096]);
    private static final long[] EMPTY_PALETTE_DATA = new long[(4 * 4096) / 64];

    @Shadow
    public abstract void acquire();

    @Shadow
    public abstract void release();

    @Shadow
    protected abstract T get(int index);

    @Shadow
    @Final
    private T defaultValue;

    @Shadow
    @Final
    private IdMapper<T> registry;

    @Shadow
    private int bits;

    @Shadow
    @Final
    private Function<CompoundTag, T> reader;

    @Shadow
    @Final
    private Function<T, CompoundTag> writer;

    @Shadow
    protected BitStorage storage;

    @Shadow
    private Palette<T> palette;

    /**
     * This patch incorporates a number of changes to significantly reduce the time needed to serialize.
     * - If a palette only contains one entry, do not attempt to repack it
     * - The packed integer array is iterated over using a specialized consumer instead of a naive for-loop.
     * - A temporary fixed array is used to cache palette lookups and remaps while compacting a data array.
     * - If the palette didn't change after compaction, avoid the step of re-packing the integer array and instead do
     * a simple memory copy.
     *
     * @reason Optimize serialization
     * @author JellySquid
     */
    @Overwrite
    public void write(CompoundTag rootTag, String paletteKey, String dataKey) {
        this.acquire();

        // The palette that will be serialized
        LithiumHashPalette<T> palette = null;
        long[] dataArray = null;

        if (this.palette instanceof LithiumHashPalette) {
            palette = ((LithiumHashPalette<T>) this.palette);

            // The palette only contains the default block, so don't re-pack
            if (palette.getSize() == 1 && palette.valueFor(0) == this.defaultValue) {
                dataArray = EMPTY_PALETTE_DATA;
            }
        }

        // If we aren't going to use an empty data array, start a compaction
        if (dataArray == null) {
            LithiumHashPalette<T> compactedPalette = new LithiumHashPalette<>(this.registry, this.bits, null, this.reader, this.writer);

            short[] array = cachedCompactionArrays.get();
            ((CompactingPackedIntegerArray) this.storage).compact(this.palette, compactedPalette, array);

            // If the palette didn't change during compaction, do a simple copy of the data array
            if (palette != null && palette.getSize() == compactedPalette.getSize()) {
                dataArray = this.storage.getRaw().clone();
            } else {
                // Re-pack the integer array as the palette has changed size
                int size = Math.max(4, Mth.ceillog2(compactedPalette.getSize()));
                BitStorage copy = new BitStorage(size, 4096);

                for (int i = 0; i < array.length; ++i) {
                    copy.set(i, array[i]);
                }

                // We don't need to clone the data array as we are the sole owner of it
                dataArray = copy.getRaw();
                palette = compactedPalette;
            }
        }

        ListTag paletteTag = new ListTag();
        palette.toTag(paletteTag);

        rootTag.put(paletteKey, paletteTag);
        rootTag.putLongArray(dataKey, dataArray);

        this.release();
    }

    /**
     * If we know the palette will contain a fixed number of elements, we can make a significant optimization by counting
     * blocks with a simple array instead of a integer map. Since palettes make no guarantee that they are bounded,
     * we have to try and determine for each implementation type how many elements there are.
     *
     * @author JellySquid
     */
    @Inject(method = "count", at = @At("HEAD"), cancellable = true)
    public void count(PalettedContainer.CountConsumer<T> consumer, CallbackInfo ci) {
        int len = (1 << this.bits);

        // Do not allocate huge arrays if we're using a large palette
        if (len > 4096) {
            return;
        }

        short[] counts = new short[len];

        this.storage.getAll(i -> counts[i]++);

        for (int i = 0; i < counts.length; i++) {
            T obj = this.palette.valueFor(i);

            if (obj != null) {
                consumer.accept(obj, counts[i]);
            }
        }

        ci.cancel();
    }
}
