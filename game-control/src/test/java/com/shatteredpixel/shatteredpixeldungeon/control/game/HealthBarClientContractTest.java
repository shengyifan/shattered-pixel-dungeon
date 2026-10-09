package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.ui.HealthBar;
import com.watabou.noosa.Camera;
import com.watabou.noosa.ColorBlock;
import com.watabou.utils.PointF;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.shatteredpixel.shatteredpixeldungeon.control.game.SnapshotFields.map;
import static org.junit.jupiter.api.Assertions.*;

/** Real native layout/export -> protocol codec -> optional Python decoder. No GL, game or profile I/O. */
class HealthBarClientContractTest {
    @Test void nativeFractionalBarsSurvivePlayFullAndSourceEncodingAndPythonDecoding() throws Exception {
        List<Object> cases = new ArrayList<>();
        float[][] dimensions = {{16f, 1f, .25f, .5f, .125f}, {64f / 3f, 1f, .15f, 1f, .5f},
                {64f / 3f, 1f, 1f, 1f, 2f}, {128f / 9f, 1.5f, .15f, 1f, 0f}};
        int[][] expected = {{16, 4, 8, 2}, {21, 4, 22, 11}, {21, 22, 22, 22}, {21, 4, 22, 0}};
        for (int i = 0; i < dimensions.length; i++) {
            float[] spec = dimensions[i];
            HealthBar bar = bar(spec[0], spec[1], spec[2], spec[3], spec[4]);
            assertArrayEquals(expected[i], bar.renderedPixelWidths());
            Map<String,Object> entity = map("kind", "character", "cell", 3, "name", "fixture target");
            entity.putAll(bar.gameplayStatus());
            Map<String,Object> hero = map("cell", 0);
            hero.putAll(bar.gameplayStatus());
            Map<String,Object> original = map("scene", "game", "phase", "player_ready",
                    "scope_id", "s1", "state_version", "r1", "visible_entities", List.of(entity), "hero", hero,
                    "ui", map("controls", Collections.emptyList()));
            String before = JsonCodec.encode(original);
            for (int view = 0; view < 3; view++) {
                Map<String,Object> wire = CompactProtocol.success("bar-" + i + "-" + view,
                        "s1", "completed", original, true, view == 2, view != 0);
                cases.add(map("wire", wire, "expected", Arrays.asList(expected[i][0], expected[i][1], expected[i][2], expected[i][3])));
                assertEquals(before, JsonCodec.encode(original), "Encoding must not normalize the native sample");
            }
        }

        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("desktop-control/client"))) root = root.getParent();
        assertNotNull(root, "Repository client must be available to this cross-language contract test");
        String script = String.join("\n",
                "import copy,json,sys",
                "sys.path.insert(0,sys.argv[1])",
                "from spdctl_client import decode_client_response",
                "cases=json.load(sys.stdin)",
                "for case in cases:",
                "    wire=case['wire']; before=copy.deepcopy(wire)",
                "    result=decode_client_response(wire)",
                "    frame=result.require_current_frame().data",
                "    sample=frame['entities'][0]['health_estimate']['samples'][0]",
                "    assert [sample[k] for k in ('total','filled','with_shield','incoming_dot')]==case['expected'], sample",
                "    assert frame['hero']['health_estimate']['samples'][0]==sample",
                "    assert sample['basis']=='displayed'",
                "    assert wire==before and result.raw==before",
                "print('PASS: native health samples through play/full/src and Python')");
        ProcessBuilder builder = new ProcessBuilder("python3", "-c", script,
                root.resolve("desktop-control/client").toString()).redirectErrorStream(true);
        builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        Process process = builder.start();
        try {
            process.getOutputStream().write(JsonCodec.encode(cases).getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Offline decoder did not terminate");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.startsWith("PASS:"), output);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static HealthBar bar(float width, float zoom, float health, float shield, float incomingDot) throws Exception {
        // Allocate only drawable shells: the real HealthBar.layout and export methods run below.
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeType.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        Object unsafe = singleton.get(null);
        HealthBar bar = (HealthBar)unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe, HealthBar.class);
        for (String name : Arrays.asList("Bg", "Hp", "Shld", "Dot")) {
            ColorBlock block = (ColorBlock)unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe, ColorBlock.class);
            block.scale = new PointF(1f, 1f);
            Field field = HealthBar.class.getDeclaredField(name); field.setAccessible(true); field.set(bar, block);
        }
        bar.camera = new Camera(0, 0, 100, 100, zoom);
        bar.setRect(0, 0, width, 2);
        bar.level(health, shield, incomingDot);
        return bar;
    }
}
