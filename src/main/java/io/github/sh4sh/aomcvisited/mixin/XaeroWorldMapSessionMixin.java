package io.github.sh4sh.aomcvisited.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.sh4sh.aomcvisited.VisitedHighlighter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import xaero.map.WorldMapSession;
import xaero.map.highlight.HighlighterRegistry;

/** Xaero locks its highlighter registry with {@code end()} and has no public hook. */
@Mixin(value = WorldMapSession.class, remap = false)
public abstract class XaeroWorldMapSessionMixin {
	@WrapOperation(method = "init", at = @At(value = "INVOKE", target = "Lxaero/map/highlight/HighlighterRegistry;end()V"))
	private void aomcVisited$registerHighlighter(HighlighterRegistry registry, Operation<Void> original) {
		VisitedHighlighter.register(registry);
		original.call(registry);
	}
}
