import sign.signlink;

import javax.swing.SwingUtilities;
import java.io.File;

/**
 * Standalone launcher for the RuneScape 317 client.
 *
 * The original source is an {@link java.applet.Applet}. This class boots it as a
 * normal desktop application using the project's own {@link GUI} class - the same
 * entry point the original {@link client#main(String[])} / {@code run.bat} uses:
 *
 *   1. Verifies the Jagex cache archive is present in {@code ./cache/} (symlinked
 *      to {@code ~/cache/}).
 *   2. Sets {@code signlink.storeid} and launches {@link GUI} on the EDT.
 *   3. {@link GUI} constructs the {@link client} applet, initialises the cache via
 *      {@link signlink#startpriv}, creates a {@link JFrame}, adds the applet and
 *      calls {@link client#init()}, which starts the original game-loop thread
 *      ({@link RSApplet#run()}) that loads the cache, builds the world scene and
 *      renders with the original software rasteriser.
 *
 * No browser, applet viewer or login server is required to start the client.
 * A real RuneScape 317 cache must be placed in {@code cache/} (see cache/README.md).
 *
 * Run with:
 *   ./gradlew run
 * or
 *   gradle run
 *
 * Default connection parameters mirror the original run.bat:
 *   nodeId=10 portOffset=0 memory=highmem type=members storeId=32
 */
public class Main {

    public static void main(String[] args) {
        int nodeId = 10;
        int portOffset = 0;
        String memory = "highmem";
        String type = "members";
        int storeId = 32;

        if (args.length == 5) {
            try {
                nodeId = Integer.parseInt(args[0]);
                portOffset = Integer.parseInt(args[1]);
                memory = args[2];
                type = args[3];
                storeId = Integer.parseInt(args[4]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid arguments. Usage:");
                System.err.println("  Main <nodeId> <portOffset> <lowmem|highmem> <free|members> <storeId>");
                return;
            }
        } else if (args.length != 0) {
            System.err.println("Usage: Main <nodeId> <portOffset> <lowmem|highmem> <free|members> <storeId>");
            return;
        }

        File cacheDir = new File("cache");
        File cacheDat = new File(cacheDir, "main_file_cache.dat");
        boolean hasIdx = true;
        for (int i = 0; i < 5; i++) {
            if (!new File(cacheDir, "main_file_cache.idx" + i).exists()) {
                hasIdx = false;
            }
        }

        if (!cacheDat.exists() || !hasIdx) {
            System.out.println("RuneScape 317 - Standalone Client");
            System.out.println("=================================");
            System.out.println("ERROR: Jagex cache archive not found.");
            System.out.println();
            System.out.println("Place the cache files into a folder named 'cache/' next to this project:");
            System.out.println("  " + new File(cacheDir, "main_file_cache.dat").getAbsolutePath());
            System.out.println("  " + new File(cacheDir, "main_file_cache.idx0").getAbsolutePath());
            System.out.println("  ... main_file_cache.idx1 .. main_file_cache.idx4");
            System.out.println();
            System.out.println("Then run:  gradle run");
            System.out.println("(See cache/README.md for details.)");
            return;
        }

        System.out.println("Jagex cache found at ./cache/ (-> ~/cache/)");

        // storeid is set before GUI's startpriv call, exactly like run.bat.
        signlink.storeid = storeId;

        System.out.println("Starting RS317 client (nodeId=" + nodeId + ", portOffset=" + portOffset
                + ", memory=" + memory + ", type=" + type + ", storeId=" + storeId + ")...");

        // GUI extends client and reproduces the proven run.bat launch path:
        // constructor sets server="127.0.0.1", startpriv opens the cache,
        // initializeClientFrame builds the JFrame, init() starts the game loop.
        // Launch synchronously (as client.main() / run.bat do) so the JVM stays
        // alive for the applet's game-loop thread.
        String[] launchArgs = {
            String.valueOf(nodeId),
            String.valueOf(portOffset),
            memory,
            type,
            String.valueOf(storeId)
        };
        new GUI(launchArgs);
    }
}
