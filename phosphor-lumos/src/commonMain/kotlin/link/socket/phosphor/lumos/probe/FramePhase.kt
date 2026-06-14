package link.socket.phosphor.lumos.probe

/**
 * The three distinct phases of a Lumos frame, each measured independently by
 * [FrameProbe].
 *
 * A `Probe` inspects without altering: these names identify *where* a
 * frame-millisecond is spent, not how the work is done.
 *
 * - [BUILD] — `VoxelFrameBuilder.build(snapshot)`: turning a runtime snapshot
 *   into a [link.socket.phosphor.lumos.VoxelFrame] of voxel cells.
 * - [PROJECT] — projecting that voxel frame to a surface-specific 2D frame
 *   (`ComposeLattice.project` / `CliLattice.project`).
 * - [DRAW] — painting the projected voxels to the surface (`LumosCanvas`
 *   composable / `CliOrb` terminal write).
 */
enum class FramePhase {
    BUILD,
    PROJECT,
    DRAW,
}
