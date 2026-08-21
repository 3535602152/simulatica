package ml.pypals.simulatica.simulation.server;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * A slot in the simulation level that one schematic sub-region is mapped into.
 *
 * <p>The mapping is a pure translation, and only in X and Z. Y is left.
 * The schematic actually sits in the world. Rotation and mirroring are not represented here at all.
 * The schematic projection already holds the transformed block states, so the transform is baked in
 * once when the contents are copied and never applied again per-block.</p>
 *
 * @param dimension which simulation level this lives in
 * @param worldMin  inclusive minimum corner in client world coordinates
 * @param worldMax  inclusive maximum corner in client world coordinates
 * @param offsetX   added to a world X to get the simulation X
 * @param offsetZ   added to a world Z to get the simulation Z
 */
public record SimulationRegion(
        ResourceKey<Level> dimension,
        BlockPos worldMin,
        BlockPos worldMax,
        int offsetX,
        int offsetZ
) {

    public BlockPos toSim(BlockPos world) {
        return new BlockPos(world.getX() + this.offsetX, world.getY(), world.getZ() + this.offsetZ);
    }

    public BlockPos toWorld(BlockPos sim) {
        return new BlockPos(sim.getX() - this.offsetX, sim.getY(), sim.getZ() - this.offsetZ);
    }

    /** Whether a simulation position falls inside the mapped contents (margin excluded). */
    public boolean containsSim(BlockPos sim) {
        int x = sim.getX() - this.offsetX;
        int z = sim.getZ() - this.offsetZ;
        return x >= this.worldMin.getX() && x <= this.worldMax.getX()
                && z >= this.worldMin.getZ() && z <= this.worldMax.getZ()
                && sim.getY() >= this.worldMin.getY() && sim.getY() <= this.worldMax.getY();
    }

    public ChunkPos simChunkMin() {
        return new ChunkPos((this.worldMin.getX() + this.offsetX) >> 4, (this.worldMin.getZ() + this.offsetZ) >> 4);
    }

    public ChunkPos simChunkMax() {
        return new ChunkPos((this.worldMax.getX() + this.offsetX) >> 4, (this.worldMax.getZ() + this.offsetZ) >> 4);
    }

    /** Whether another region comes within {@code margin} blocks of this one on every axis. */
    public boolean isWithin(SimulationRegion other, int margin) {
        if (!this.dimension.equals(other.dimension)) {
            return false;
        }
        return simMinX() - margin <= other.simMaxX() && simMaxX() + margin >= other.simMinX()
                && simMinZ() - margin <= other.simMaxZ() && simMaxZ() + margin >= other.simMinZ()
                && this.worldMin.getY() - margin <= other.worldMax.getY()
                && this.worldMax.getY() + margin >= other.worldMin.getY();
    }

    private int simMinX() { return this.worldMin.getX() + this.offsetX; }
    private int simMaxX() { return this.worldMax.getX() + this.offsetX; }
    private int simMinZ() { return this.worldMin.getZ() + this.offsetZ; }
    private int simMaxZ() { return this.worldMax.getZ() + this.offsetZ; }

    /** The contents as a box in simulation coordinates, expanded to block bounds. */
    public AABB simBounds() {
        return new AABB(simMinX(), this.worldMin.getY(), simMinZ(),
                simMaxX() + 1.0, this.worldMax.getY() + 1.0, simMaxZ() + 1.0);
    }

    public int blockCount() {
        return (this.worldMax.getX() - this.worldMin.getX() + 1)
                * (this.worldMax.getY() - this.worldMin.getY() + 1)
                * (this.worldMax.getZ() - this.worldMin.getZ() + 1);
    }
}
