package gribland.gribcore.lithium.common.world.interests;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.Set;

public class PointOfInterestTypeHelper {
    private static Set<BlockState> TYPES;

    public static void init(Set<BlockState> types) {
        if (TYPES != null) {
            throw new IllegalStateException("Already initialized");
        }

        TYPES = types;
    }

    public static boolean shouldScan(LevelChunkSection section) {
        return section.maybeHas(TYPES::contains);
    }

}
