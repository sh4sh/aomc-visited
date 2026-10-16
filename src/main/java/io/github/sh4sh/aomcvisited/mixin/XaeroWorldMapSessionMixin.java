package io.github.sh4sh.aomcvisited.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.sh4sh.aomcvisited.VisitedHighlighter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import xaero.map.WorldMapSession;
import xaero.map.highlight.HighlighterRegistry;

/**
 * Adds our map highlight. Xaero builds a highlighter registry for each world session, lets supported mods
 * register (Open Parties and Claims), then locks it with {@code end()}; there's no public hook, so we
 * register just before that.
 */
@Mixin(value = WorldMapSession.class, remap = false)
public abstract class XaeroWorldMapSessionMixin {
	@WrapOperation(method = "init", at = @At(value = "INVOKE", target = "Lxaero/map/highlight/HighlighterRegistry;end()V"))
	private void aomcVisited$registerHighlighter(HighlighterRegistry registry, Operation<Void> original) {
		VisitedHighlighter.register(registry);
		original.call(registry);
	}
}
