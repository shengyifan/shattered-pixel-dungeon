package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.items.stones.StoneOfIntuition;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;
import com.watabou.gltextures.SmartTexture;
import com.watabou.gltextures.TextureCache;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Game;
import com.watabou.noosa.Scene;
import com.watabou.utils.PlatformSupport;
import com.watabou.utils.RectF;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Uses the native WndGuess and its real IconButton children without launching a game or profile. */
class WndGuessAccessibilityTest {

    private static Graphics previousGraphics;
    private static PlatformSupport previousPlatform;
    private static Camera previousUiCamera;
    private static int previousZoom, previousWidth, previousHeight;
    private static final Map<Object, SmartTexture> previousTextures = new java.util.LinkedHashMap<>();
    private static final List<Object> fixtureTextureKeys = List.of(
            Assets.Interfaces.CHROME, Assets.Interfaces.SHADOW,
            Assets.Sprites.ITEMS, Assets.Sprites.ITEM_ICONS,
            "1x1:" + 0xFFCC0000, "1x1:" + 0xFF00EE00, "1x1:" + 0xFFFFFFFF);

    @BeforeAll
    static void resources() throws Exception {
        GameSnapshotterTest.resourceOnlyRuntime();
        previousGraphics = Gdx.graphics;
        previousPlatform = Game.platform;
        previousUiCamera = PixelScene.uiCamera;
        previousZoom = PixelScene.defaultZoom;
        previousWidth = Game.width;
        previousHeight = Game.height;

        Gdx.graphics = (Graphics) Proxy.newProxyInstance(Graphics.class.getClassLoader(), new Class<?>[]{Graphics.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getBackBufferWidth") || method.getName().equals("getWidth")) return 320;
                    if (method.getName().equals("getBackBufferHeight") || method.getName().equals("getHeight")) return 240;
                    return primitiveDefault(method.getReturnType());
                });
        Game.platform = new FixturePlatform();
        Game.width = 320;
        Game.height = 240;
        PixelScene.defaultZoom = 1;
        PixelScene.uiCamera = new Camera(0, 0, 320, 240, 1);
        PixelScene.uiCamera.visible = true;

        @SuppressWarnings("unchecked") Map<Object, SmartTexture> textures =
                (Map<Object, SmartTexture>) SnapshotFields.read(TextureCache.class, "all");
        for (Object key : fixtureTextureKeys) {
            if (textures.containsKey(key)) previousTextures.put(key, textures.get(key));
            else if (key.equals(Assets.Sprites.ITEMS)) textures.put(key, texture(256, 512));
            else if (key instanceof String && ((String) key).startsWith("1x1:")) textures.put(key, texture(1, 1));
            else textures.put(key, texture(128, 128));
        }
    }

    @AfterAll
    static void restore() {
        Potion.clearColors();
        Messages.setup(Languages.ENGLISH);
        Gdx.graphics = previousGraphics;
        Game.platform = previousPlatform;
        PixelScene.uiCamera = previousUiCamera;
        PixelScene.defaultZoom = previousZoom;
        Game.width = previousWidth;
        Game.height = previousHeight;
        @SuppressWarnings("unchecked") Map<Object, SmartTexture> textures =
                (Map<Object, SmartTexture>) SnapshotFields.read(TextureCache.class, "all");
        for (Object key : fixtureTextureKeys) {
            if (previousTextures.containsKey(key)) textures.put(key, previousTextures.get(key));
            else textures.remove(key);
        }
    }

