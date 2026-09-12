package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Pasty;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfFrost;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.FerretTuft;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.effects.FloatingText;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.utils.Holiday;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.watabou.utils.Random;
import com.watabou.gltextures.SmartTexture;
import com.watabou.gltextures.TextureCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.shatteredpixel.shatteredpixeldungeon.control.game.SnapshotFields.map;
import static org.junit.jupiter.api.Assertions.*;

/** Resource-only fixtures: no backend, real preferences, native window, or save access. */
class ScopedLanguageTest {
    @BeforeAll
    static void resources() { GameSnapshotterTest.resourceOnlyRuntime(); }

    private TextObservationFixture textObserver;
    @BeforeEach
    void chineseGui() { textObserver=new TextObservationFixture(); Messages.setup(Languages.CHI_SMPL); }

    @AfterEach
    void restoreFixture() {
        textObserver.close();
        Messages.setup(Languages.ENGLISH);
        Dungeon.hero = null;
        Dungeon.level = null;
    }

    @Test
    void englishReadsKeepChineseGuiBundlesLocaleAndFormattersUntouched() {
        assertEquals("战士", HeroClass.WARRIOR.title());
        Messages.decimalFormat("#0.0", 3.5);
        Object bundles = SnapshotFields.read(Messages.class, "bundles");
        Object locale = SnapshotFields.read(Messages.class, "locale");
        Object formatters = SnapshotFields.read(Messages.class, "formatters");
        Map<?, ?> before = new HashMap<>((Map<?, ?>) formatters);

        String result = Messages.withLanguage(Languages.ENGLISH, () -> {
            assertEquals(Languages.ENGLISH, Messages.lang());
            assertEquals(Languages.CHI_SMPL, Messages.selectedLanguage());
            assertEquals(Locale.ENGLISH, Messages.locale());
            assertEquals("Potion of Healing", Messages.titleCase("potion of healing"));
            assertEquals("1.50", Messages.decimalFormat("#0.00", 1.5));
            assertEquals("1.50", Messages.format("%.2f", 1.5));
            return HeroClass.WARRIOR.title();
        });

        assertEquals("warrior", result);
        assertEquals("战士", HeroClass.WARRIOR.title());
        assertEquals(Languages.CHI_SMPL, Messages.lang());
        assertSame(bundles, SnapshotFields.read(Messages.class, "bundles"));
        assertSame(locale, SnapshotFields.read(Messages.class, "locale"));
        assertSame(formatters, SnapshotFields.read(Messages.class, "formatters"));
        assertEquals(before, formatters);
    }

    @Test void publicDisplayReportsSelectedLanguageAndActualGraphicsModeWithoutChangingEither() {
        com.badlogic.gdx.Graphics previous=com.badlogic.gdx.Gdx.graphics;
        java.util.concurrent.atomic.AtomicBoolean fullscreen=new java.util.concurrent.atomic.AtomicBoolean(false);
        com.badlogic.gdx.Gdx.graphics=(com.badlogic.gdx.Graphics)java.lang.reflect.Proxy.newProxyInstance(
                com.badlogic.gdx.Graphics.class.getClassLoader(),new Class<?>[]{com.badlogic.gdx.Graphics.class},
                (proxy,method,args)->{
                    if(method.getName().equals("isFullscreen"))return fullscreen.get();
                    if(method.getReturnType()==boolean.class)return false;
                    if(method.getReturnType()==int.class)return 0;
                    if(method.getReturnType()==float.class)return 0f;
                    return null;
                });
        try {
            com.watabou.noosa.Scene scene=new com.watabou.noosa.Scene();
            UiBridge bridge=new UiBridge(()->scene);
            Map<?,?> shown=Messages.withLanguage(Languages.ENGLISH,()->(Map<?,?>)bridge.describeUi().get("display"));
            assertEquals("zh",shown.get("language"));assertEquals(false,shown.get("fullscreen"));
            fullscreen.set(true);
            assertEquals(true,((Map<?,?>)bridge.describeUi().get("display")).get("fullscreen"));
            assertEquals(Languages.CHI_SMPL,Messages.lang());
        } finally { com.badlogic.gdx.Gdx.graphics=previous; }
    }

