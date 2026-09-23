package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.badlogic.gdx.graphics.Pixmap;
import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.SuperNovaTracker;
import com.shatteredpixel.shatteredpixeldungeon.effects.VisualCueCollector;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfBlastWave;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.watabou.gltextures.SmartTexture;
import com.watabou.gltextures.TextureCache;
import com.watabou.glwrap.Uniform;
import com.watabou.glwrap.Vertexbuffer;
import com.watabou.noosa.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real effect/Image draw methods with a recording shader and synthetic texture samples, no GPU or saves. */
class RadialVisualObservationTest {
    @BeforeAll static void resources() { GameSnapshotterTest.resourceOnlyRuntime(); }

    @Test void novaPublishesOnlyAfterItsNativeImageDrawAndPreservesTheGradientAlphaTransform() throws Exception {
        try (DrawFixture fixture = new DrawFixture()) {
            DrawnNova halo = fixture.halo();
            fixture.begin(); fixture.finish(); assertTrue(fixture.cues.isEmpty(), "Construction is not draw evidence");
            halo.am = -1; halo.aa = 1;
            assertEquals(0, halo.alpha()); assertTrue(halo.renderedHaloContributes());
            VisualCue edge = fixture.draw(halo);
            assertEquals(1, fixture.shader.draws);
            assertEquals("supernova_halo", edge.kind);
            assertEquals(Arrays.asList(-1f, 1f), edge.appearance.get("alpha_transform"));
            assertEquals(Arrays.asList(12f, 12f), edge.appearance.get("radius_world"));
            assertEquals(Arrays.asList(257f * 12f / 128f, 257f * 12f / 128f), edge.appearance.get("size"));
            assertEquals(Arrays.asList(80f, 80f), edge.appearance.get("center_world"));
            assertNull(edge.opacity, "A gradient cannot be represented by white-pixel alpha alone");
            assertThrows(UnsupportedOperationException.class, () -> edge.appearance.put("turnsLeft", 10));
            halo.am = halo.aa = 0; assertNull(fixture.draw(halo));
            halo.am = 1; halo.aa = -.95f;
            assertNull(fixture.draw(halo), "Synthetic halo's maximum texture alpha is below .95");
        }
    }

    @Test void novaNeverReadsTheCountdownOrBackingRadiusAndRejectsChangedTextureOrUndrawnImage() throws Exception {
        try (DrawFixture fixture = new DrawFixture()) {
            DrawnNova halo = fixture.halo();
            VisualCue first = fixture.draw(halo);
            set(halo.owner, SuperNovaTracker.class, "turnsLeft", -900);
            set(halo, Halo.class, "radius", 999f);
            assertEquals(first, fixture.draw(halo), "Only already applied scale/alpha belong to this frame");
            halo.scale.set(halo.scale.x * 2); assertNotEquals(first, fixture.draw(halo));
            SmartTexture nativeTexture = halo.texture;
            halo.texture = fixture.effectTexture; assertNull(fixture.draw(halo));
            halo.texture = null; int before = fixture.shader.draws;
            assertNull(fixture.draw(halo)); assertEquals(before, fixture.shader.draws);
            halo.texture = nativeTexture; set(halo, Image.class, "buffer", null); set(halo, Image.class, "dirty", false);
            assertNull(fixture.draw(halo)); assertEquals(before, fixture.shader.draws);
        }
    }

