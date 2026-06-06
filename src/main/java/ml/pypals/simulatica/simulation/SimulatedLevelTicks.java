package ml.pypals.simulatica.simulation;

import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.jspecify.annotations.NonNull;

import java.util.ArrayDeque;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.LongPredicate;

/**
 * A lightweight, chunkless implementation of {@link LevelTickAccess} designed
 * specifically for simulation regions.
 *
 * <p>Unlike the vanilla {@link net.minecraft.world.ticks.LevelTicks}, which organizes
 * ticks into chunk-specific containers and handles chunk loading/unloading semantics,
 * this implementation uses a single global priority queue. This is ideal for our
 * self-contained, in-memory simulations where chunks do not exist.</p>
 */
public class SimulatedLevelTicks<T> extends LevelTicks<T> {

    // All pending ticks, sorted by execution time and priority.
    private final PriorityQueue<ScheduledTick<T>> pendingTicks = new PriorityQueue<>(ScheduledTick.DRAIN_ORDER);

    // Fast lookup to prevent duplicate scheduling of the same block/type combo.
    private final Set<ScheduledTick<?>> ticksPerPosition = new ObjectOpenCustomHashSet<>(ScheduledTick.UNIQUE_TICK_HASH);

    // Ticks pulled from pendingTicks that are scheduled to execute in the current tick.
    private final Queue<ScheduledTick<T>> toRunThisTick = new ArrayDeque<>();
    private final Set<ScheduledTick<?>> toRunThisTickSet = new ObjectOpenCustomHashSet<>(ScheduledTick.UNIQUE_TICK_HASH);

    public SimulatedLevelTicks(LongPredicate longPredicate) {
        super(longPredicate);
    }

    @Override
    public void schedule(@NonNull ScheduledTick<T> scheduledTick) {
        if (this.ticksPerPosition.add(scheduledTick)) {
            this.pendingTicks.add(scheduledTick);
        }
    }

    @Override
    public boolean hasScheduledTick(@NonNull BlockPos blockPos, @NonNull T object) {
        return this.ticksPerPosition.contains(ScheduledTick.probe(object, blockPos));
    }

    @Override
    public boolean willTickThisTick(@NonNull BlockPos blockPos, @NonNull T object) {
        this.calculateTickSetIfNeeded();
        return this.toRunThisTickSet.contains(ScheduledTick.probe(object, blockPos));
    }

    @Override
    public int count() {
        return this.pendingTicks.size() + this.toRunThisTick.size();
    }

    /**
     * Executes ticks whose scheduled time is &le; {@code gameTime}.
     *
     * @param gameTime The current simulation time.
     * @param maxTicks The maximum number of ticks to process in this call (prtevents infinite loops if ticks schedule themselves instantly).
     * @param ticker   The logic to execute for each tick.
     */
    public void tick(long gameTime, int maxTicks, @NonNull BiConsumer<BlockPos, T> ticker) {
        // 1. Drain pending ticks that are due, up to maxTicks
        while (this.toRunThisTick.size() < maxTicks) {
            ScheduledTick<T> peeked = this.pendingTicks.peek();
            if (peeked == null || peeked.triggerTick() > gameTime) {
                break;
            }
            ScheduledTick<T> polled = this.pendingTicks.poll();
            this.ticksPerPosition.remove(polled);
            this.toRunThisTick.add(polled);
        }

        // 2. Execute collected ticks
        while (!this.toRunThisTick.isEmpty()) {
            ScheduledTick<T> tick = this.toRunThisTick.poll();
            if (!this.toRunThisTickSet.isEmpty()) {
                this.toRunThisTickSet.remove(tick);
            }
            ticker.accept(tick.pos(), tick.type());
        }

        // 3. Cleanup
        this.toRunThisTickSet.clear();
    }

    private void calculateTickSetIfNeeded() {
        if (this.toRunThisTickSet.isEmpty() && !this.toRunThisTick.isEmpty()) {
            this.toRunThisTickSet.addAll(this.toRunThisTick);
        }
    }
}
