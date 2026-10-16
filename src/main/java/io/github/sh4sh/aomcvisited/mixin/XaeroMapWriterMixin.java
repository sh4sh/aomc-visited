package io.github.sh4sh.aomcvisited.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.sh4sh.aomcvisited.AomcVisitedClient;
import io.github.sh4sh.aomcvisited.VisitedChunks;
import io.github.sh4sh.aomcvisited.VisitedHighlighter;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.MapProcessor;
import xaero.map.MapWriter;
import xaero.map.cache.BlockStateShortShapeCache;
import xaero.map.region.MapBlock;
import xaero.map.region.MapTile;
import xaero.map.region.MapTileChunk;

/**
 * Records every chunk Xaero's World Map writes fresh map data for, and whether it differs from what the
 * map had there before.
 *
 * <p>{@code writeChunk} skips chunks that are missing a neighbour or haven't changed since it last wrote
 * them. Otherwise it takes the chunk's existing tile (or a fresh one from its pool for new ground),
 * computes each pixel, compares it with the pixel already there ({@code equalsSlopesExcluded}, ignoring
 * slope shading, which depends on neighbours), overwrites it in place, and finally stores the tile with
 * {@code setTile}. Because the tile is overwritten in place, "did anything differ" can only be learned
 * from those per-pixel comparisons.
 *
 * <p>Not required: if a Xaero update moves these calls, nothing is recorded and exports refuse rather
 * than upload old data.
 */
@Mixin(value = MapWriter.class, remap = false)
public abstract class XaeroMapWriterMixin {
	/** The map layer being written: the normal map, or a cave-mode layer. */
	@Shadow
	private int writingLayer;

	/** Whether a pixel of the chunk being written differed from what the map had. */
	@Unique
	private boolean aomcVisited$differs;

	@Inject(method = "writeChunk", at = @At("HEAD"))
	private void aomcVisited$startChunk(CallbackInfoReturnable<Boolean> cir) {
		aomcVisited$differs = false;
	}

	@WrapOperation(method = "writeChunk", at = @At(value = "INVOKE", target = "Lxaero/map/region/MapBlock;equalsSlopesExcluded(Lxaero/map/region/MapBlock;)Z"))
	private boolean aomcVisited$comparePixel(MapBlock fresh, MapBlock previous, Operation<Boolean> original) {
		boolean same = original.call(fresh, previous);
		if (!same) {
			aomcVisited$differs = true;
		}
		return same;
	}

	@WrapOperation(method = "writeChunk", at = @At(value = "INVOKE", target = "Lxaero/map/region/MapTileChunk;setTile(IILxaero/map/region/MapTile;Lxaero/map/cache/BlockStateShortShapeCache;Lxaero/map/MapProcessor;)V"))
	private void aomcVisited$recordWrite(MapTileChunk tileChunk, int x, int z, MapTile tile,
			BlockStateShortShapeCache cache, MapProcessor processor, Operation<Void> original,
			@Local(argsOnly = true) Level level) {
		// No tile there yet means Xaero took a fresh one from its pool: ground the map didn't have.
		boolean differs = aomcVisited$differs || tileChunk.getTile(x, z) == null;
		original.call(tileChunk, x, z, tile, cache, processor);
		int chunkX = tile.getChunkX(), chunkZ = tile.getChunkZ();
		if (AomcVisitedClient.VISITED.mapped(level.dimension(), writingLayer, chunkX, chunkZ, differs)
				&& writingLayer == VisitedChunks.SURFACE_LAYER) {
			VisitedHighlighter.refreshRegion(processor, level.dimension(), chunkX >> 5, chunkZ >> 5);
		}
	}
}