    @Test void novaAndWaveRequireFullActualBoundsFovAttachmentAndNoOverlay() throws Exception {
        try (DrawFixture fixture = new DrawFixture()) {
            DrawnNova halo = fixture.halo(); assertNotNull(fixture.draw(halo));
            halo.visible = false; assertNull(fixture.draw(halo)); halo.visible = true;
            fixture.scene.erase(halo); assertNull(fixture.draw(halo)); fixture.scene.add(halo);
            // The center remains visible; one peripheral quad cell is hidden.
            fixture.level.heroFOV[4 + 4 * fixture.level.width()] = false;
            assertNull(fixture.draw(halo)); Arrays.fill(fixture.level.heroFOV, true);
            Camera.main.scroll.x = 70;
            assertNull(fixture.draw(halo), "The visible center tile cannot disclose a partially clipped halo");
            Camera.main.scroll.x = 0;
            // The quad begins at 67.953125; overlap its rim while leaving the center tile uncovered.
            fixture.cover = new Visual(68, 68, 2, 2); fixture.cover.camera = PixelScene.uiCamera;
            fixture.scene.add(fixture.cover); assertNull(fixture.draw(halo));
            fixture.cover.visible = false; assertNotNull(fixture.draw(halo));
            DrawnWave wave = fixture.wave(6); wave.scale.set(1.5f);
            assertNotNull(fixture.draw(wave));
            fixture.level.heroFOV[4 + 4 * fixture.level.width()] = false;
            assertNull(fixture.draw(wave));
        }
    }

    @Test void blastRetainsCurrentRadiusSizeScaleAndOpacityButNeverConfiguredFinalSize() throws Exception {
        try (DrawFixture fixture = new DrawFixture()) {
            List<VisualCue> observed = new ArrayList<>();
            for (float configured : new float[]{1, 3, 6}) {
                DrawnWave wave = fixture.wave(configured);
                assertNull(fixture.draw(wave), "The reset zero-size image has no visible extent");
                wave.scale.set(configured / 2f); wave.alpha(.5f);
                VisualCue cue = fixture.draw(wave); observed.add(cue);
                assertEquals("blast_wave", cue.kind);
                assertEquals(Arrays.asList(8f * configured, 8f * configured), cue.appearance.get("size"));
                assertEquals(Arrays.asList(4f * configured, 4f * configured), cue.appearance.get("radius_world"));
                assertEquals(Arrays.asList(.5f, 0f), cue.appearance.get("alpha_transform"));
                set(wave, WandOfBlastWave.BlastWave.class, "size", 99f);
                set(wave, WandOfBlastWave.BlastWave.class, "time", 999f);
                assertEquals(cue, fixture.draw(wave), "Configured future size/time do not enter observation");
                assertFalse(cue.appearance.containsKey("duration")); assertFalse(cue.appearance.containsKey("max"));
                fixture.scene.erase(wave);
            }
            assertEquals(3, new HashSet<>(observed).size());
        }
    }

    private static final class DrawFixture implements AutoCloseable {
        final Game previousGame = Game.instance;
        final RuntimeObserver previousObserver = Game.observer;
        final Camera previousCamera = Camera.main, previousUiCamera = PixelScene.uiCamera;
        final Level previousLevel = Dungeon.level;
        final String previousRun = Dungeon.runId;
        final Object previousScene;
        final Map<Object,SmartTexture> textures;
        final SmartTexture previousHalo, previousEffects;
        final SampleTexture haloTexture, effectTexture;
        final RecordingShader shader;
        final GameScene scene = new GameScene();
        final TestLevel level = new TestLevel();
        final VisualCueCollector collector = new VisualCueCollector(scene);
        List<VisualCue> cues = Collections.emptyList();
        Visual cover;

