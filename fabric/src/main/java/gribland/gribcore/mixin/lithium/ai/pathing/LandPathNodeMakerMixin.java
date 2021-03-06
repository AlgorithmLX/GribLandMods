package gribland.gribcore.mixin.lithium.ai.pathing;

import gribland.gribcore.lithium.common.ai.pathing.PathNodeCache;
import gribland.gribcore.world.WorldHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Determining the type of node offered by a block state is a very slow operation due to the nasty chain of tag,
 * instanceof, and block property checks. Since each blockstate can only map to one type of node, we can create a
 * cache which stores the result of this complicated code path. This provides a significant speed-up in path-finding
 * code and should be relatively safe.
 */
@Mixin(WalkNodeEvaluator.class)
public abstract class LandPathNodeMakerMixin {
    /**
     * @reason Use optimized implementation
     * @author JellySquid
     */
    @Overwrite
    public static BlockPathTypes getBlockPathTypeRaw(BlockGetter blockView, BlockPos blockPos) {
        BlockState blockState = blockView.getBlockState(blockPos);
        BlockPathTypes type = PathNodeCache.getPathNodeType(blockState);

        // If the node type is open, it means that we were unable to determine a more specific type, so we need
        // to check the fallback path.
        if (type == BlockPathTypes.OPEN) {
            // This is only ever called in vanilla after all other possibilities are exhausted, but before fluid checks
            // It should be safe to perform it last in actuality and take advantage of the cache for fluid types as well
            // since fluids will always pass this check.
            if (!blockState.isPathfindable(blockView, blockPos, PathComputationType.LAND)) {
                return BlockPathTypes.BLOCKED;
            }

            // All checks succeed, this path node really is open!
            return BlockPathTypes.OPEN;
        }

        // Return the cached value since we found an obstacle earlier
        return type;
    }

    /**
     * @reason Use optimized implementation which avoids scanning blocks for dangers where possible
     * @author JellySquid
     */
    @Overwrite
    public static BlockPathTypes checkNeighbourBlocks(BlockGetter world, BlockPos.MutableBlockPos pos, BlockPathTypes type) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();

        LevelChunkSection section = null;

        // Check that all the block's neighbors are within the same chunk column. If so, we can isolate all our block
        // reads to just one chunk and avoid hits against the server chunk manager.
        if (world instanceof CollisionGetter && WorldHelper.areNeighborsWithinSameChunk(pos)) {
            // If the y-coordinate is within bounds, we can cache the chunk section. Otherwise, the if statement to check
            // if the cached chunk section was initialized will early-exit.
            if (!Level.isOutsideBuildHeight(y)) {
                // This cast is always safe and is necessary to obtain direct references to chunk sections.
                LevelChunk chunk = (LevelChunk) ((CollisionGetter) world).getChunkForCollisions(x >> 4, z >> 4);

                // If the chunk is absent, the cached section above will remain null, as there is no chunk section anyways.
                // An empty chunk or section will never pose any danger sources, which will be caught later.
                if (chunk != null) {
                    section = chunk.getSections()[y >> 4];
                }
            }

            // If we can guarantee that blocks won't be modified while the cache is active, try to see if the chunk
            // section is empty or contains any dangerous blocks within the palette. If not, we can assume any checks
            // against this chunk section will always fail, allowing us to fast-exit.
            if (LevelChunkSection.isEmpty(section) || PathNodeCache.isSectionSafeAsNeighbor(section)) {
                return type;
            }
        }

        int xStart = x - 1;
        int yStart = y - 1;
        int zStart = z - 1;

        int xEnd = x + 1;
        int yEnd = y + 1;
        int zEnd = z + 1;

        // Vanilla iteration order is XYZ
        for (int adjX = xStart; adjX <= xEnd; adjX++) {
            for (int adjY = yStart; adjY <= yEnd; adjY++) {
                for (int adjZ = zStart; adjZ <= zEnd; adjZ++) {
                    // Skip the vertical column of the origin block
                    if (adjX == x && adjZ == z) {
                        continue;
                    }

                    BlockState state;

                    // If we're not accessing blocks outside a given section, we can greatly accelerate block state
                    // retrieval by calling upon the cached chunk directly.
                    if (section != null) {
                        state = section.getBlockState(adjX & 15, adjY & 15, adjZ & 15);
                    } else {
                        state = world.getBlockState(pos.set(adjX, adjY, adjZ));
                    }

                    // Ensure that the block isn't air first to avoid expensive hash table accesses
                    if (state.isAir()) {
                        continue;
                    }

                    BlockPathTypes neighborType = PathNodeCache.getNeighborPathNodeType(state);

                    if (neighborType != BlockPathTypes.OPEN) {
                        return neighborType;
                    }
                }
            }
        }

        return type;
    }
}
