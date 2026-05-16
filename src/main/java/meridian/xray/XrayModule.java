package meridian.xray;

import java.util.Set;
import meridian.api.event.EventPriority;
import meridian.api.event.PhaseChangedEvent;
import meridian.api.module.ModuleContext;
import meridian.api.module.ProxyModule;
import meridian.api.session.SessionPhase;
import meridian.api.settings.SettingsSpec;
import meridian.core.api.BlockView;
import meridian.core.api.WorldState;
import org.slf4j.Logger;

/**
 * xray-2 — X-Ray and Night Vision as a pure Layer-2 module on top of
 * meridian-core.
 *
 * <p>No packet handler, no {@code static} caches, no manual (de)serialisation,
 * no Swing, and not a single import from {@code meridian.protocol}. The UI is a
 * declarative {@link SettingsSpec}; the proxy renders and persists it.
 */
public class XrayModule implements ProxyModule {
    private static final Set<String> XRAY_PREFIXES = Set.of("Soil_", "Rock_");

    // Night-vision glow applied to every block (radius + dim white light).
    private static final int NV_RADIUS = 1;
    private static final int NV_CHANNEL = 50;

    private volatile boolean xrayEnabled = false;
    private volatile boolean nightVisionEnabled = false;
    private WorldState world;
    private Logger log;

    @Override
    public void onEnable(ModuleContext ctx) {
        this.log = ctx.getLogger();
        this.world = ctx.services().require(WorldState.class);

        // (Re)apply once the world's block types are loaded.
        ctx.events().subscribe(PhaseChangedEvent.class, EventPriority.NORMAL, e -> {
            if (e.to() == SessionPhase.WORLD_LOADED) {
                refresh();
            }
        });

        // Declarative settings — rendered and persisted by the proxy. Each
        // callback fires with the persisted value at startup and on every edit.
        ctx.registerSettings(SettingsSpec.builder()
                .section("X-Ray", SettingsSpec.builder()
                        .bool("xray", "Enable X-Ray", false, v -> {
                            xrayEnabled = v;
                            refresh();
                        })
                        .bool("nightVision", "Enable Night Vision", false, v -> {
                            nightVisionEnabled = v;
                            refresh();
                        })
                        .build())
                // Session-only — X-Ray and Night Vision always start off.
                .ephemeral()
                .build());

        log.info("XrayModule (v2) enabled — backed by meridian-core WorldState");
    }

    /**
     * Recomputes the desired override for every block type. Idempotent: it can
     * be called on any toggle or phase change and converges to the correct state.
     */
    private void refresh() {
        if (world == null) return;
        int overridden = 0;
        for (BlockView bv : world.allBlockTypes()) {
            boolean xray = xrayEnabled && isXrayTarget(bv.name());
            boolean nv = nightVisionEnabled;
            if (!xray && !nv) {
                world.clearOverride(bv.id());
                continue;
            }
            world.overrideBlockType(bv.id(), b -> {
                BlockView r = b;
                if (xray) r = r.withVisible(false);
                if (nv) r = r.withLight(NV_RADIUS, NV_CHANNEL, NV_CHANNEL, NV_CHANNEL);
                return r;
            });
            overridden++;
        }
        log.info("X-Ray={} NightVision={} — {} block type(s) overridden",
                xrayEnabled, nightVisionEnabled, overridden);
    }

    private static boolean isXrayTarget(String name) {
        if (name == null) return false;
        for (String prefix : XRAY_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }
}
