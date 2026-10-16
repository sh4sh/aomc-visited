package io.github.sh4sh.aomcvisited.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.sh4sh.aomcvisited.XaeroExport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.MapProcessor;

import java.nio.file.Path;

/**
 * Hooks Xaero's export task ({@code MapSaveLoad$1}) for {@code /visited export}: stops it opening a folder
 * after each area (we open the combined folder once at the end), and reports when the task has returned, so
 * the next area isn't started while it's still waiting (see {@code XaeroExport.TASK_END_FALLBACK_MS}). If a
 * Xaero update moves these, an extra folder window opens and areas start a second apart instead.
 */
@Mixin(targets = "xaero.map.file.MapSaveLoad$1", remap = false)
public abstract class XaeroExportTaskMixin {
	@Inject(method = "run", at = @At("RETURN"))
	private void aomcVisited$taskEnded(MapProcessor processor, CallbackInfo ci) {
		XaeroExport.taskEnded();
	}

	@WrapOperation(method = "lambda$run$0", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/Blaze3D;openPath(Ljava/nio/file/Path;)V"))
	private void aomcVisited$dontOpenFolder(Path path, Operation<Void> original) {
		if (!XaeroExport.active()) {
			original.call(path);
		}
	}
}
