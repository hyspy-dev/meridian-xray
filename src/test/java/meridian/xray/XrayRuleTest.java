package meridian.xray;

import java.lang.reflect.Field;
import java.util.Arrays;
import meridian.core.api.BlockView;

/**
 * The rule X-Ray hands core: the ground by its item categories, air by its name. A plain test class
 * (no framework on hand offline): every {@code test*} method runs, an {@link AssertionError} fails it.
 */
public class XrayRuleTest {

    public void testTerrainIsRocksAndSoils() {
        check(XrayModule.isTerrain(FakeBlock.of(5, "Rock_Stone", "Blocks.Rocks")), "stone");
        check(XrayModule.isTerrain(FakeBlock.of(6, "Soil_Dirt", "Blocks.Soils")), "dirt");
        check(XrayModule.isTerrain(FakeBlock.of(7, "Rubble_Stone", "Blocks.Rocks")), "a rock by kind, whatever its name");
        check(!XrayModule.isTerrain(FakeBlock.of(8, "Ore_Copper_Stone", "Blocks.Ores")), "ore stays solid");
        check(!XrayModule.isTerrain(FakeBlock.of(9, "Rock_Gold_Brick", "Blocks.Metal")), "a brick named Rock_ is no ground");
        check(!XrayModule.isTerrain(FakeBlock.of(10, "Rock_Stone")), "no categories, no ground");
    }

    public void testHiddenGroundGlowsAirLightsAndTheRestIsLeftAlone() throws Exception {
        XrayModule x = new XrayModule();
        set(x, "xrayEnabled", true);
        set(x, "mode", XrayModule.Mode.HIDE);
        set(x, "airGlow", true);
        FakeBlock ore = FakeBlock.of(8, "Ore_Copper_Stone", "Blocks.Ores");
        check(x.look(ore) == ore, "a block X-Ray does not target comes back as it was");
        FakeBlock stone = (FakeBlock) x.look(FakeBlock.of(5, "Rock_Stone", "Blocks.Rocks"));
        check(!stone.visible(), "hidden ground");
        check(Arrays.equals(stone.light(), new int[] {1, 8, 7, 5}), "the ground's warm glow: " + Arrays.toString(stone.light()));
        FakeBlock air = (FakeBlock) x.look(FakeBlock.of(0, BlockView.EMPTY));
        check(Arrays.equals(air.light(), new int[] {1, 4, 4, 8}), "fullbright air: " + Arrays.toString(air.light()));
        set(x, "xrayEnabled", false);
        FakeBlock dirt = FakeBlock.of(6, "Soil_Dirt", "Blocks.Soils");
        check(x.look(dirt) == dirt, "X-Ray off leaves the ground alone");
    }

    private static void set(Object o, String field, Object value) throws Exception {
        Field f = o.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(o, value);
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
