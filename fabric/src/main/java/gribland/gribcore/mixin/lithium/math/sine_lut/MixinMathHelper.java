package gribland.gribcore.mixin.lithium.math.sine_lut;

import gribland.gribcore.lithium.common.util.math.CompactSineLUT;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(Mth.class)
public class MixinMathHelper {
    /**
     * @author jellysquid3
     * @reason use an optimized implementation
     */
    @Overwrite
    public static float sin(float f) {
        return CompactSineLUT.sin(f);
    }

    /**
     * @author jellysquid3
     * @reason use an optimized implementation
     */
    @Overwrite
    public static float cos(float f) {
        return CompactSineLUT.cos(f);
    }
}
