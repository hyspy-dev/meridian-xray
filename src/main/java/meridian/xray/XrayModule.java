package meridian.xray;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import meridian.api.event.EventPriority;
import meridian.api.event.PhaseChangedEvent;
import meridian.api.module.ModuleContext;
import meridian.api.module.ProxyModule;
import meridian.api.session.SessionPhase;
import meridian.api.settings.SettingsSpec;
import meridian.core.api.BlockView;
import meridian.core.api.ClientAssets;
import meridian.core.api.WorldState;
import org.slf4j.Logger;

/**
 * xray-2 — see through the ground, as a pure Layer-2 module on top of meridian-core.
 *
 * <p>The module owns its own pictures: the vanilla textures of the terrain families it reveals
 * are bundled in this jar under {@code /meridian/xray/textures/} (listed by {@code textures.index}).
 * Nothing is read from the client install and nothing is fetched from the server, so the proxy
 * runs the same standalone or launcher-driven, wherever the jar sits.
 *
 * <p>Two ways through the ground. {@link Mode#HIDE} stops drawing the targeted blocks.
 * {@link Mode#FRAME} keeps them, drawn with their own texture cut open in the middle: the bundled
 * original is cut to leave a {@link #BORDER}-pixel frame. The cut copies are handed to the client
 * while it loads a world (via {@link ClientAssets#provideAtConnect}), so they are indexed by then;
 * the actual re-point of a block type onto its copy — plus the see-through opacity and the glow —
 * is a per-id {@link WorldState#overrideBlockType} applied on the X-Ray toggle. Once a texture is
 * indexed, the client re-points to it from a mid-session update, so the toggle is fully live: off
 * leaves the blocks exactly as the server sent them (no frame in the shadows or the hand), on
 * turns them into lit see-through frames.
 *
 * <p>The module contract any other module builds on the same way: {@link ClientAssets#provideAtConnect}
 * delivers a texture for the load, and {@link WorldState#overrideBlockType} re-points / lights a
 * block per id (a small live update). A "glow ores" module is the light alone, no textures.
 */
public class XrayModule implements ProxyModule {
    /** Block-name prefixes the module reveals — the natural terrain ore grows in. */
    private static final Set<String> TARGET_PREFIXES =
            Set.of("Soil_", "Rock_", "Clay_", "Calcite", "Peachstone", "Chalk");
    private static final String RES = "/meridian/xray/textures/";
    /** The air block's catalog name ({@code BlockType.EMPTY_KEY}, id 0) — lighting it lifts the dark. */
    private static final String AIR = "Empty";
    /** Frame border width, in pixels — fixed at 4, which reads well on the 32px terrain textures. */
    private static final int BORDER = 4;

    // Warm light the targeted blocks emit, so the ground the frames reveal is not pitch dark.
    // Colour channels are 0..15 (the game's ColorLight is three 4-bit values); radius is the reach.
    private static final int GLOW_RED = 8;
    private static final int GLOW_GREEN = 7;
    private static final int GLOW_BLUE = 5;
    // Cool light the air emits when fullbright is on — a faint, even lift, not a warm tint.
    private static final int AIR_RED = 4;
    private static final int AIR_GREEN = 4;
    private static final int AIR_BLUE = 8;

    /** How a targeted block is made see-through. */
    public enum Mode { HIDE, FRAME }

    /** The bundled vanilla textures, loaded once per proxy run: name → PNG bytes. */
    private static final Map<String, byte[]> SOURCE = new ConcurrentHashMap<>();
    /** Cut copies, cached across connections: texture name → framed PNG (all at {@link #BORDER}). */
    private static final Map<String, byte[]> CUT_CACHE = new ConcurrentHashMap<>();

    /** Light reach (in blocks) for the terrain glow and the air glow — fixed at 1. */
    private static final int GLOW_RADIUS = 1;

    private volatile boolean xrayEnabled = false;
    private volatile Mode mode = Mode.HIDE;
    /** Fullbright: light the air itself, its own switch, independent of X-Ray. */
    private volatile boolean airGlow = false;

    /** Original texture path → the name its framed copy is registered under for connect time. */
    private final Map<String, String> framedNames = new ConcurrentHashMap<>();
    private volatile boolean framesReady = false;

    private WorldState world;
    private ClientAssets assets;
    private Logger log;

