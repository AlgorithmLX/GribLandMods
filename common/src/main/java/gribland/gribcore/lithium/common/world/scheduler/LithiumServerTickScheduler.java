package gribland.gribcore.lithium.common.world.scheduler;

import it.unimi.dsi.fastutil.longs.Long2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectSortedMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ServerTickList;
import net.minecraft.world.level.TickNextTickData;
import net.minecraft.world.level.TickPriority;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public class LithiumServerTickScheduler<T> extends ServerTickList<T> {
    private static final Predicate<TickEntry<?>> PREDICATE_ANY_TICK = entry -> true;
    private static final Predicate<TickEntry<?>> PREDICATE_ACTIVE_TICKS = entry -> !entry.consumed;
    private final Long2ObjectSortedMap<TickEntryQueue<T>> scheduledTicksOrdered = new Long2ObjectAVLTreeMap<>();
    private final Long2ObjectOpenHashMap<Set<TickEntry<T>>> scheduledTicksByChunk = new Long2ObjectOpenHashMap<>();
    private final Map<TickNextTickData<T>, TickEntry<T>> scheduledTicks = new HashMap<>();
    private final ArrayList<TickEntry<T>> executingTicks = new ArrayList<>();
    private final Predicate<T> invalidObjPredicate;
    private final ServerLevel world;
    private final Consumer<TickNextTickData<T>> tickConsumer;

    public LithiumServerTickScheduler(ServerLevel world, Predicate<T> invalidPredicate, Function<T, ResourceLocation> idToName, Consumer<TickNextTickData<T>> tickConsumer) {
        super(world, invalidPredicate, idToName, tickConsumer);

        this.invalidObjPredicate = invalidPredicate;
        this.world = world;
        this.tickConsumer = tickConsumer;
    }

    @Override
    public void tick() {
        this.world.getProfiler().push("cleaning");

        this.selectTicks(this.world.getChunkSource(), this.world.getGameTime());

        this.world.getProfiler().popPush("executing");

        this.executeTicks(this.tickConsumer);

        this.world.getProfiler().pop();
    }

    @Override
    public boolean willTickThisTick(BlockPos pos, T obj) {
        TickEntry<T> entry = this.scheduledTicks.get(new TickNextTickData<>(pos, obj));

        if (entry == null) {
            return false;
        }

        return entry.executing;
    }

    @Override
    public boolean hasScheduledTick(BlockPos pos, T obj) {
        TickEntry<T> entry = this.scheduledTicks.get(new TickNextTickData<>(pos, obj));

        if (entry == null) {
            return false;
        }

        return entry.scheduled;
    }

    @Override
    public List<TickNextTickData<T>> fetchTicksInChunk(ChunkPos chunkPos, boolean mutates, boolean getStaleTicks) {
        //[VanillaCopy] bug chunk steals ticks from neighboring chunk on unload + does so only in the negative direction
        BoundingBox box = new BoundingBox(chunkPos.getMinBlockX() - 2, Integer.MIN_VALUE, chunkPos.getMinBlockZ() - 2, chunkPos.getMinBlockX() + 16, Integer.MAX_VALUE, chunkPos.getMinBlockZ() + 16);

        return this.fetchTicksInArea(box, mutates, getStaleTicks);
    }

    @Override
    public List<TickNextTickData<T>> fetchTicksInArea(BoundingBox box, boolean remove, boolean getStaleTicks) {
        return this.collectTicks(box, remove, getStaleTicks ? PREDICATE_ANY_TICK : PREDICATE_ACTIVE_TICKS);
    }

    @Override
    public void copy(BoundingBox box, BlockPos pos) {
        List<TickNextTickData<T>> list = this.fetchTicksInArea(box, false, false);

        for (TickNextTickData<T> tick : list) {
            this.addScheduledTick(new TickNextTickData<>(tick.pos.offset(pos), tick.getType(), tick.triggerTick, tick.priority));
        }
    }

    @Override
    public void scheduleTick(BlockPos pos, T obj, int delay, TickPriority priority) {
        if (!this.invalidObjPredicate.test(obj)) {
            this.addScheduledTick(new TickNextTickData<>(pos, obj, (long) delay + this.world.getGameTime(), priority));
        }
    }

    /**
     * Returns the number of currently scheduled ticks.
     */
    @Override
    public int size() {
        int count = 0;

        for (TickEntry<T> entry : this.scheduledTicks.values()) {
            if (entry.scheduled) {
                count += 1;
            }
        }

        return count;
    }

    /**
     * Enqueues all scheduled ticks before the specified time and prepares them for execution.
     */
    public void selectTicks(ServerChunkCache chunkManager, long time) {
        // Calculates the maximum key value which includes all ticks scheduled before the specified time
        long headKey = getBucketKey(time + 1, TickPriority.EXTREMELY_HIGH) - 1;

        // [VanillaCopy] ServerTickScheduler#tick
        // In order to fulfill the promise of not breaking vanilla behaviour, we keep the vanilla artifact of
        // tick suppression.
        int limit = 65536;

        boolean canTick = true;
        long prevChunk = Long.MIN_VALUE;

        // Create an iterator over only
        Iterator<TickEntryQueue<T>> it = this.scheduledTicksOrdered.headMap(headKey).values().iterator();

        // Iterate over all scheduled ticks and enqueue them for until we exceed our budget
        while (limit > 0 && it.hasNext()) {
            TickEntryQueue<T> list = it.next();

            // Pointer for writing scheduled ticks back into the queue
            int w = 0;

            // Re-builds the scheduled tick queue in-place
            for (int i = 0; i < list.size(); i++) {
                TickEntry<T> tick = list.getTickAtIndex(i);

                if (!tick.scheduled) {
                    continue;
                }

                // If no more ticks can be scheduled for execution this phase, then we leave it in its current time
                // bucket and skip it. This deliberately introduces a bug where backlogged ticks will not be re-scheduled
                // properly, re-producing the vanilla issue of tick suppression.
                if (limit > 0) {
                    long chunk = ChunkPos.asLong(tick.pos.getX() >> 4, tick.pos.getZ() >> 4);

                    // Take advantage of the fact that if any position in a chunk can be updated, then all other positions
                    // in the same chunk can be updated. This avoids the more expensive check to the chunk manager.
                    if (prevChunk != chunk) {
                        prevChunk = chunk;
                        canTick = chunkManager.isTickingChunk(tick.pos);
                    }

                    // If the tick can be executed right now, then add it to the executing list and decrement our
                    // budget limit.
                    if (canTick) {
                        tick.scheduled = false;
                        tick.executing = true;

                        this.executingTicks.add(tick);

                        limit--;

                        // Avoids the tick being kept in the scheduled queue
                        continue;
                    }
                }

                // Nothing happened to this tick, so re-add it to the queue
                list.setTickAtIndex(w++, tick);
            }

            // Finalize our changes to the queue and notify it of the new length
            list.resize(w);

            // If the queue is empty, remove it from the map
            if (list.isEmpty()) {
                it.remove();
            }
        }
    }

    public void executeTicks(Consumer<TickNextTickData<T>> consumer) {
        // Mark and execute all executing ticks
        for (TickEntry<T> tick : this.executingTicks) {
            try {
                // Mark as consumed before execution per vanilla behaviour
                tick.executing = false;

                // Perform tick execution
                consumer.accept(tick);

                // If the tick didn't get re-scheduled, we're finished and this tick should be deleted
                if (!tick.scheduled) {
                    this.removeTickEntry(tick);
                }
            } catch (Throwable e) {
                CrashReport crash = CrashReport.forThrowable(e, "Exception while ticking");
                CrashReportCategory section = crash.addCategory("Block being ticked");
                CrashReportCategory.populateBlockDetails(section, tick.pos, null);

                throw new ReportedException(crash);
            }
        }


        // We finished executing those ticks, so empty the list.
        this.executingTicks.clear();
    }

    private List<TickNextTickData<T>> collectTicks(BoundingBox bounds, boolean remove, Predicate<TickEntry<?>> predicate) {
        List<TickNextTickData<T>> ret = new ArrayList<>();

        int minChunkX = bounds.x0 >> 4;
        int maxChunkX = bounds.x1 >> 4;

        int minChunkZ = bounds.z0 >> 4;
        int maxChunkZ = bounds.z1 >> 4;

        // Iterate over all chunks encompassed by the block box
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                long chunk = ChunkPos.asLong(chunkX, chunkZ);

                Set<TickEntry<T>> set = this.scheduledTicksByChunk.get(chunk);

                if (set == null) {
                    continue;
                }

                for (TickEntry<T> tick : set) {
                    BlockPos pos = tick.pos;

                    // [VanillaCopy] ServerTickScheduler#transferTickInBounds
                    // The minimum coordinate is include while the maximum coordinate is exclusive
                    // Possibly a bug in vanilla, but we need to match it here.
                    if (pos.getX() >= bounds.x0 && pos.getX() < bounds.x1 && pos.getZ() >= bounds.z0 && pos.getZ() < bounds.z1) {
                        if (predicate.test(tick)) {
                            ret.add(tick);
                        }
                    }
                }
            }
        }

        if (remove) {
            for (TickNextTickData<T> tick : ret) {
                // It's not possible to downcast a collection, so we have to upcast here
                // This will always succeed
                this.removeTickEntry((TickEntry<T>) tick);
            }
        }

        return ret;
    }

    /**
     * Schedules a tick for execution if it has not already been. To match vanilla, we do not re-schedule matching
     * scheduled ticks which are set to execute at a different time.
     */
    private void addScheduledTick(TickNextTickData<T> tick) {
        TickEntry<T> entry = this.scheduledTicks.computeIfAbsent(tick, this::createTickEntry);

        if (!entry.scheduled) {
            TickEntryQueue<T> timeIdx = this.scheduledTicksOrdered.computeIfAbsent(getBucketKey(tick.triggerTick, tick.priority), key -> new TickEntryQueue<>());
            timeIdx.push(entry);

            entry.scheduled = true;
        }
    }

    private TickEntry<T> createTickEntry(TickNextTickData<T> tick) {
        Set<TickEntry<T>> chunkIdx = this.scheduledTicksByChunk.computeIfAbsent(getChunkKey(tick.pos), LithiumServerTickScheduler::createChunkIndex);

        return new TickEntry<>(tick, chunkIdx);
    }

    private void removeTickEntry(TickEntry<T> tick) {
        tick.scheduled = false;
        tick.consumed = true;

        tick.chunkIdx.remove(tick);

        if (tick.chunkIdx.isEmpty()) {
            this.scheduledTicksByChunk.remove(getChunkKey(tick.pos));
        }

        this.scheduledTicks.remove(tick);
    }

    private static <T> Set<TickEntry<T>> createChunkIndex(long pos) {
        return new ObjectOpenHashSet<>(8);
    }

    // Computes a chunk key from a block position
    private static long getChunkKey(BlockPos pos) {
        return ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
    }

    // Computes a timestamped key including the tick's priority
    // Keys can be sorted in descending order to find what should be executed first
    // 60 time bits, 4 priority bits
    private static long getBucketKey(long time, TickPriority priority) {
        return (time << 4L) | (priority.ordinal() & 15);
    }
}