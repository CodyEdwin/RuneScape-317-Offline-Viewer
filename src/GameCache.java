import sign.signlink;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.util.zip.GZIPInputStream;

/**
 * Owns the cache itself: opens the decompressors and loads the archives the
 * viewer needs (definitions, textures, models, map index). This is pure I/O -
 * it never touches the scene, the window or the camera, so it can be reloaded
 * or tested on its own.
 */
final class GameCache {

    /** Receives coarse progress while a bulk load runs (percent + message). */
    interface Progress {
        void report(int percent, String message);
    }

    // Archive indices into the cache.
    static final int ARCHIVE_CONFIG = 0; // configs, textures, map_index, ...
    static final int ARCHIVE_MODELS = 1; // model data (gzipped)
    static final int ARCHIVE_MAPS = 4;   // terrain + object maps (gzipped)

    private Decompressor[] decompressors;

    /** Opens the five cache decompressors. Throws if the cache is missing. */
    void open() throws Exception {
        signlink.startpriv(InetAddress.getByName("127.0.0.1"));
        if (signlink.cache_dat == null) {
            throw new IllegalStateException("cache_dat is null - place cache files in ./cache/");
        }
        Decompressor[] d = new Decompressor[5];
        for (int i = 0; i < 5; i++) {
            d[i] = new Decompressor(signlink.cache_dat, signlink.cache_idx[i], i + 1);
        }
        decompressors = d;
    }

    byte[] decompress(int archive, int fileId) {
        return decompressors[archive].decompress(fileId);
    }

    /** Unpacks every definition table from the config archive. */
    void unpackConfigs() {
        StreamLoader config = new StreamLoader(decompress(ARCHIVE_CONFIG, 2));
        ObjectDef.unpackConfig(config);
        ItemDef.unpackConfig(config);
        EntityDef.unpackConfig(config);
        Flo.unpackConfig(config);
        IDK.unpackConfig(config);
        SpotAnim.unpackConfig(config);
        Varp.unpackConfig(config);
        VarBit.unpackConfig(config);
        Animation.unpackConfig(config);
    }

    /** Loads and prepares the texture archive. */
    void loadTextures() {
        StreamLoader textures = new StreamLoader(decompress(ARCHIVE_CONFIG, 6));
        Texture.method368(textures);
        Texture.lowMem = false;
        ObjectManager.lowMem = false;
        Texture.method372(1.0D);
    }

    /**
     * Pre-loads every model from the model archive so object rendering never
     * stalls on disk. Reports progress 0..100 and returns how many loaded.
     */
    int preloadModels(Progress progress) {
        Model.method459(65536, null);
        int loaded = 0;
        for (int id = 0; id < 65536; id++) {
            if ((id & 2047) == 0 && progress != null) {
                progress.report(18 + (int) (52L * id / 65536),
                        "Loading models - " + (id * 100 / 65536) + "%");
            }
            byte[] data = decompress(ARCHIVE_MODELS, id);
            if (data == null) continue;
            // Cache entries are gzip-compressed; decompress before parsing.
            try {
                data = gunzip(data);
            } catch (Exception ignore) {
                // not gzipped - use the raw bytes
            }
            try {
                Model.method460(data, id);
                loaded++;
            } catch (Exception ignore) {
                // skip corrupt model data
            }
        }
        return loaded;
    }

    /** Raw bytes of a named entry in a config-archive file (e.g. map_index). */
    byte[] readNamed(int fileId, String name) {
        StreamLoader loader = new StreamLoader(decompress(ARCHIVE_CONFIG, fileId));
        return loader.getDataForName(name);
    }

    /** Cache entries are gzip-compressed; inflate them to raw bytes. */
    static byte[] gunzip(byte[] data) throws Exception {
        GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int read;
        while ((read = gz.read(buf, 0, buf.length)) != -1) {
            out.write(buf, 0, read);
        }
        gz.close();
        return out.toByteArray();
    }
}