        @SuppressWarnings("unchecked") DrawFixture() throws Exception {
            previousScene = get(null, GameScene.class, "scene");
            textures = (Map<Object,SmartTexture>)get(null, TextureCache.class, "all");
            haloTexture = texture(257, 257); effectTexture = texture(128, 128);
            previousHalo = textures.put(Halo.class, haloTexture); previousEffects = textures.put(Assets.Effects.EFFECTS, effectTexture);
            Camera.main = new Camera(0, 0, 256, 256, 1); PixelScene.uiCamera = new Camera(0, 0, 256, 256, 1);
            Dungeon.level = level; Dungeon.runId = "radial-unit-fixture";
            Game.instance = allocate(Game.class); set(Game.instance, Game.class, "scene", scene);
            set(null, GameScene.class, "scene", scene); set(scene, GameScene.class, "visualCueCollector", collector);
            Game.observer = new RuntimeObserver() {
                @Override public boolean observesVisualCues() { return true; }
                @Override public void onVisualCues(String run, Object grid, int depth, List<VisualCue> value, boolean ready) {
                    cues = new ArrayList<>(value);
                }
            };
            shader = allocate(RecordingShader.class);
            shader.uModel = new Uniform(0) { @Override public void valueM4(float[] matrix) {} };
        }
        DrawnNova halo() throws Exception {
            DrawnNova halo = new DrawnNova(new SuperNovaTracker(), shader);
            halo.radius(12); halo.point(80, 80); halo.alpha(.75f); halo.hardlight(1, 1, 0);
            prepare(halo); return halo;
        }
        DrawnWave wave(float configured) throws Exception {
            DrawnWave wave = new DrawnWave(shader); wave.reset(5 + 5 * level.width(), configured);
            prepare(wave); return wave;
        }
        void prepare(Image image) throws Exception {
            set(image, Image.class, "buffer", allocate(Vertexbuffer.class)); set(image, Image.class, "dirty", false);
            scene.add(image);
        }
        void begin() { collector.beginDraw(); }
        void finish() { collector.finishDraw(); }
        VisualCue draw(Image image) { begin(); image.draw(); finish(); return cues.isEmpty() ? null : cues.get(0); }
        @Override public void close() throws Exception {
            Game.instance = previousGame; Game.observer = previousObserver; Camera.main = previousCamera;
            PixelScene.uiCamera = previousUiCamera; Dungeon.level = previousLevel; Dungeon.runId = previousRun;
            set(null, GameScene.class, "scene", previousScene);
            if (previousHalo == null) textures.remove(Halo.class); else textures.put(Halo.class, previousHalo);
            if (previousEffects == null) textures.remove(Assets.Effects.EFFECTS); else textures.put(Assets.Effects.EFFECTS, previousEffects);
        }
    }

    private static final class DrawnNova extends SuperNovaTracker.NovaVFX {
        final SuperNovaTracker owner; final RecordingShader shader;
        DrawnNova(SuperNovaTracker owner, RecordingShader shader) { owner.super(); this.owner = owner; this.shader = shader; }
        @Override protected NoosaScript script() { return shader; }
    }
    private static final class DrawnWave extends WandOfBlastWave.BlastWave {
        final RecordingShader shader;
        DrawnWave(RecordingShader shader) { this.shader = shader; }
        @Override protected NoosaScript script() { return shader; }
    }
    private static final class RecordingShader extends NoosaScript {
        int draws;
        @Override public void camera(Camera camera) {}
        @Override public void lighting(float rm, float gm, float bm, float am, float ra, float ga, float ba, float aa) {}
        @Override public void drawQuad(Vertexbuffer buffer) { draws++; }
    }
    /** Samples deliberately stand in for the gradient; real native Pixmap evidence is a GUI fixture. */
    private static final class SampleTexture extends SmartTexture {
        SampleTexture() { super((Pixmap)null); }
        @Override public void bind() {}
        @Override public int getPixel(int x, int y) { return x == 128 && y == 128 ? 0xDDFFFFFF : 0x00FFFFFF; }
    }
    private static SampleTexture texture(int width, int height) throws Exception {
        SampleTexture texture = allocate(SampleTexture.class); texture.width = width; texture.height = height;
        texture.bitmap = allocate(Pixmap.class); return texture;
    }
    private static final class TestLevel extends Level {
        TestLevel() { width = height = 16; length = 256; heroFOV = new boolean[length]; Arrays.fill(heroFOV, true); }
        @Override protected boolean build() { return false; }
        @Override protected void createMobs() {}
        @Override protected void createItems() {}
    }
    @SuppressWarnings("unchecked") private static <T>T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe"); Field field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return (T)unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type);
    }
    private static Object get(Object target, Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
}
