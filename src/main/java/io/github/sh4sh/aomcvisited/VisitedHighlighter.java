package io.github.sh4sh.aomcvisited;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.gui.ExportScreen;
import xaero.map.highlight.ChunkHighlighter;
import xaero.map.highlight.HighlighterRegistry;
import xaero.map.world.MapDimension;

import java.util.List;

/**
 * Tints the chunks the next export will keep on Xaero's World Map (and its minimap), with a brighter edge
 * where the record ends. Registered by {@code XaeroWorldMapSessionMixin}; only load this class when
 * {@code xaeroworldmap} is installed.
 *
 * <p>Xaero caches highlights per 512-block region by {@link #calculateRegionHash}, so whenever what we'd
 * show changes we clear that cache ({@link #refreshRegion}, {@link #refreshAll}).
 */
public final class VisitedHighlighter extends ChunkHighlighter {
	/** Colours are 0xRRGGBBAA, as in Xaero's claims highlighter. */
	private static final int FILL = 0x40FF4040;
	private static final int EDGE = 0x40FF40C0;
	private static final Component TOOLTIP = Component.literal("Recorded by AOMC Visited: will be exported");

	/**
	 * While an export screen is open (ours or Xaero's own) nothing is shown: with "Include Active
	 * Highlights" on, Xaero would otherwise paint our tint into the exported tiles, inside exactly the
	 * chunks the cleaner keeps.
	 */
	private static volatile boolean exportScreenOpen;

	private VisitedHighlighter() {
		super(false);
	}

	public static void register(HighlighterRegistry registry) {
		registry.register(new VisitedHighlighter());
	}

	/** Hides the highlight while an export screen is open. Call once at startup. */
	static void registerEvents() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof ExportScreen) {
				exportScreenOpen = true;
				refreshAll();
				ScreenEvents.remove(screen).register(removed -> {
					exportScreenOpen = false;
					refreshAll();
				});
			}
		});
	}

	/** Redraws every highlight, e.g. after {@code /visited show on|off}. */
	static void refreshAll() {
		MapProcessor processor = processor();
		if (processor != null && processor.getMapWorld() != null) {
			processor.getMapWorld().clearAllCachedHighlightHashes();
		}
	}

	/** Redraws one region's highlight after a chunk in it was recorded. */
	public static void refreshRegion(MapProcessor processor, ResourceKey<Level> dimension, int regionX, int regionZ) {
		MapDimension mapDimension = processor.getMapWorld() == null ? null : processor.getMapWorld().getDimension(dimension);
		if (mapDimension != null) {
			mapDimension.getHighlightHandler().clearCachedHash(regionX, regionZ);
		}
	}

	private static MapProcessor processor() {
		WorldMapSession session = WorldMapSession.getCurrentSession();
		return session == null ? null : session.getMapProcessor();
	}

	private static boolean active() {
		return !exportScreenOpen && AomcVisitedClient.SETTINGS.showOnMap();
	}

	/** Exports are made with cave mode off, so only the normal map's record is shown. */
	private static String key(ResourceKey<Level> dimension) {
		return VisitedChunks.recordKey(dimension, VisitedChunks.SURFACE_LAYER);
	}

	private static boolean recorded(ResourceKey<Level> dimension, int x, int z) {
		return AomcVisitedClient.VISITED.contains(key(dimension), x, z);
	}

	@Override
	public int calculateRegionHash(ResourceKey<Level> dimension, int regionX, int regionZ) {
		// Only ever grows while shown, so the count is enough to tell Xaero something changed.
		return active() ? 1 + AomcVisitedClient.VISITED.regionCount(key(dimension), regionX, regionZ) : 0;
	}

	@Override
	public boolean regionHasHighlights(ResourceKey<Level> dimension, int regionX, int regionZ) {
		return active() && AomcVisitedClient.VISITED.regionCount(key(dimension), regionX, regionZ) > 0;
	}

	@Override
	public boolean chunkIsHighlit(ResourceKey<Level> dimension, int x, int z) {
		return active() && recorded(dimension, x, z);
	}

	/** Centre, then the top (north), right (east), bottom (south) and left (west) edges. */
	@Override
	protected int[] getColors(ResourceKey<Level> dimension, int x, int z) {
		if (!chunkIsHighlit(dimension, x, z)) {
			return null;
		}
		return new int[] {
				FILL,
				recorded(dimension, x, z - 1) ? FILL : EDGE,
				recorded(dimension, x + 1, z) ? FILL : EDGE,
				recorded(dimension, x, z + 1) ? FILL : EDGE,
				recorded(dimension, x - 1, z) ? FILL : EDGE,
		};
	}

	@Override
	public Component getChunkHighlightSubtleTooltip(ResourceKey<Level> dimension, int x, int z) {
		return chunkIsHighlit(dimension, x, z) ? TOOLTIP : null;
	}

	@Override
	public Component getChunkHighlightBluntTooltip(ResourceKey<Level> dimension, int x, int z) {
		return null;
	}

	@Override
	public void addMinimapBlockHighlightTooltips(List<Component> list, ResourceKey<Level> dimension, int blockX, int blockZ, int width) {
	}
}