    @Override
    public void onEnable(ModuleContext ctx) {
        this.log = ctx.getLogger();
        this.world = ctx.services().require(WorldState.class);
        this.assets = ctx.services().require(ClientAssets.class);
        loadBundledTextures();

        // (Re)apply once the world's block types are loaded (and again at JOINING — WORLD_LOADED
        // rises on the very packet the connect-time files ride, so JOINING confirms them).
        ctx.events().subscribe(PhaseChangedEvent.class, EventPriority.NORMAL, e -> {
            if (e.to() == SessionPhase.WORLD_LOADED || e.to() == SessionPhase.JOINING) {
                refresh();
            }
        });

        // Declarative settings — rendered and persisted by the proxy. Each callback fires with the
        // persisted value at load and on every edit. The X-Ray toggle is session-only (starts off);
        // how it looks is remembered, and must be, since the framed textures ship at world load.
        ctx.registerSettings(SettingsSpec.builder()
                .section("X-Ray", SettingsSpec.builder()
                        .bool("xray", "Enable X-Ray", false, v -> {
                            xrayEnabled = v;
                            refresh();
                        })
                        .enum_("mode", "Mode", Mode.class, Mode.FRAME, v -> {
                            mode = v;
                            refresh();
                        })
                        .bool("airGlow", "Glow air (fullbright)", false, v -> {
                            airGlow = v;
                            refresh();
                        })
                        .persistent("mode", "airGlow")
                        .build())
                .build());

        log.info("XrayModule (v2) enabled — {} bundled terrain texture(s)", SOURCE.size());
    }

    /**
     * Recomputes the desired override for every block type. Idempotent: it can be called on any
     * toggle or phase change and converges to the correct state.
     */
    private void refresh() {
        if (world == null) return;
        // Only the delivery is load-time (the copies must be indexed before they can be referenced);
        // the re-point, opacity and lights are all per-id overrides that emit a small mid-session
        // update and take effect live — texture, occlusion and light all recompute for a pushed id.
        syncFrames();
        boolean frame = xrayEnabled && mode == Mode.FRAME;
        int overridden = 0;
        for (BlockView bv : world.allBlockTypes()) {
            boolean xray = xrayEnabled && isTarget(bv.name());
            // Air is its own switch (fullbright), independent of X-Ray.
            boolean lightAir = airGlow && AIR.equals(bv.name());
            if (!xray && !lightAir) {
                world.clearOverride(bv.id());
                continue;
            }
            boolean asFrame = xray && frame && isFramedOnClient(bv);
            world.overrideBlockType(bv.id(), b -> {
                BlockView r = b;
                // Bind the framed texture here, on the toggle — the copies are delivered+indexed at
                // load, so this only re-points an already-indexed name, which the client does live.
                // Transparent is the opacity that actually renders the holes for these blocks.
                if (xray) {
                    r = asFrame
                            ? r.withTextures(framedNames::get).withOpacity(BlockView.Opacity.TRANSPARENT)
                            : r.withVisible(false);
                    r = r.withLight(GLOW_RADIUS, GLOW_RED, GLOW_GREEN, GLOW_BLUE);
                }
                if (lightAir) r = r.withLight(GLOW_RADIUS, AIR_RED, AIR_GREEN, AIR_BLUE);
                return r;
            });
            overridden++;
        }
        log.info("X-Ray={} mode={} airGlow={} — {} block(s) overridden",
                xrayEnabled, mode, airGlow, overridden);
    }

