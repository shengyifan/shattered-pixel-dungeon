package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Badges;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Rankings;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.HeroSprite;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndRanking;
import com.watabou.gltextures.SmartTexture;
import com.watabou.gltextures.TextureCache;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;
import com.watabou.utils.GameSettings;
import com.watabou.utils.PlatformSupport;
import com.watabou.utils.RectF;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Constructs the real ranking stats page. Only the disk-loading outer window and font/GL backend
 * are omitted; the native copy-seed visibility guard and button construction run unchanged. */
class RankingSeedAvailabilityTest {
    private final Map<Field, Object> saved = new LinkedHashMap<>();
    private final Map<String, Object> preferences = new HashMap<>();
    private final Map<Object, SmartTexture> oldTextures = new LinkedHashMap<>();
    private final HashSet<Badges.Badge> badges = new HashSet<>();
    private Map<Object, SmartTexture> textures;
    private WndRanking owner;

    @BeforeEach void setup() throws Exception {
        remember(Gdx.class, "app"); remember(Gdx.class, "files"); remember(Gdx.class, "graphics");
        remember(GameSettings.class, "prefs"); remember(Game.class, "platform");
        remember(Game.class, "width"); remember(Game.class, "height");
        remember(Game.class, "version"); remember(PixelScene.class, "defaultZoom");
        remember(Badges.class, "global"); remember(HeroSprite.class, "tiers");
        for (String name : new String[]{"hero", "seed", "daily", "dailyReplay", "customSeedText"}) remember(Dungeon.class, name);
        GameSnapshotterTest.resourceOnlyRuntime();
        Gdx.graphics = (Graphics) Proxy.newProxyInstance(Graphics.class.getClassLoader(),
                new Class<?>[]{Graphics.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getBackBufferWidth") || method.getName().equals("getWidth")) return 320;
                    if (method.getName().equals("getBackBufferHeight") || method.getName().equals("getHeight")) return 240;
                    throw new AssertionError("Unexpected graphics operation: " + method.getName());
                });
        Game.width = 320; Game.height = 240;
        Game.platform = new NoFontPlatform();
        Game.version = "4.0.2";
        PixelScene.defaultZoom = 1;
        field(HeroSprite.class, "tiers").set(null, null);
        field(Badges.class, "global").set(null, badges);
        GameSettings.set((Preferences) Proxy.newProxyInstance(Preferences.class.getClassLoader(),
                new Class<?>[]{Preferences.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("contains")) return preferences.containsKey(args[0]);
                    if (name.startsWith("get") && args != null && args.length == 2) return preferences.getOrDefault(args[0], args[1]);
                    if (name.startsWith("put") && args != null && args.length == 2) {
                        preferences.put((String) args[0], args[1]); return proxy;
                    }
                    if (name.equals("flush")) return null;
                    throw new AssertionError("Unexpected preferences operation: " + name);
                }));
        @SuppressWarnings("unchecked") Map<Object, SmartTexture> cache = (Map<Object, SmartTexture>) field(TextureCache.class, "all").get(null);
        textures = cache;
        install(Assets.Interfaces.ICONS, 256, 256);
        install(Assets.Interfaces.CHROME, 128, 128);
        install(Assets.Sprites.WARRIOR, 256, 128);
        install(Assets.Sprites.ROGUE, 256, 128);
        for (int color : new int[]{0xFFAA0000, 0xFF00EE00, 0xFFFFFFFF, 0x66000000}) install("1x1:" + color, 1, 1);
        Dungeon.hero = new Hero();
        Dungeon.seed = 123456L;
        Dungeon.daily = Dungeon.dailyReplay = false;
        Dungeon.customSeedText = "";
        owner = FloatingAppearanceTest.allocate(WndRanking.class);
        Rankings.Record record = new Rankings.Record();
        record.heroClass = HeroClass.WARRIOR;
        record.herolevel = 1;
        record.armorTier = 0;
        record.date = "Isolated ranking availability fixture";
        record.version = "4.0.2";
        field(WndRanking.class, "record").set(owner, record);
    }

    @AfterEach void restore() throws Exception {
        if (textures != null) for (var entry : oldTextures.entrySet()) {
            if (entry.getValue() == null) textures.remove(entry.getKey());
            else textures.put(entry.getKey(), entry.getValue());
        }
        for (var entry : saved.entrySet()) entry.getKey().set(null, entry.getValue());
    }

    @Test void configuredProfileOffersNativeCopySeedWithoutClaimingVictory() throws Exception {
        SPDSettings.unlockStartOptions(true);
        assertTrue(hasCopySeedButton());
        assertFalse(Badges.isUnlocked(Badges.Badge.VICTORY));
        assertTrue(badges.isEmpty());
        SPDSettings.unlockStartOptions(false);
        assertFalse(hasCopySeedButton());
        badges.add(Badges.Badge.VICTORY);
        assertTrue(hasCopySeedButton());
    }

    @Test void unlockedProfileStillHonorsNativeDailyUnknownSeedAndMissingHeroGuards() throws Exception {
        SPDSettings.unlockStartOptions(true);
        Dungeon.daily = true;
        assertFalse(hasCopySeedButton());
        Dungeon.daily = false;
        Dungeon.seed = -1;
        assertFalse(hasCopySeedButton());
        Dungeon.seed = 123456L;
        Dungeon.hero = null;
        assertFalse(hasCopySeedButton());
    }

    private boolean hasCopySeedButton() throws Exception {
        Class<?> type = Class.forName(WndRanking.class.getName() + "$StatsTab");
        Constructor<?> constructor = type.getDeclaredConstructor(WndRanking.class);
        constructor.setAccessible(true);
        Group page = (Group) constructor.newInstance(owner);
        try {
            for (Gizmo child : page.childrenSnapshot()) if (child instanceof RedButton) {
                RedButton button = (RedButton) child;
                if ("Copy Seed".equals(button.text())) {
                    assertTrue(button.exists && button.visible && button.active);
                    assertEquals(115f, button.width());
                    assertEquals(16f, button.height());
                    return true;
                }
            }
            return false;
        } finally { page.destroy(); }
    }

    private void install(String key, int width, int height) throws Exception {
        SmartTexture texture = FloatingAppearanceTest.allocate(SmartTexture.class);
        texture.width = width; texture.height = height;
        oldTextures.put(key, textures.put(key, texture));
    }
    private void remember(Class<?> type, String name) throws Exception {
        Field field = field(type, name); saved.put(field, field.get(null));
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }

    private static final class NoFontPlatform extends PlatformSupport {
        @Override public void updateDisplaySize() {}
        @Override public void updateSystemUI() {}
        @Override public boolean connectedToUnmeteredNetwork() { return false; }
        @Override public boolean supportsVibration() { return false; }
        @Override public void setupFontGenerators(int pageSize, boolean systemFont) {}
        @Override protected FreeTypeFontGenerator getGeneratorForString(String input) { return null; }
        @Override public String[] splitforTextBlock(String text, boolean multiline) { return new String[]{text}; }
        @Override public BitmapFont getFont(int size, String text, boolean flipped, boolean border) { return null; }
        @Override public RectF getSafeInsets(int level) { return new RectF(); }
    }
}
