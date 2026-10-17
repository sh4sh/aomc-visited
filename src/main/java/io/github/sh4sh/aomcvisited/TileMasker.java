package io.github.sh4sh.aomcvisited;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class TileMasker {
	public interface Recorded {
		boolean contains(int chunkX, int chunkZ);
	}

	public record Result(int tiles, int tilesDeleted, int chunksKept, int chunksBlackedOut) {
		Result plus(Result other) {
			return new Result(tiles + other.tiles, tilesDeleted + other.tilesDeleted, chunksKept + other.chunksKept,
					chunksBlackedOut + other.chunksBlackedOut);
		}
	}

	private static final Pattern TILE = Pattern.compile("\\d+_\\d+_x(-?\\d+)_z(-?\\d+)\\.png");
	private static final int BLACK = 0xFF000000;

	private TileMasker() {
	}

	public static Result mask(Path folder, Recorded recorded) throws IOException {
		List<Path> files;
		try (Stream<Path> list = Files.list(folder)) {
			files = list.filter(p -> TILE.matcher(p.getFileName().toString()).matches()).sorted().toList();
		}
		Result total = new Result(0, 0, 0, 0);
		for (Path file : files) {
			Matcher m = TILE.matcher(file.getFileName().toString());
			m.matches();
			total = total.plus(maskImage(file, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), recorded));
		}
		return total;
	}

	public static Result maskImage(Path file, int blockX, int blockZ, Recorded recorded) throws IOException {
		if (Math.floorMod(blockX, 16) != 0 || Math.floorMod(blockZ, 16) != 0) {
			throw new IOException("Image is not chunk-aligned, refusing to guess: " + file);
		}
		BufferedImage image = ImageIO.read(file.toFile());
		if (image == null) {
			throw new IOException("Not a readable image: " + file);
		}
		int width = image.getWidth(), height = image.getHeight();
		int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
		int kept = 0, blackedOut = 0;
		for (int cellZ = 0; cellZ * 16 < height; cellZ++) {
			for (int cellX = 0; cellX * 16 < width; cellX++) {
				int x0 = cellX * 16, z0 = cellZ * 16;
				int x1 = Math.min(x0 + 16, width), z1 = Math.min(z0 + 16, height);
				boolean terrain = hasTerrain(pixels, width, x0, z0, x1, z1);
				if (recorded.contains(Math.floorDiv(blockX, 16) + cellX, Math.floorDiv(blockZ, 16) + cellZ)) {
					if (terrain) {
						kept++;
					}
				} else if (terrain) {
					blackedOut++;
					for (int z = z0; z < z1; z++) {
						Arrays.fill(pixels, z * width + x0, z * width + x1, BLACK);
					}
				}
			}
		}
		if (kept == 0) {
			Files.delete(file);
			return new Result(1, 1, 0, blackedOut);
		}
		if (blackedOut > 0) {
			image.setRGB(0, 0, width, height, pixels, 0, width);
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			if (!ImageIO.write(image, "png", tmp.toFile())) {
				throw new IOException("No PNG writer for " + file);
			}
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		return new Result(1, 0, kept, blackedOut);
	}

	private static boolean hasTerrain(int[] pixels, int width, int x0, int z0, int x1, int z1) {
		for (int z = z0; z < z1; z++) {
			for (int x = x0; x < x1; x++) {
				if ((pixels[z * width + x] & 0xFFFFFF) != 0) {
					return true;
				}
			}
		}
		return false;
	}
}
