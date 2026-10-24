/**
 * One map region discovered in the cache's map_index.
 *
 * A region id packs the world region coordinates as (x << 8) + y; the ground
 * and object files are the cache archive-4 entries that hold its terrain and
 * scenery.
 */
final class MapRegion {
    final int rx;
    final int ry;
    final int regionId;
    final int groundFileId;
    final int objectFileId;

    MapRegion(int rx, int ry, int regionId, int groundFileId, int objectFileId) {
        this.rx = rx;
        this.ry = ry;
        this.regionId = regionId;
        this.groundFileId = groundFileId;
        this.objectFileId = objectFileId;
    }

    /** World tile coordinate of the region's low corner. */
    int baseTileX() { return rx * 64; }
    int baseTileY() { return ry * 64; }

    /** Centre tile of the region, used as the camera spawn/jump target. */
    int centerTileX() { return rx * 64 + 32; }
    int centerTileY() { return ry * 64 + 32; }

    @Override
    public String toString() {
        return "(" + rx + "," + ry + ")";
    }
}
