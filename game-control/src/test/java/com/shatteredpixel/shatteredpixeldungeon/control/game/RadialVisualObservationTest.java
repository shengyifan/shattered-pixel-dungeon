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

    @Test void novaRetainsKnownExtentWithoutNativeDrawOrShaderParameters() throws Exception {
        try(DrawFixture fixture=new DrawFixture()){
            DrawnNova halo=fixture.halo();fixture.begin();fixture.finish();
            assertEquals(1,fixture.cues.size(),"An offscreen source need not execute a native draw");
            assertEquals(0,fixture.shader.draws);
            halo.am=-1;halo.aa=1;assertTrue(halo.renderedHaloContributes());
            VisualCue edge=fixture.draw(halo);assertEquals("supernova_halo",edge.kind);
            assertEquals("visual_extent",edge.appearance.get("coverage"));
            assertTrue(edge.appearance.get("cells") instanceof List<?>);
            for(String field:Arrays.asList("alpha_transform","radius_world","size","center_world","scale","angle"))
                assertFalse(edge.appearance.containsKey(field));
            assertNull(edge.color);assertNull(edge.opacity);
            halo.am=halo.aa=0;assertNull(fixture.draw(halo));
            halo.am=1;halo.aa=-.95f;assertNull(fixture.draw(halo));
        }
    }
    @Test void observationDoesNotReadOrAdvanceTheCountdownOrFutureRadius() throws Exception {
        try(DrawFixture fixture=new DrawFixture()){
            DrawnNova halo=fixture.halo();VisualCue first=fixture.draw(halo);
            set(halo.owner,SuperNovaTracker.class,"turnsLeft",-900);set(halo,Halo.class,"radius",999f);
            assertEquals(first,fixture.draw(halo));assertEquals(-900,get(halo.owner,SuperNovaTracker.class,"turnsLeft"));
            halo.scale.set(halo.scale.x*3);assertNotEquals(first,fixture.draw(halo));
            halo.texture=fixture.effectTexture;assertNull(fixture.draw(halo));halo.texture=null;assertNull(fixture.draw(halo));
        }
    }
    @Test void viewportAndUiCannotHideKnownExtentAndFogReturnsOnlyPartialCells() throws Exception {
        try(DrawFixture fixture=new DrawFixture()){
            DrawnNova halo=fixture.halo();VisualCue first=fixture.draw(halo);assertNotNull(first);
            Camera.main.scroll.x=700;assertEquals(first,fixture.draw(halo));
            fixture.cover=new Visual(68,68,40,40);fixture.cover.camera=PixelScene.uiCamera;fixture.scene.add(fixture.cover);
            assertEquals(first,fixture.draw(halo));
            fixture.level.heroFOV[4+4*fixture.level.width()]=false;
            VisualCue partial=fixture.draw(halo);assertNotNull(partial);assertEquals(true,partial.appearance.get("partial"));
            assertFalse(((List<?>)partial.appearance.get("cells")).contains(68));
            Arrays.fill(fixture.level.heroFOV,false);assertNull(fixture.draw(halo));
            Arrays.fill(fixture.level.heroFOV,true);halo.visible=false;assertNull(fixture.draw(halo));
            halo.visible=true;fixture.scene.erase(halo);assertNull(fixture.draw(halo));
        }
    }
    @Test void waveUsesCurrentKnownExtentWithoutConfiguredFinalSizeOrTiming() throws Exception {
        try(DrawFixture fixture=new DrawFixture()){
            DrawnWave wave=fixture.wave(6);assertNull(fixture.draw(wave));
            wave.scale.set(1.5f);wave.alpha(.5f);VisualCue cue=fixture.draw(wave);assertNotNull(cue);
            assertEquals("blast_wave",cue.kind);assertEquals("ring",cue.appearance.get("shape"));
            set(wave,WandOfBlastWave.BlastWave.class,"size",99f);set(wave,WandOfBlastWave.BlastWave.class,"time",999f);
            assertEquals(cue,fixture.draw(wave));assertEquals(999f,get(wave,WandOfBlastWave.BlastWave.class,"time"));
            assertNull(cue.opacity);assertFalse(cue.appearance.containsKey("duration"));
        }
    }

    private static final class DrawFixture implements AutoCloseable {
        final Game previousGame = Game.instance;
        final RuntimeObserver previousObserver = Game.observer;
        final Camera previousCamera = Camera.main, previousUiCamera = PixelScene.uiCamera;
        final Level previousLevel = Dungeon.level;
        final String previousRun = Dungeon.runId;
        final Object previousScene;
        final Object previousActor, previousActorThread;
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
            previousActor=get(null,com.shatteredpixel.shatteredpixeldungeon.actors.Actor.class,"current");
            previousActorThread=get(null,GameScene.class,"actorThread");
            set(null,com.shatteredpixel.shatteredpixeldungeon.actors.Actor.class,"current",null);
            set(null,GameScene.class,"actorThread",null);
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
        VisualCue draw(Image image) { begin(); finish(); return cues.isEmpty() ? null : cues.get(0); }
        @Override public void close() throws Exception {
            Game.instance = previousGame; Game.observer = previousObserver; Camera.main = previousCamera;
            PixelScene.uiCamera = previousUiCamera; Dungeon.level = previousLevel; Dungeon.runId = previousRun;
            set(null, GameScene.class, "scene", previousScene);
            set(null,com.shatteredpixel.shatteredpixeldungeon.actors.Actor.class,"current",previousActor);
            set(null,GameScene.class,"actorThread",previousActorThread);
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
