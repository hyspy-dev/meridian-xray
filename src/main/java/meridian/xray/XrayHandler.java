package meridian.xray;

import meridian.protocol.BlockType;
import meridian.protocol.ColorLight;
import meridian.protocol.DrawType;
import meridian.protocol.Packet;
import meridian.protocol.UpdateType;
import meridian.protocol.io.PacketIO;
import meridian.protocol.io.PacketStatsRecorder;
import meridian.protocol.packets.assets.UpdateBlockTypes;
import meridian.proxy.core.Direction;
import meridian.proxy.core.ProxySession;
import meridian.proxy.handler.PacketHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class XrayHandler implements PacketHandler {
    private static final Logger log = LoggerFactory.getLogger(XrayHandler.class);

    private static final byte NV_RADIUS = 1;
    private static final byte NV_R = (byte) 50;
    private static final byte NV_G = (byte) 50;
    private static final byte NV_B = (byte) 50;
    private static final ColorLight NV_LIGHT = new ColorLight(NV_RADIUS, NV_R, NV_G, NV_B);

    private static final Set<String> XRAY_TARGET_PREFIXES = Set.of("Soil_", "Rock_");

    private static final Map<Integer, BlockType> originalBlockTypes = new ConcurrentHashMap<>();
    private static final Set<Integer> xrayTargetIds = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> nightVisionDirtyIds = ConcurrentHashMap.newKeySet();
    private static volatile int maxBlockId = 0;
    private static volatile ProxySession currentSession = null;
    private static volatile boolean xrayEnabled = false;
    private static volatile boolean nightVisionEnabled = false;

    private final Direction direction;

    public XrayHandler(Direction direction) {
        this.direction = direction;
    }

    public static boolean isXrayEnabled() { return xrayEnabled; }
    public static boolean isNightVisionEnabled() { return nightVisionEnabled; }

    public static void setXrayEnabled(boolean enabled) {
        xrayEnabled = enabled;
        resyncSession("X-Ray", enabled);
    }

    public static void setNightVisionEnabled(boolean enabled) {
        nightVisionEnabled = enabled;
        resyncSession("Night Vision", enabled);
    }

    private static void resyncSession(String what, boolean enabled) {
        ProxySession session = currentSession;
        if (session == null) return;
        log.info("{} toggled to {}. Updating current session.", what, enabled);
        try {
            sendBlockUpdate(session);
        } catch (Exception e) {
            log.error("Failed to update session: {}", e.toString());
        }
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        currentSession = session;
        if (direction != Direction.S2C) return Action.FORWARD;
        if (!(packet instanceof UpdateBlockTypes update)) return Action.FORWARD;
        if (update.blockTypes == null) return Action.FORWARD;

        log.info("Received UpdateBlockTypes (type={}, {} entries, maxId={})",
                update.type, update.blockTypes.size(), update.maxId);

        for (Map.Entry<Integer, BlockType> entry : update.blockTypes.entrySet()) {
            int id = entry.getKey();
            BlockType bt = entry.getValue();
            originalBlockTypes.put(id, bt.clone());

            if (bt.name != null && isXrayTarget(bt.name)) {
                xrayTargetIds.add(id);
                log.info("  Target block: id={} name='{}' drawType={}", id, bt.name, bt.drawType);
            }
        }
        maxBlockId = Math.max(maxBlockId, update.maxId);

        boolean shouldMutate = (xrayEnabled || nightVisionEnabled)
                && (update.type == UpdateType.Init || update.type == UpdateType.AddOrUpdate);
        if (!shouldMutate) return Action.FORWARD;

        for (Map.Entry<Integer, BlockType> entry : update.blockTypes.entrySet()) {
            applyMods(entry.getKey(), entry.getValue());
        }
        log.info("Mutated {} packet: xray={}, nv={}", update.type, xrayEnabled, nightVisionEnabled);
        resend(session, update);
        return Action.DROP;
    }

    private static void applyMods(int id, BlockType bt) {
        if (xrayEnabled && xrayTargetIds.contains(id)) {
            bt.drawType = DrawType.Empty;
        }
        if (nightVisionEnabled) {
            bt.light = NV_LIGHT;
            nightVisionDirtyIds.add(id);
        }
    }

    private static void resend(ProxySession session, Packet packet) {
        ByteBuf out = Unpooled.buffer();
        PacketIO.writeFramedPacket(packet, packet.getClass(), out, PacketStatsRecorder.NOOP);
        session.sendRawToClient(out);
    }

    private boolean isXrayTarget(String name) {
        for (String prefix : XRAY_TARGET_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    private static void sendBlockUpdate(ProxySession session) {
        if (originalBlockTypes.isEmpty()) return;

        Map<Integer, BlockType> modifiedTypes = new HashMap<>();
        // NV on: cover every known block (server sends no light for most). NV off: still
        // touch every id we previously lit so clients restore originals, plus xray targets.
        Set<Integer> idsToUpdate = new HashSet<>();
        if (nightVisionEnabled) {
            idsToUpdate.addAll(originalBlockTypes.keySet());
        } else {
            idsToUpdate.addAll(xrayTargetIds);
            idsToUpdate.addAll(nightVisionDirtyIds);
        }

        for (int id : idsToUpdate) {
            BlockType original = originalBlockTypes.get(id);
            if (original == null) continue;
            BlockType modified = original.clone();
            applyMods(id, modified);
            modifiedTypes.put(id, modified);
        }

        if (modifiedTypes.isEmpty()) return;
        if (!nightVisionEnabled) nightVisionDirtyIds.clear();

        UpdateBlockTypes update = new UpdateBlockTypes(
                UpdateType.AddOrUpdate, maxBlockId, modifiedTypes, true, true, true, true);

        log.info("Sending UpdateBlockTypes with {} modified blocks (xray={}, nv={})",
                modifiedTypes.size(), xrayEnabled, nightVisionEnabled);
        resend(session, update);
    }
}