    @Test
    void nestedLanguagesAndAllCaseUtilitiesRestoreEvenAfterExceptions() {
        Messages.withLanguage(Languages.ENGLISH, () -> {
            assertEquals("warrior", HeroClass.WARRIOR.title());
            Messages.withLanguage(Languages.GERMAN, () -> {
                assertEquals("1,50", Messages.decimalFormat("#0.00", 1.5));
                assertEquals("1,50", Messages.format("%.2f", 1.5));
                return null;
            });
            assertEquals("1.50", Messages.decimalFormat("#0.00", 1.5));
            Messages.withLanguage(Languages.TURKISH, () -> {
                assertEquals("İstanbul", Messages.capitalize("istanbul"));
                assertEquals("İ", Messages.upperCase("i"));
                assertEquals("ı", Messages.lowerCase("I"));
                return null;
            });
            IllegalStateException failure = new IllegalStateException("fixture failure");
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> Messages.withLanguage(Languages.CHI_SMPL, () -> { throw failure; })));
            assertEquals(Languages.ENGLISH, Messages.lang());
            assertEquals("I", Messages.upperCase("i"));
            return null;
        });
        assertEquals(Languages.CHI_SMPL, Messages.lang());
        assertThrows(IllegalArgumentException.class,
                () -> Messages.withLanguage(Languages.ENGLISH, () -> { throw new IllegalArgumentException(); }));
        assertEquals("战士", HeroClass.WARRIOR.title());
    }

    @Test
    void simultaneousScopesHaveIndependentBundlesAndNumberFormatters() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<String> english = workers.submit(() -> Messages.withLanguage(Languages.ENGLISH,
                    () -> concurrentRead(entered, release, Languages.ENGLISH, "1.50")));
            Future<String> german = workers.submit(() -> Messages.withLanguage(Languages.GERMAN,
                    () -> concurrentRead(entered, release, Languages.GERMAN, "1,50")));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            assertEquals("战士", HeroClass.WARRIOR.title());
            assertEquals(Languages.CHI_SMPL, Messages.lang());
            release.countDown();
            assertEquals("warrior", english.get(10, TimeUnit.SECONDS));
            assertNotEquals("warrior", german.get(10, TimeUnit.SECONDS));
            assertEquals("战士", HeroClass.WARRIOR.title());
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    private static String concurrentRead(CountDownLatch entered, CountDownLatch release,
                                         Languages language, String number) {
        entered.countDown();
        try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
        for (int i = 0; i < 100; i++) {
            assertEquals(language, Messages.lang());
            assertEquals(number, Messages.decimalFormat("#0.00", 1.5));
        }
        return HeroClass.WARRIOR.title();
    }

    @Test
    void actorThreadsAndLaterCallbacksDoNotInheritPresentationLanguage() throws Exception {
        // Char.attack has an ENGLISH-only Random.Int call for the ferret miss effect.
        // A capture must not leave that language condition enabled for original game work.
        FutureTask<Languages> actorLanguage = new FutureTask<>(Messages::lang);
        Supplier<Languages> laterCallback = Messages.withLanguage(Languages.ENGLISH, () -> {
            Thread actor = new Thread(actorLanguage, "isolated-language-boundary");
            actor.start();
            return Messages::lang;
        });
        assertEquals(Languages.CHI_SMPL, actorLanguage.get(10, TimeUnit.SECONDS));
        assertEquals(Languages.CHI_SMPL, laterCallback.get());
        assertEquals(Languages.CHI_SMPL, Messages.lang());
    }

    @Test
    void originalFerretMissRuleConsumesNoExtraRandomAfterEnglishPublicCapture() throws Exception {
        Hero hero = new Hero();
        hero.heroClass = HeroClass.WARRIOR;
        Dungeon.hero = hero;
        LanguageLevel level = new LanguageLevel();
        level.heroFOV = new boolean[4]; // No rendered fight or audio in this isolated model test.
        Dungeon.level = level;
        FerretTuft tuft = new FerretTuft();
        Field levelField = Item.class.getDeclaredField("level");
        levelField.setAccessible(true);
        levelField.set(tuft, 3);
        hero.belongings.backpack.items.add(tuft);
        MissCharacter attacker = new MissCharacter();
        MissCharacter defender = new MissCharacter();
        defender.pos = 1;
        RecordingSprite sprite = allocate(RecordingSprite.class);
        defender.sprite = sprite;
        @SuppressWarnings("unchecked") Map<Object, SmartTexture> textures =
                (Map<Object, SmartTexture>) SnapshotFields.read(TextureCache.class, "all");
        SmartTexture previous = textures.get(Assets.Effects.TEXT_ICONS);
        SmartTexture dimensionsOnly = allocate(SmartTexture.class);
        dimensionsOnly.width = 70;
        dimensionsOnly.height = 80;
        textures.put(Assets.Effects.TEXT_ICONS, dimensionsOnly);
        try {
            byte[] chinese = ferretMiss(attacker, defender, false);
            assertEquals(FloatingText.MISS_TUFT, sprite.icon);
            assertEquals(Languages.CHI_SMPL, sprite.language);
            byte[] afterCapture = ferretMiss(attacker, defender, true);
            assertArrayEquals(chinese, afterCapture);
            assertEquals(FloatingText.MISS_TUFT, sprite.icon);
            assertEquals(Languages.CHI_SMPL, sprite.language);

            // A real English GUI enables the original extra Random.Int(10) branch.
            // This control proves the comparison actually exercises the sensitive rule.
            Messages.setup(Languages.ENGLISH);
            byte[] englishGui = ferretMiss(attacker, defender, false);
            assertEquals(FloatingText.MISS_TUFT, sprite.icon);
            assertFalse(Arrays.equals(chinese, englishGui));
        } finally {
            Messages.setup(Languages.CHI_SMPL);
            if (previous == null) textures.remove(Assets.Effects.TEXT_ICONS);
            else textures.put(Assets.Effects.TEXT_ICONS, previous);
        }
    }

    private static byte[] ferretMiss(Char attacker, Char defender, boolean captureFirst) {
        Random.pushGenerator(5); // Scrambled fixture rolls: 0.72775 accuracy, 0.57378 evasion before tuft.
        try {
            if (captureFirst) PlayerObservation.capture(Dungeon.hero, null, "game");
            assertFalse(attacker.attack(defender)); // Execute the original Char.hit/attack code.
            return Random.exportState();
        } finally { Random.popGenerator(); }
    }

    private static final class MissCharacter extends Char {
        @Override public int attackSkill(Char target) { return 1; }
        @Override public int defenseSkill(Char target) { return 1; }
        @Override protected boolean act() { return false; }
    }

    private static final class RecordingSprite extends CharSprite {
        int icon;
        Languages language;
        @Override public void showStatusWithIcon(int color, String text, int icon, Object... arguments) {
            this.icon = icon;
            language = Messages.lang();
        }
    }

    private static final class LanguageLevel extends Level {
        @Override protected boolean build() { return false; }
        @Override protected void createMobs() {}
        @Override protected void createItems() {}
        @Override public String tilesTex() { return ""; }
        @Override public String waterTex() { return ""; }
    }

    @Test
    void publicCaptureIsEnglishAndDoesNotMutateModelRngHolidayOrGui() throws Exception {
        Hero hero = new Hero();
        hero.heroClass = HeroClass.WARRIOR;
        Dungeon.hero = hero;
        Potion unknown = potion(PotionOfHealing.class);
        hero.belongings.backpack.items.add(unknown);
        Pasty food = new Pasty();
        hero.belongings.backpack.items.add(food);
        Holiday.clearCachedHoliday();
        String chineseName = HeroClass.WARRIOR.title();
        byte[] rng = Random.exportState();
        String before = JsonCodec.encode(new InternalGraphSnapshotter().capture(map("hero", hero)));
        for (int i = 0; i < 25; i++) {
            Map<String, Object> observation = PublicEnglishProjection.copy(PlayerObservation.capture(hero, null, "game"));
            assertEquals("warrior", ((Map<?, ?>) observation.get("hero")).get("class_name"));
            assertEquals("crimson potion", PublicEnglishProjection.copy(PlayerObservation.item(unknown, "backpack.0", false)).get("name"));
            assertEquals(Languages.CHI_SMPL, Messages.lang());
        }
        assertEquals(chineseName, HeroClass.WARRIOR.title());
        assertEquals("战士", chineseName);
        assertNull(SnapshotFields.read(Holiday.class, "cached"));
        assertEquals(before, JsonCodec.encode(new InternalGraphSnapshotter().capture(map("hero", hero))));
        assertArrayEquals(rng, Random.exportState());
        assertFalse(unknown.isKnown());
    }

    @Test
    void unknownIdentitiesRemainIndistinguishableInEnglishWithChineseGui() throws Exception {
        Map<String, Object> healing = PublicEnglishProjection.copy(PlayerObservation.item(potion(PotionOfHealing.class), "backpack.0", false));
        Map<String, Object> frost = PublicEnglishProjection.copy(PlayerObservation.item(potion(PotionOfFrost.class), "backpack.0", false));
        assertEquals(healing, frost);
        assertEquals("crimson potion", healing.get("name"));
        assertEquals("This flask contains a swirling colorful liquid. Who knows what it will do when drunk or thrown?",
                healing.get("description"));
        assertFalse((Boolean) healing.get("type_known"));
        assertEquals(Languages.CHI_SMPL, Messages.lang());
    }

    @Test
    void failingPublicGetterCannotLeaveOriginalCallbacksInEnglish() {
        Item broken = new Item() {
            @Override public String name() { throw new IllegalStateException("fixture getter failure"); }
        };
        assertThrows(IllegalStateException.class, () -> PlayerObservation.item(broken, "backpack.0", false));
        assertEquals(Languages.CHI_SMPL, Messages.lang());
        assertEquals("战士", HeroClass.WARRIOR.title());
    }

    private static <T extends Potion> T potion(Class<T> type) throws Exception {
        T item = allocate(type);
        Field color = Potion.class.getDeclaredField("color");
        color.setAccessible(true);
        color.set(item, "crimson");
        Field quantity = Item.class.getDeclaredField("quantity");
        quantity.setAccessible(true);
        quantity.set(item, 1);
        return item;
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeType.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return type.cast(unsafeType.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type));
    }
}
