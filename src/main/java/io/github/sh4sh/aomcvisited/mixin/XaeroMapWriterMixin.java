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
import org.spongepowered.asm.mixin.injection.At;
import xaero.map.MapProcessor;
import xaero.map.MapWriter;
import xaero.map.cache.BlockStateShortShapeCache;
import xaero.map.region.MapTile;
import xaero.map.region.MapTileChunk;

/**
 * Records every chunk Xaero's World Map writes fresh map data for. {@code writeChunk} builds a new
 * {@link MapTile} from the loaded chunk and stores it with {@code setTile}; it skips chunks that are missing
 * a neighbour or haven't changed since it last wrote them. Not required: if a
 * Xaero update moves this call, nothing is recorded and exports refuse rather than upload old data.
 */
@Mixin(value = MapWriter.class, remap = false)
public abstract class XaeroMapWriterMixin {
	/** The map layer being written: the normal map, or a cave-mode layer. */
	@Shadow
	private int writingLayer;

	@WrapOperation(method = "writeChunk", at = @At(value = "INVOKE", target = "Lxaero/map/region/MapTileChunk;setTile(IILxaero/map/region/MapTile;Lxaero/map/cache/BlockStateShortShapeCache;Lxaero/map/MapProcessor;)V"))
	private void aomcVisited$recordWrite(MapTileChunk tileChunk, int x, int z, MapTile tile,
			BlockStateShortShapeCache cache, MapProcessor processor, Operation<Void> original,
			@Local(argsOnly = true) Level level) {
		original.call(tileChunk, x, z, tile, cache, processor);
		int chunkX = tile.getChunkX(), chunkZ = tile.getChunkZ();
		if (AomcVisitedClient.VISITED.mapped(level.dimension(), writingLayer, chunkX, chunkZ)
				&& writingLayer == VisitedChunks.SURFACE_LAYER) {
			VisitedHighlighter.refreshRegion(processor, level.dimension(), chunkX >> 5, chunkZ >> 5);
		}
	}
}
