package meridian.xray;

import java.util.List;
import java.util.function.UnaryOperator;
import meridian.core.api.BlockView;

/** A block type for tests: a name, its categories, and what was done to it. */
record FakeBlock(int id, String name, List<String> categories, boolean visible, Opacity opacity,
                 int[] light) implements BlockView {

    static FakeBlock of(int id, String name, String... categories) {
        return new FakeBlock(id, name, List.of(categories), true, Opacity.SOLID, null);
    }

    @Override public String state() { return DEFAULT_STATE; }
    @Override public boolean isSolid() { return true; }
    @Override public boolean isVisible() { return visible; }
    @Override public boolean isLit() { return light != null; }
    @Override public List<String> textures() { return List.of(); }
    @Override public BlockView withTextures(UnaryOperator<String> remap) { return this; }
    @Override public BlockView withOpacity(Opacity o) { return new FakeBlock(id, name, categories, visible, o, light); }
    @Override public BlockView withSolid(boolean solid) { return this; }
    @Override public BlockView withVisible(boolean v) { return new FakeBlock(id, name, categories, v, opacity, light); }
    @Override public BlockView withLight(int radius, int r, int g, int b) {
        return new FakeBlock(id, name, categories, visible, opacity, new int[] {radius, r, g, b});
    }
    @Override public BlockView withoutLight() { return new FakeBlock(id, name, categories, visible, opacity, null); }
}
