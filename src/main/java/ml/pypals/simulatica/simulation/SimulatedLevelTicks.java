package ml.pypals.simulatica.simulation;

import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.jspecify.annotations.NonNull;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.function.BiConsumer;

import sun.misc.Unsafe;

public class SimulatedLevelTicks<T> extends LevelTicks<T> {

    private PriorityQueue<ScheduledTick<T>> pendingTicks;

    private Set<ScheduledTick<?>> ticksPerPosition;

    private Queue<ScheduledTick<T>> toRunThisTick;
    private Set<ScheduledTick<?>> toRunThisTickSet;

    private SimulatedLevelTicks() {
        super(null);
        throw new UnsupportedOperationException("Instantiate via create() to bypass constructor");
    }

    @SuppressWarnings("unchecked")
    public static <T> SimulatedLevelTicks<T> create() {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Unsafe unsafe = (Unsafe) f.get(null);

            SimulatedLevelTicks<T> instance = (SimulatedLevelTicks<T>) unsafe
                    .allocateInstance(SimulatedLevelTicks.class);
            instance.init();
            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Failed to allocate SimulatedLevelTicks via Unsafe", e);
        }
    }

    private void init() {
        this.pendingTicks = new PriorityQueue<>(ScheduledTick.DRAIN_ORDER);
        this.ticksPerPosition = new ObjectOpenCustomHashSet<>(ScheduledTick.UNIQUE_TICK_HASH);
        this.toRunThisTick = new ArrayDeque<>();
        this.toRunThisTickSet = new ObjectOpenCustomHashSet<>(ScheduledTick.UNIQUE_TICK_HASH);
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

    public void tick(long gameTime, int maxTicks, @NonNull BiConsumer<BlockPos, T> ticker) {
        while (this.toRunThisTick.size() < maxTicks) {
            ScheduledTick<T> peeked = this.pendingTicks.peek();
            if (peeked == null || peeked.triggerTick() > gameTime) {
                break;
            }
            ScheduledTick<T> polled = this.pendingTicks.poll();
            this.ticksPerPosition.remove(polled);
            this.toRunThisTick.add(polled);
        }

        while (!this.toRunThisTick.isEmpty()) {
            ScheduledTick<T> tick = this.toRunThisTick.poll();
            if (!this.toRunThisTickSet.isEmpty()) {
                this.toRunThisTickSet.remove(tick);
            }
            ticker.accept(tick.pos(), tick.type());
        }
        this.toRunThisTickSet.clear();
    }

    private void calculateTickSetIfNeeded() {
        if (this.toRunThisTickSet.isEmpty() && !this.toRunThisTick.isEmpty()) {
            this.toRunThisTickSet.addAll(this.toRunThisTick);
        }
    }
}
