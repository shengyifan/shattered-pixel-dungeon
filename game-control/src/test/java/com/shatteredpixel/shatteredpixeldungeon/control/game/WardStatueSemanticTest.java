package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.effects.VisualCueCollector;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfWarding;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CrystalSpireSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.StatueSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.WardSprite;
import com.watabou.gltextures.SmartTexture;
import com.watabou.gltextures.TextureCache;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Game;
import com.watabou.noosa.MovieClip;
import com.watabou.noosa.RuntimeObserver;
import com.watabou.noosa.VisualCue;
import com.watabou.noosa.TextureFilm;
import com.watabou.utils.RectF;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Native selected appearances only; fixtures never load a game profile or execute an actor turn. */
class WardStatueSemanticTest {
    @BeforeAll static void resources() { GameSnapshotterTest.resourceOnlyRuntime(); }

    @Test void wardChargeRemainsTheSelectedNativeBrightnessUntilItsProducerRefreshes() throws Exception {
        try (Textures ignored = new Textures()) {
            WardSprite sprite = new WardSprite(); sprite.updateTier(3);
            WandOfWarding.Ward ward = FloatingAppearanceTest.allocate(WandOfWarding.Ward.class);
            ward.tier = 3; ward.totalZaps = 2; sprite.ch = ward; sprite.resetColor();
            VisualCue selected = wardCue(sprite);
            assertEquals(3, selected.appearance.get("tier"));
            assertEquals(.6f, ((Number)((Map<?,?>)selected.appearance.get("charge")).get("fraction")).floatValue(), .00001f);
            ward.totalZaps = 4;
            assertEquals(selected, wardCue(sprite), "Observation cannot reread the ward's unseen counters");
            sprite.brightness(2);
            assertEquals(selected, wardCue(sprite), "Temporary execution brightness is not a new charge value");
            sprite.resetColor();
            assertEquals(.2f, ((Number)((Map<?,?>)wardCue(sprite).appearance.get("charge")).get("fraction")).floatValue(), .00001f);
            sprite.ch = null; sprite.updateTier(4);
            assertFalse(wardCue(sprite).appearance.containsKey("charge"), "Sentry forms have no ward-brightness charge meter");
            sprite.frame(new RectF(0, 0, 1, 1));
            assertUnmapped(wardCue(sprite).appearance,"tier","ward_form");
            assertFalse(wardCue(sprite).appearance.containsKey("tier"));
        }
    }

    @Test void statueArmorFollowsTheActualNativeFrameAndDeathRemovesTheArmorAppearance() throws Exception {
        try (Textures ignored = new Textures()) {
            StatueSprite sprite = new StatueSprite(); assertNull(sprite.ch);
            for (int tier = 0; tier <= 5; tier++) {
                sprite.setArmor(tier);
                for (String name : new String[]{"idle", "run", "attack"}) {
                    MovieClip.Animation animation = (MovieClip.Animation)SnapshotFields.read(sprite, name);
                    for (RectF frame : animation.frames) {
                        sprite.frame(frame); assertEquals(tier, statueCue(sprite).appearance.get("tier"));
                    }
                }
                MovieClip.Animation death = (MovieClip.Animation)SnapshotFields.read(sprite, "die");
                sprite.frame(death.frames[0]); assertEquals(0, statueCue(sprite).appearance.get("tier"));
            }
            sprite.texture = FloatingAppearanceTest.allocate(SmartTexture.class);
            assertUnmapped(statueCue(sprite).appearance,"tier","statue_armor");
            assertFalse(statueCue(sprite).appearance.containsKey("tier"));
        }
    }

    @Test void worldCueUsesFovAndAttachmentInsteadOfCameraViewport() throws Exception {
        Level previousLevel = Dungeon.level;
        RuntimeObserver previousObserver = Game.observer;
        Object previousScene = SnapshotFields.read(GameScene.class, "scene");
        Camera previousCamera = Camera.main;
        try (Textures ignored = new Textures()) {
            Grid level = new Grid(); Dungeon.level = level;
            GameScene scene = new GameScene(); setStatic(GameScene.class, "scene", scene);
            VisualCueCollector collector = new VisualCueCollector(scene);
            set(collector, "collecting", true); set(collector, "level", level);
            set(scene, "visualCueCollector", collector);
            Game.observer = new RuntimeObserver() { @Override public boolean observesVisualCues() { return true; } };
            Camera.main = new Camera(0, 0, 8, 8, 1); Camera.main.visible = false;
            WardSprite ward = new WardSprite(); ward.updateTier(3); ward.x = 32.5f; ward.y = 32; scene.add(ward);
            assertEquals(22, ward.renderedCell()); ward.observeGameplayVisuals();
            assertTrue(offered(collector).values().stream().anyMatch(cue -> cue.kind.equals("ward_state")));
            offered(collector).clear(); level.heroFOV[22] = false; ward.observeGameplayVisuals();
            assertTrue(offered(collector).isEmpty());
            level.heroFOV[22] = true; ward.visible = false; ward.observeGameplayVisuals();
            assertTrue(offered(collector).isEmpty());
            ward.visible = true; scene.erase(ward); ward.observeGameplayVisuals();
            assertTrue(offered(collector).isEmpty(), "Detached sources cannot retain their old cell meaning");
        } finally {
            Dungeon.level = previousLevel; Game.observer = previousObserver; Camera.main = previousCamera;
            setStatic(GameScene.class, "scene", previousScene);
        }
    }

