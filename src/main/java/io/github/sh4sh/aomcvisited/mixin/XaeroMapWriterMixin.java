package io.github.sh4sh.aomcvisited.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.sh4sh.aomcvisited.AomcVisitedClient;
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

/** Xaero overwrites the tile in place, so whether it differs can only be learned pixel by pixel. */
@Mixin(value = MapWriter.class, remap = false)
public abstract class XaeroMapWriterMixin {
	@Shadow
	private int writingLayer;

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
		// No tile there yet means Xaero took a fresh one from its pool: areas the map didn't have.
		boolean differs = aomcVisited$differs || tileChunk.getTile(x, z) == null;
		original.call(tileChunk, x, z, tile, cache, processor);
		AomcVisitedClient.VISITED.mapped(level.dimension(), writingLayer, tile.getChunkX(), tile.getChunkZ(), differs);
	}
}
