package io.github.sh4sh.aomcvisited.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.sh4sh.aomcvisited.XaeroExport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.nio.file.Path;

/**
 * Stops Xaero opening a folder after each area of {@code /visited export}; we open the combined folder
 * once at the end. Targets the lambda in Xaero's export task ({@code MapSaveLoad$1}), so a Xaero update may
 * move it: then only an extra folder window opens.
 */
@Mixin(targets = "xaero.map.file.MapSaveLoad$1", remap = false)
public abstract class XaeroExportTaskMixin {
	@WrapOperation(method = "lambda$run$0", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/Blaze3D;openPath(Ljava/nio/file/Path;)V"))
	private void aomcVisited$dontOpenFolder(Path path, Operation<Void> original) {
		if (!XaeroExport.running) {
			original.call(path);
		}
	}
}
