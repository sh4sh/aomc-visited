package io.github.sh4sh.aomcvisited;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ExportAreas {
	/** A rectangle of chunks, inclusive. */
	public record Box(int minX, int minZ, int maxX, int maxZ) {
		boolean overlaps(Box other) {
			return minX <= other.maxX && other.minX <= maxX && minZ <= other.maxZ && other.minZ <= maxZ;
		}

		Box union(Box other) {
			return new Box(Math.min(minX, other.minX), Math.min(minZ, other.minZ),
					Math.max(maxX, other.maxX), Math.max(maxZ, other.maxZ));
		}
	}

	private static final int MIN_FILL_DIVISOR = 3;
	private static final int SMALL_ENOUGH = 4;

	private ExportAreas() {
	}

	public static List<Box> plan(Iterable<int[]> chunks) {
		Map<Long, Box> regions = new HashMap<>();
		for (int[] chunk : chunks) {
			Box one = new Box(chunk[0], chunk[1], chunk[0], chunk[1]);
			regions.merge(key(chunk[0] >> 5, chunk[1] >> 5), one, Box::union);
		}
		List<Box> boxes = new ArrayList<>();
		for (List<Long> group : touchingGroups(regions.keySet())) {
			split(group, regions, boxes);
		}
		return mergeOverlapping(boxes);
	}

	private static List<List<Long>> touchingGroups(Set<Long> cells) {
		List<List<Long>> groups = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		for (long start : cells) {
			if (!seen.add(start)) {
				continue;
			}
			List<Long> group = new ArrayList<>();
			ArrayDeque<Long> queue = new ArrayDeque<>(List.of(start));
			while (!queue.isEmpty()) {
				long cell = queue.poll();
				group.add(cell);
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						long next = key(x(cell) + dx, z(cell) + dz);
						if (cells.contains(next) && seen.add(next)) {
							queue.add(next);
						}
					}
				}
			}
			groups.add(group);
		}
		return groups;
	}

	private static void split(List<Long> cells, Map<Long, Box> regions, List<Box> out) {
		int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (long cell : cells) {
			minX = Math.min(minX, x(cell));
			maxX = Math.max(maxX, x(cell));
			minZ = Math.min(minZ, z(cell));
			maxZ = Math.max(maxZ, z(cell));
		}
		int width = maxX - minX + 1, height = maxZ - minZ + 1;
		if (width * height <= SMALL_ENOUGH || cells.size() * MIN_FILL_DIVISOR >= width * height) {
			Box box = null;
			for (long cell : cells) {
				box = box == null ? regions.get(cell) : box.union(regions.get(cell));
			}
			out.add(box);
			return;
		}
		boolean alongX = width >= height;
		int middle = alongX ? minX + width / 2 : minZ + height / 2;
		List<Long> low = new ArrayList<>(), high = new ArrayList<>();
		for (long cell : cells) {
			((alongX ? x(cell) : z(cell)) < middle ? low : high).add(cell);
		}
		split(low, regions, out);
		split(high, regions, out);
	}

	/** This is a slow way to do things, but should be fine in practical terms. */
	private static List<Box> mergeOverlapping(List<Box> boxes) {
		List<Box> result = new ArrayList<>(boxes);
		boolean merged = true;
		while (merged) {
			merged = false;
			outer:
			for (int i = 0; i < result.size(); i++) {
				for (int j = i + 1; j < result.size(); j++) {
					if (result.get(i).overlaps(result.get(j))) {
						result.set(i, result.get(i).union(result.remove(j)));
						merged = true;
						break outer;
					}
				}
			}
		}
		return result;
	}

	private static long key(int x, int z) {
		return ((long) x << 32) | Integer.toUnsignedLong(z);
	}

	private static int x(long key) {
		return (int) (key >> 32);
	}

	private static int z(long key) {
		return (int) key;
	}
}
