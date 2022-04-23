package gribland.gribcore.mixin.lithium.gen.biome_noise_cache;

import gribland.gribcore.lithium.common.world.layer.CloneableContext;
import gribland.gribcore.lithium.common.world.layer.FastCachingLayerSampler;
import it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.newbiome.area.LazyArea;
import net.minecraft.world.level.newbiome.context.BigContext;
import net.minecraft.world.level.newbiome.context.LazyAreaContext;
import net.minecraft.world.level.newbiome.layer.traits.PixelTransformer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LazyAreaContext.class)
public class CachingLayerContextMixin implements CloneableContext<LazyArea> {
    @Shadow
    @Final
    @Mutable
    private long seed;

    @Shadow
    @Final
    @Mutable
    private ImprovedNoise biomeNoise;

    @Shadow
    @Final
    @Mutable
    private Long2IntLinkedOpenHashMap cache;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void init(int cacheCapacity, long seed, long salt, CallbackInfo ci) {
        // We don't use this cache
        this.cache = null;
    }

    /**
     * @reason Replace with optimized cache implementation
     * @author gegy1000
     */
    @Overwrite
    public LazyArea createResult(PixelTransformer operator) {
        return new FastCachingLayerSampler(128, operator);
    }

    /**
     * @reason Replace with optimized cache implementation
     * @author gegy1000
     */
    @Overwrite
    public LazyArea createResult(PixelTransformer operator, LazyArea sampler) {
        return new FastCachingLayerSampler(512, operator);
    }

    /**
     * @reason Replace with optimized cache implementation
     * @author gegy1000
     */
    @Overwrite
    public LazyArea createResult(PixelTransformer operator, LazyArea left, LazyArea right) {
        return new FastCachingLayerSampler(512, operator);
    }

    @Override
    public BigContext<LazyArea> cloneContext() {
        LazyAreaContext context = new LazyAreaContext(0, 0, 0);

        CachingLayerContextMixin access = (CachingLayerContextMixin) (Object) context;
        access.seed = this.seed;
        access.biomeNoise = this.biomeNoise;

        return context;
    }
}
