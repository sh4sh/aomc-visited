package io.github.sh4sh.aomcvisited.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.sh4sh.aomcvisited.XaeroExport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import xaero.lib.common.config.option.ConfigOption;
import xaero.lib.common.config.single.SingleConfigManager;
import xaero.map.config.primary.option.WorldMapPrimaryClientConfigOptions;
import xaero.map.file.export.PNGExporter;

/**
 * Hands Xaero's exporter the options {@code /visited export} needs while it runs, instead of changing the
 * player's settings: Multiple Unscaled Images on (so the export can be cleaned chunk by chunk), Nighttime
 * off, Include Active Highlights off (our tint must never be uploaded). Lighting stays the player's.
 */
@Mixin(value = PNGExporter.class, remap = false)
public abstract class XaeroPNGExporterMixin {
	@WrapOperation(method = "export", at = @At(value = "INVOKE", target = "Lxaero/lib/common/config/single/SingleConfigManager;getEffective(Lxaero/lib/common/config/option/ConfigOption;)Ljava/lang/Object;"))
	private Object aomcVisited$forceOptions(SingleConfigManager manager, ConfigOption<?> option, Operation<Object> original) {
		if (XaeroExport.active()) {
			if (option == WorldMapPrimaryClientConfigOptions.EXPORT_MULTIPLE_IMAGES) {
				return Boolean.TRUE;
			}
			if (option == WorldMapPrimaryClientConfigOptions.NIGHT_EXPORT
					|| option == WorldMapPrimaryClientConfigOptions.EXPORT_HIGHLIGHTS) {
				return Boolean.FALSE;
			}
		}
		return original.call(manager, option);
	}
}
