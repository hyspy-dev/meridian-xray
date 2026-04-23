package meridian.xray;

import java.awt.Color;
import java.util.function.Consumer;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import meridian.proxy.module.ModuleContext;
import meridian.proxy.module.ProxyModule;
import org.slf4j.Logger;

public class XrayModule implements ProxyModule {
    private static final Color PANEL_BG = new Color(20, 20, 20);
    private Logger log;

    @Override
    public void onEnable(ModuleContext context) {
        this.log = context.getLogger();
        log.info("XrayModule initialization started...");

        context.registerHandler((direction, forwarder) -> new XrayHandler(direction));

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(PANEL_BG);

        panel.add(checkbox("Enable X-Ray Visualization",
                XrayHandler.isXrayEnabled(),
                v -> { XrayHandler.setXrayEnabled(v); log.info("X-Ray toggled via UI: {}", v); }));
        panel.add(checkbox("Enable Night Vision",
                XrayHandler.isNightVisionEnabled(),
                v -> { XrayHandler.setNightVisionEnabled(v); log.info("Night Vision toggled via UI: {}", v); }));

        context.registerSettings(panel);
        log.info("XrayModule enabled!");
    }

    private static JCheckBox checkbox(String label, boolean initial, Consumer<Boolean> onToggle) {
        JCheckBox box = new JCheckBox(label, initial);
        box.setBackground(PANEL_BG);
        box.setForeground(Color.WHITE);
        box.addActionListener(e -> onToggle.accept(box.isSelected()));
        return box;
    }
}