    @Test
    void nativeGuessButtonsExposeTheSameEnglishLabelsInPlayFullAndSourceViews() {
        try (TextObservationFixture ignored = new TextObservationFixture()) {
            Messages.setup(Languages.CHI_SMPL);
            Potion.clearColors();
            Potion.initColors();

            List<String> expected = new ArrayList<>();
            for (Class<? extends Potion> type : Potion.getUnknown()) {
                expected.add(Messages.withLanguage(Languages.ENGLISH,
                        () -> Messages.titleCase(Messages.get(type, "name"))));
            }

            StoneOfIntuition stone = new StoneOfIntuition();
            StoneOfIntuition.WndGuess window = stone.new WndGuess(new PotionOfHealing());
            Hero hero = new Hero();
            Dungeon.hero = hero;
            Scene scene = new Scene();
            scene.add(window);
            try {
                UiBridge bridge = new UiBridge(() -> scene);
                Map<String, Object> canonical = PublicEnglishProjection.copy(map("ui", bridge.frozenUi()));
                Map<String, Object> play = expanded(CompactProtocol.project(canonical, false, false));
                Map<String, Object> full = object(CompactProtocol.project(canonical, false, true));
                Map<String, Object> source = object(CompactProtocol.project(canonical, true, false));

                assertEquals(new LinkedHashSet<>(expected), labels(play));
                assertEquals(new LinkedHashSet<>(expected), labels(full));
                assertEquals(new LinkedHashSet<>(expected), labels(source));
                assertTrue(nodes(source).stream().filter(node -> node.get("label") instanceof String)
                        .allMatch(node -> object(node.get("text_sources")).containsKey("label")));

                Map<String, Object> firstChoice = controls(bridge.describeUi()).stream()
                        .filter(node -> expected.get(0).equals(node.get("label"))).findFirst().orElseThrow();
                int quantity = stone.quantity();
                float cooldown = hero.cooldown();
                bridge.execute("ui.activate", map("control", firstChoice.get("id")));
                RedButton confirmation = descendants(window).stream()
                        .filter(RedButton.class::isInstance).map(RedButton.class::cast)
                        .filter(button -> button.visible && button.isActive()).findFirst().orElseThrow();
                assertEquals(expected.get(0), PublicEnglishProjection.text(confirmation.text()));
                assertEquals(quantity, stone.quantity(), "Selecting a candidate must not submit or consume the stone");
                assertEquals(cooldown, hero.cooldown(), "Selecting a candidate must not spend a game turn");
            } finally {
                Dungeon.hero = null;
                clearCurrentGuess();
                scene.erase(window);
                window.destroy();
            }
        }
    }

    private static Set<String> labels(Map<String, Object> state) {
        Set<String> result = new LinkedHashSet<>();
        for (Map<String, Object> node : nodes(state)) {
            if ("button".equals(node.get("role")) && node.get("label") instanceof String) {
                result.add((String) node.get("label"));
            }
        }
        return result;
    }

    private static List<Map<String, Object>> nodes(Map<String, Object> state) {
        List<Map<String, Object>> result = new ArrayList<>();
        Object raw = object(state.get("ui")).get("nodes");
        if (raw instanceof List) for (Object node : (List<?>) raw) result.add(object(node));
        return result;
    }

    private static List<Map<String, Object>> controls(Map<String, Object> ui) {
        List<Map<String, Object>> result = new ArrayList<>();
        Object raw = ui.get("controls");
        if (raw instanceof List) for (Object node : (List<?>) raw) result.add(object(node));
        return result;
    }

    private static List<Gizmo> descendants(Group root) {
        List<Gizmo> result = new ArrayList<>();
        for (Gizmo child : root.childrenSnapshot()) {
            result.add(child);
            if (child instanceof Group) result.addAll(descendants((Group) child));
        }
        return result;
    }

    private static void clearCurrentGuess() {
        try {
            Field field = StoneOfIntuition.class.getDeclaredField("curGuess");
            field.setAccessible(true);
            field.set(null, null);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Map<String, Object> expanded(Object value) {
        return object(CompactProtocol.expandStructures(value));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static SmartTexture texture(int width, int height) throws Exception {
        SmartTexture texture = allocate(SmartTexture.class);
        texture.width = width;
        texture.height = height;
        return texture;
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field field = unsafeType.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(unsafeType.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }

    private static Object primitiveDefault(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return null;
    }

    private static final class FixturePlatform extends PlatformSupport {
        @Override public void updateDisplaySize() { }
        @Override public void updateSystemUI() { }
        @Override public boolean connectedToUnmeteredNetwork() { return false; }
        @Override public boolean supportsVibration() { return false; }
        @Override public void setupFontGenerators(int pageSize, boolean systemFont) { }
        @Override protected FreeTypeFontGenerator getGeneratorForString(String input) { return null; }
        @Override public String[] splitforTextBlock(String text, boolean multiline) { return new String[]{text}; }
        @Override public BitmapFont getFont(int size, String text, boolean flipped, boolean border) { return null; }
        @Override public RectF getSafeInsets(int level) { return new RectF(); }
    }
}
