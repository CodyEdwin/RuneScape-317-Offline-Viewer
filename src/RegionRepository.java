import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The catalogue of map regions found in the cache's map_index, plus a small
 * bounded cache of their decompressed bytes.
 *
 * Region ids pack the world region coordinates as (x &lt;&lt; 8) + y (confirmed
 * by OnDemandFetcher.method562); the ground and object file ids are the
 * archive-4 entries that hold each region's terrain and scenery.
 */
final class RegionRepository {

    private final GameCache cache;
    private final Map<String, MapRegion> byKey = new HashMap<>();
    private final List<MapRegion> list = new ArrayList<>();
    // Gunzipped map part bytes, keyed by cache file id (bounded).
    private final Map<Integer, byte[]> bytesCache = new HashMap<>();

    RegionRepository(GameCache cache) {
        this.cache = cache;
    }

    /** Reads map_index and builds the region catalogue, sorted by (ry, rx). */
    void load() throws Exception {
        byte[] mapIndex = cache.readNamed(5, "map_index");
        if (mapIndex == null || mapIndex.length < 7) {
            throw new IllegalStateException("map_index not found in cache");
        }
        int count = mapIndex.length / 7;
        Stream stream = new Stream(mapIndex);
        for (int i = 0; i < count; i++) {
            int regionId = stream.readUnsignedWord();
            int groundFile = stream.readUnsignedWord();
            int objectFile = stream.readUnsignedWord();
            stream.readUnsignedByte();
            int rx = regionId >> 8;   // world region X
            int ry = regionId & 0xff; // world region Y
            MapRegion region = new MapRegion(rx, ry, regionId, groundFile, objectFile);
            byKey.put(rx + "," + ry, region);
            list.add(region);
        }
        list.sort((a, b) -> a.ry != b.ry ? Integer.compare(a.ry, b.ry) : Integer.compare(a.rx, b.rx));
    }

    MapRegion get(int rx, int ry) {
        return byKey.get(rx + "," + ry);
    }

    List<MapRegion> all() {
        return list;
    }

    MapRegion byIndex(int index) {
        return list.get(index);
    }

    /** Fractional-safe lookup for navigation; returns the index or -1. */
    int indexOf(MapRegion region) {
        return list.indexOf(region);
    }

    int size() {
        return list.size();
    }

    boolean isEmpty() {
        return list.isEmpty();
    }

    /** Decompress + gunzip a map cache file, with a small bounded cache. */
    byte[] bytes(int fileId) {
        byte[] cached = bytesCache.get(fileId);
        if (cached != null) return cached;
        byte[] raw;
        try {
            raw = cache.decompress(GameCache.ARCHIVE_MAPS, fileId);
        } catch (Exception e) {
            return null;
        }
        if (raw == null) return null;
        try {
            raw = GameCache.gunzip(raw);
        } catch (Exception ignore) {
            // not gzipped - use the raw bytes
        }
        if (bytesCache.size() > 256) bytesCache.clear();
        bytesCache.put(fileId, raw);
        return raw;
    }
}
