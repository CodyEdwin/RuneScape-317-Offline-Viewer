# RS317 Standalone Client (Gradle)

This is the RuneScape 317 client source packaged as a Gradle project. It loads
the Jagex cache archive and runs as a standalone desktop application - no
browser, applet viewer, or login server required to start.

## Project layout

```
src/
  Main.java              # Standalone launcher (JFrame + cache init + client)
  OfflineWorldViewer.java# Experimental offline scene viewer (see below)
  client.java            # The 317 game client (applet core)
  RSApplet.java          # Applet/game loop, input, timing
  WorldController.java   # World scene building & software rendering
  ObjectManager.java     # Map/object parsing into the world scene
  Model.java             # 3D model + software rasteriser
  Texture.java           # Textured triangle / water rendering
  DrawingArea.java       # Software framebuffer (int[] pixels)
  StreamLoader.java      # Named archive parser
  Decompressor.java      # Jagex cache sector-chain decompressor
  OnDemandFetcher.java   # On-demand model/animation/map loading
  sign/signlink.java     # Jagex cache file access (./cache/)
  ... (remaining 317 classes)
build.gradle             # Gradle build (Java 25, no external deps)
settings.gradle
cache/                   # <- symlinked to ~/cache/ (contains main_file_cache.dat + *.idx*)
cache/README.md
compile.bat / run.bat    # Original build/run scripts (still work)
```

## How it works

**Cache loading** — `signlink` opens `./cache/main_file_cache.dat` and the five
`main_file_cache.idx*` files. `Decompressor` follows the 520-byte sector chains
to decompress files by (archive id, file id). `StreamLoader` turns an archive
into named entries (`getDataForName`). `OnDemandFetcher` pulls models,
animations and maps directly from the local cache.

**World scene building** — `ObjectManager` parses map bytes (ground via
`method180`, objects via `method190`, floors/walls via `method171`) into
`WorldController`, which maintains `groundArray[4][104][104]` and builds the
visible scene.

**Rendering** — `Model` renders textured/Gouraud-shaded triangles into
`DrawingArea.pixels[]`; `Texture` handles flat-shaded/textured floors and
water animation. `RSImageProducer` blits the pixel array to the AWT window.
The standalone `Main` hosts the applet in a `JFrame` and calls `client.init()`,
which starts the original `RSApplet.run()` loop (`startUp` → load cache →
`processGameLoop`/`processDrawing`).

## Build & run

Requires JDK 25 and Gradle.

```bash
gradle build          # compile + test
gradle run            # launch the standalone client (default args)
gradle run --args="10 0 highmem members 32"
java -jar build/libs/rs317-client-1.0.jar   # also works (no external deps)
```

`gradle run` uses default args `10 0 highmem members 32` (same as the original
`run.bat`).

## Important: cache files

The client needs a real RuneScape 317 cache. A cache was found at `~/cache/`,
so `./cache` is symlinked to it:

```bash
ln -s ~/cache cache
```

`signlink.findcachedir()` returns `"./cache/"`, so the symlink lets the original
client code open `main_file_cache.dat` / `main_file_cache.idx*` unchanged. See
`cache/README.md`.

Without the cache, `gradle run` prints an error and exits.

## World scene (login vs offline)

This is the original 317 client: after the cache loads it boots to the login
screen, and the 3D world scene is built once you connect to a login server
(the region list comes from the server). All of the cache loading, scene
building (`ObjectManager` + `WorldController`) and rendering
(`Model` + `Texture` + `DrawingArea`) is present and unchanged.

## Offline scene viewer (experimental)

`src/OfflineWorldViewer.java` opens the cache, unpacks all definitions and
textures, pre-loads all 14,927 models from archive 2, loads map regions from
the cache, and renders them with the client's own `ObjectManager` +
`WorldController` + `Model`/`Texture`/`DrawingArea` pipeline.

The `WorldController` is hardcoded to a 104×104 tile grid, so only one
64×64 region renders at a time. Regions are cached and auto-loaded as the
camera crosses region boundaries.

```bash
gradle runOfflineViewer   (see build.gradle)
or
java -cp build/classes/java/main OfflineWorldViewer
```

### Controls

| Key | Action |
|-----|--------|
| WASD | Move camera through the world |
| Shift+WASD | Sprint (faster movement) |
| Arrows | Rotate camera (yaw/pitch) |
| PgUp / PgDn | Raise/lower camera height |
| N / P | Next / previous region |
| R | Reset camera to region center |
| O | Toggle object count overlay |

The viewer auto-loads regions as you move across region boundaries and caches
them so moving back and forth is instant. Some regions have corrupt model data
or texture IDs outside the 0-49 range supported by the original Texture class -
those regions are automatically skipped.

### Known limitations

- Plain terrain tiles without floor textures don't render (engine limitation -
  only tiles with `aClass40_1312` or `aClass43_1311` are drawn)
- Some regions have texture IDs > 49 which the original Texture class doesn't
  support - those regions are skipped
- Corrupt model data in the cache causes some objects to be skipped

## Original scripts

`compile.bat` (`javac -cp . -d ../bin/ ./*.java` from `src/`) and `run.bat`
(`java client 10 0 highmem members 32` from `bin/`) still work alongside Gradle.