package gribland.gribcore.mixin.lithium.world.chunk_access;

import com.mojang.datafixers.util.Either;
import gribland.gribcore.lithium.common.world.chunk.ChunkHolderExtended;
import net.minecraft.Util;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@SuppressWarnings({"OverwriteModifiers"})
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkManagerMixin {

    @Shadow
    @Final
    private ServerChunkCache.MainThreadExecutor mainThreadProcessor;

    @Shadow
    @Final
    private DistanceManager distanceManager;

    @Shadow
    @Final
    public ChunkMap chunkMap;

    @Shadow
    protected abstract ChunkHolder getVisibleChunkIfPresent(long pos);

    @Shadow
    protected abstract boolean runDistanceManagerUpdates();

    @Shadow
    protected abstract boolean chunkAbsent(ChunkHolder holder, int maxLevel);

    @Shadow
    @Final
    private Thread mainThread;
    private long time;

    @Inject(method = "runDistanceManagerUpdates", at = @At("HEAD"))
    private void preTick(CallbackInfoReturnable<Boolean> cir) {
        this.time++;
    }

    /**
     * @reason Optimize the function
     * @author JellySquid
     */
    @Overwrite
    public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean create) {
        if (Thread.currentThread() != this.mainThread) {
            return this.getChunkOffThread(x, z, status, create);
        }

        // Store a local reference to the cached keys array in order to prevent bounds checks later
        long[] cacheKeys = this.cacheKeys;

        // Create a key which will identify this request in the cache
        long key = createCacheKey(x, z, status);

        for (int i = 0; i < 4; ++i) {
            // Consolidate the scan into one comparison, allowing the JVM to better optimize the function
            // This is considerably faster than scanning two arrays side-by-side
            if (key == cacheKeys[i]) {
                ChunkAccess chunk = this.cacheChunks[i];

                // If the chunk exists for the key or we didn't need to create one, return the result
                if (chunk != null || !create) {
                    return chunk;
                }
            }
        }

        // We couldn't find the chunk in the cache, so perform a blocking retrieval of the chunk from storage
        ChunkAccess chunk = this.getChunkBlocking(x, z, status, create);

        if (chunk != null) {
            this.addToCache(key, chunk);
        } else if (create) {
            throw new IllegalStateException("Chunk not there when requested");
        }

        return chunk;
    }

    private ChunkAccess getChunkOffThread(int x, int z, ChunkStatus status, boolean create) {
        return CompletableFuture.supplyAsync(() -> this.getChunk(x, z, status, create), this.mainThreadProcessor).join();
    }

    /**
     * Retrieves a chunk from the storages, blocking to work on other tasks if the requested chunk needs to be loaded
     * from disk or generated in real-time.
     *
     * @param x      The x-coordinate of the chunk
     * @param z      The z-coordinate of the chunk
     * @param status The minimum status level of the chunk
     * @param create True if the chunk should be loaded/generated if it isn't already, otherwise false
     * @return A chunk if it was already present or loaded/generated by the {@param create} flag
     */
    private ChunkAccess getChunkBlocking(int x, int z, ChunkStatus status, boolean create) {
        final long key = ChunkPos.asLong(x, z);
        final int level = 33 + ChunkStatus.getDistance(status);

        ChunkHolder holder = this.getVisibleChunkIfPresent(key);

        // Check if the holder is present and is at least of the level we need
        if (this.chunkAbsent(holder, level)) {
            if (create) {
                // The chunk holder is missing, so we need to create a ticket in order to load it
                this.createChunkLoadTicket(x, z, level);

                // Tick the chunk manager to have our new ticket processed
                this.runDistanceManagerUpdates();

                // Try to fetch the holder again now that we have requested a load
                holder = this.getVisibleChunkIfPresent(key);

                // If the holder is still not available, we need to fail now... something is wrong.
                if (this.chunkAbsent(holder, level)) {
                    throw Util.pauseInIde(new IllegalStateException("No chunk holder after ticket has been added"));
                }
            } else {
                // The holder is absent and we weren't asked to create anything, so return null
                return null;
            }
        } else if (((ChunkHolderExtended) holder).updateLastAccessTime(this.time)) {
            // Only create a new chunk ticket if one hasn't already been submitted this tick
            // This maintains vanilla behavior (preventing chunks from being immediately unloaded) while also
            // eliminating the cost of submitting a ticket for most chunk fetches
            this.createChunkLoadTicket(x, z, level);
        }

        CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> loadFuture = null;
        CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> statusFuture = ((ChunkHolderExtended) holder).getFutureByStatus(status.getIndex());

        if (statusFuture != null) {
            Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure> immediate = statusFuture.getNow(null);

            // If the result is already available, return it
            if (immediate != null) {
                Optional<ChunkAccess> chunk = immediate.left();

                if (chunk.isPresent()) {
                    // Early-return with the already ready chunk
                    return chunk.get();
                }
            } else {
                // The load future will first start with the existing future for this status
                loadFuture = statusFuture;
            }
        }

        // Create a future to load the chunk if none exists
        if (loadFuture == null) {
            if (ChunkHolder.getStatus(holder.getTicketLevel()).isOrAfter(status)) {
                // Create a new future which upgrades the chunk from the previous status level to the desired one
                CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> mergedFuture = this.chunkMap.schedule(holder, status);

                // Add this future to the chunk holder so subsequent calls will see it
                holder.updateChunkToSave(mergedFuture);
                ((ChunkHolderExtended) holder).setFutureForStatus(status.getIndex(), mergedFuture);

                loadFuture = mergedFuture;
            } else {
                if (statusFuture == null) {
                    return null;
                }

                loadFuture = statusFuture;
            }
        }

        // Check if the future is completed first before trying to run other tasks in our idle time
        // This prevents object allocations and method call overhead that would otherwise be instantly invalidated
        // when the future is already complete
        if (!loadFuture.isDone()) {
            // Perform other chunk tasks while waiting for this future to complete
            // This returns when either the future is done or there are no other tasks remaining
            this.mainThreadProcessor.managedBlock(loadFuture::isDone);
        }

        // Wait for the result of the future and unwrap it, returning null if the chunk is absent
        return loadFuture.join().left().orElse(null);
    }

    private void createChunkLoadTicket(int x, int z, int level) {
        ChunkPos chunkPos = new ChunkPos(x, z);

        this.distanceManager.addTicket(TicketType.UNKNOWN, chunkPos, level, chunkPos);
    }

    /**
     * The array of keys (encoding positions and status levels) for the recent lookup cache
     */
    private final long[] cacheKeys = new long[4];

    /**
     * The array of values associated with each key in the recent lookup cache.
     */
    private final ChunkAccess[] cacheChunks = new ChunkAccess[4];

    /**
     * Encodes a chunk position and status into a long. Uses 28 bits for each coordinate value, and 8 bits for the
     * status.
     */
    private static long createCacheKey(int chunkX, int chunkZ, ChunkStatus status) {
        return ((long) chunkX & 0xfffffffL) | (((long) chunkZ & 0xfffffffL) << 28) | ((long) status.getIndex() << 56);
    }

    /**
     * Prepends the chunk with the given key to the recent lookup cache
     */
    private void addToCache(long key, ChunkAccess chunk) {
        for (int i = 3; i > 0; --i) {
            this.cacheKeys[i] = this.cacheKeys[i - 1];
            this.cacheChunks[i] = this.cacheChunks[i - 1];
        }

        this.cacheKeys[0] = key;
        this.cacheChunks[0] = chunk;
    }

    /**
     * Reset our own caches whenever vanilla does the same
     */
    @Inject(method = "clearCache", at = @At("HEAD"))
    private void onCachesCleared(CallbackInfo ci) {
        Arrays.fill(this.cacheKeys, Long.MAX_VALUE);
        Arrays.fill(this.cacheChunks, null);
    }
}