    /**
     * A block can be drawn as a frame when every texture it uses reached this client as a framed
     * copy during its load. Otherwise (a model block, a texture the module does not bundle, a
     * world joined before Frame was chosen) it is hidden instead, so the ground is never opaque.
     */
    private boolean isFramedOnClient(BlockView b) {
        List<String> textures = b.textures();
        if (textures.isEmpty()) return false;
        for (String texture : textures) {
            String name = framedNames.get(texture);
            if (name == null || !assets.deliveredAtConnect(name)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Frames: bundled texture → copy with a window → the client, at connect, under a new name
    // ------------------------------------------------------------------

    /**
     * Only delivers the framed textures for the load (so the client indexes them); the actual
     * re-point onto the block types is a per-id override in {@link #refresh()}, applied on the
     * X-Ray toggle. TEST: this is the live-rebind experiment — if the client re-points an
     * already-indexed texture from a mid-session update, X-Ray off leaves the blocks truly normal
     * (no ghost frame in the shadows or the hand); if it does not, we fall back to load-time.
     */
    private void syncFrames() {
        if (mode != Mode.FRAME) {
            if (!framedNames.isEmpty()) {
                framedNames.values().forEach(assets::withdrawAtConnect);
                framedNames.clear();
                framesReady = false;
                log.info("X-Ray: framed textures withdrawn from the next connect");
            }
            return;
        }
        if (!framesReady) {
            int added = 0;
            for (Map.Entry<String, byte[]> e : SOURCE.entrySet()) {
                String texture = e.getKey();
                if (framedNames.containsKey(texture)) continue;
                byte[] copy = frameOf(texture, e.getValue());
                if (copy == null) continue;
                String name = framedName(texture);
                assets.provideAtConnect(name, copy);
                framedNames.put(texture, name);
                added++;
            }
            framesReady = true;
            log.info("X-Ray: {} framed texture(s) delivered for the load (rebind is on the toggle)", added);
        }
    }

    /** {@code BlockTextures/Soil_Dirt.png} → {@code BlockTextures/Soil_Dirt_xray4.png}. */
    private static String framedName(String texture) {
        String suffix = "_xray" + BORDER + ".png";
        return texture.endsWith(".png")
                ? texture.substring(0, texture.length() - 4) + suffix
                : texture + suffix;
    }

    /** The framed copy of one bundled texture, cached, or null if it cannot be cut. */
    private byte[] frameOf(String texture, byte[] original) {
        byte[] cached = CUT_CACHE.get(texture);
        if (cached != null) return cached;
        try {
            byte[] copy = cutWindow(original, BORDER);
            CUT_CACHE.put(texture, copy);
            return copy;
        } catch (IOException | RuntimeException e) {
            log.warn("X-Ray: cannot cut a window into {}: {}", texture, e.toString());
            return null;
        }
    }

    /**
     * The picture with its middle made fully transparent, leaving {@code border} opaque pixels on
     * each side. Colour is kept under the hole, so the copy drawn solid still looks like the
     * original. A picture too small for the border is returned unchanged.
     */
    static byte[] cutWindow(byte[] png, int border) throws IOException {
        BufferedImage in = ImageIO.read(new ByteArrayInputStream(png));
        if (in == null) {
            throw new IOException("not an image");
        }
        int w = in.getWidth();
        int h = in.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = in.getRGB(x, y);
                boolean inside = x >= border && x < w - border && y >= border && y < h - border;
                out.setRGB(x, y, inside ? argb & 0x00FFFFFF : argb);
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(out, "png", bytes)) {
            throw new IOException("no PNG writer");
        }
        return bytes.toByteArray();
    }

    /** Reads the bundled terrain textures from the jar once, into {@link #SOURCE}. */
    private void loadBundledTextures() {
        if (!SOURCE.isEmpty()) return;
        synchronized (SOURCE) {
            if (!SOURCE.isEmpty()) return;
            List<String> names;
            try (InputStream index = getClass().getResourceAsStream(RES + "textures.index")) {
                if (index == null) {
                    log.error("X-Ray: bundled textures.index missing — Frame mode will hide blocks");
                    return;
                }
                names = new BufferedReader(new InputStreamReader(index, StandardCharsets.UTF_8))
                        .lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
            } catch (IOException e) {
                log.error("X-Ray: cannot read textures.index: {}", e.toString());
                return;
            }
            int loaded = 0;
            for (String name : names) {
                try (InputStream in = getClass().getResourceAsStream(RES + name)) {
                    if (in != null) {
                        SOURCE.put("BlockTextures/" + stripDir(name), in.readAllBytes());
                        loaded++;
                    }
                } catch (IOException ignored) {
                    // a missing bundled file just means that block stays hidden in Frame mode
                }
            }
            log.info("X-Ray: loaded {} of {} bundled texture(s)", loaded, names.size());
        }
    }

    /** {@code textures.index} lists paths as {@code BlockTextures/Foo.png}; keep the leaf. */
    private static String stripDir(String indexPath) {
        int slash = indexPath.lastIndexOf('/');
        return slash < 0 ? indexPath : indexPath.substring(slash + 1);
    }

    private static boolean isTarget(String name) {
        if (name == null) return false;
        for (String prefix : TARGET_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }
}