    @Test void crystalSpireStagesUseBothFrameCoordinatesForEveryColor() throws Exception {
        try(Textures ignored=new Textures()) {
            CrystalSpireSprite[] sprites={new CrystalSpireSprite.Blue(),new CrystalSpireSprite.Green(),new CrystalSpireSprite.Red()};
            for(int color=0;color<sprites.length;color++) {
                CrystalSpireSprite sprite=sprites[color];assertNull(sprite.ch);
                TextureFilm frames=new TextureFilm(sprite.texture,24,41);
                String[] stages={"intact","cracked","damaged","heavily_damaged","destroyed"};
                for(int stage=0;stage<stages.length;stage++) {
                    sprite.frame(frames.get(color*5+stage));
                    VisualCue observed=cue(sprite,CrystalSpireSprite.class,"renderedSpireCue");
                    assertEquals(stages[stage],observed.appearance.get("stage"));assertFalse(observed.appearance.containsKey("unmapped_indicator"));
                }
                sprite.frame(new RectF(0,0,1,1));
                Map<String,Object> unknown=cue(sprite,CrystalSpireSprite.class,"renderedSpireCue").appearance;
                assertUnmapped(unknown,"stage","crystal_damage_stage");assertFalse(unknown.containsKey("stage"));
            }
        }
    }

    @Test void wardMeterPublishesOnlyEightBitBrightnessAndKeepsUnknownChargeExplicit() throws Exception {
        try(Textures ignored=new Textures()) {
            WardSprite sprite=new WardSprite();sprite.updateTier(3);
            set(sprite,"selectedChargeBrightness",.6001f);VisualCue first=wardCue(sprite);
            set(sprite,"selectedChargeBrightness",.6002f);VisualCue second=wardCue(sprite);
            assertEquals(first,second,"Subpixel shader precision must not become public charge precision");
            assertEquals(.6f,((Number)((Map<?,?>)first.appearance.get("charge")).get("fraction")).floatValue());
            set(sprite,"selectedChargeBrightness",Float.NaN);Map<String,Object> unknown=wardCue(sprite).appearance;
            assertEquals(3,unknown.get("tier"));assertFalse(unknown.containsKey("charge"));assertUnmapped(unknown,"charge","ward_charge");
        }
    }

    private static void assertUnmapped(Map<String,Object> value,String field,String indicator) {
        assertEquals(true,value.get("unmapped_indicator"));assertFalse(value.containsKey("partial"));
        Map<?,?> presentation=(Map<?,?>)value.get("presentation");assertEquals("partial",presentation.get("status"));
        assertEquals(java.util.List.of(Map.of("field",field,"code","unmapped_indicator","indicator",indicator)),presentation.get("diagnostics"));
    }

    private static VisualCue wardCue(WardSprite sprite) throws Exception { return cue(sprite, WardSprite.class, "renderedWardCue"); }
    private static VisualCue statueCue(StatueSprite sprite) throws Exception { return cue(sprite, StatueSprite.class, "renderedArmorCue"); }
    private static VisualCue cue(CharSprite sprite, Class<?> type, String name) throws Exception {
        Method method = type.getDeclaredMethod(name, int.class); method.setAccessible(true);
        return (VisualCue)method.invoke(sprite, 22);
    }
    @SuppressWarnings("unchecked")
    private static Map<Object,VisualCue> offered(VisualCueCollector collector) {
        return (Map<Object,VisualCue>)SnapshotFields.read(collector, "offered");
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static void setStatic(Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); field.set(null, value);
    }
    private static final class Grid extends Level {
        Grid() { width = height = 10; length = 100; heroFOV = new boolean[length]; Arrays.fill(heroFOV, true); }
        @Override protected boolean build() { return false; }
        @Override protected void createMobs() { }
        @Override protected void createItems() { }
    }
    private static final class Textures implements AutoCloseable {
        private final Map<Object,SmartTexture> cache;
        private final Map<String,SmartTexture> previous = new LinkedHashMap<>();
        @SuppressWarnings("unchecked") Textures() throws Exception {
            cache = (Map<Object,SmartTexture>)SnapshotFields.read(TextureCache.class, "all");
            install(Assets.Sprites.WARDS, 64, 16); install(Assets.Sprites.STATUE, 256, 64);
            install(Assets.Sprites.CRYSTAL_SPIRE,128,128);
        }
        private void install(String asset, int width, int height) throws Exception {
            SmartTexture texture = FloatingAppearanceTest.allocate(SmartTexture.class);
            texture.width = width; texture.height = height; previous.put(asset, cache.put(asset, texture));
        }
        @Override public void close() {
            for (Map.Entry<String,SmartTexture> entry : previous.entrySet()) {
                if (entry.getValue() == null) cache.remove(entry.getKey()); else cache.put(entry.getKey(), entry.getValue());
            }
        }
    }
}
