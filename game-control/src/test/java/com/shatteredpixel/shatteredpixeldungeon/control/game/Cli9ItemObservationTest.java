package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.control.game.text.TextProvenance;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.stones.StoneOfAugmentation;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Crystal;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Venomous;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Vorpal;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.watabou.gltextures.SmartTexture;
import com.watabou.gltextures.TextureCache;
import com.watabou.noosa.Game;
import com.watabou.noosa.RuntimeObserver;
import com.watabou.utils.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** v4 native item displays, using synthetic texture resources and no game profile. */
class Cli9ItemObservationTest {
    @BeforeAll static void resources() { GameSnapshotterTest.resourceOnlyRuntime(); }

    @Test void augmentationBackClosesTheWindowBeforeReopeningTheNativeItemSelector() throws Exception {
        Object priorUser = SnapshotFields.read(Item.class, "curUser");
        try {
            Hero hero = new Hero();
            hero.pos = 41;
            FloatingAppearanceTest.field(null, Item.class, "curUser", hero);
            TrackingStone stone = new TrackingStone();
            BareAugment window = FloatingAppearanceTest.allocate(BareAugment.class);
            // Only visual construction is omitted; the production onBackPressed method executes.
            for (java.lang.reflect.Field field : StoneOfAugmentation.WndAugment.class.getDeclaredFields()) {
                if (field.getType() == StoneOfAugmentation.class) {
                    field.setAccessible(true);
                    field.set(window, stone);
                }
            }
            window.events = stone.events;
            window.onBackPressed();
            assertEquals(List.of("hide", "select:41"), stone.events);
            assertEquals(1, stone.quantity());
        } finally { FloatingAppearanceTest.field(null, Item.class, "curUser", priorUser); }
    }

    @Test void crystalGlowReportsOnlyTheNativeSelectedStageAndKeepsItUntilReselected() throws Exception {
        try (Textures ignored = new Textures()) {
            Crystal crystal = new Crystal();
            ItemSprite sprite = new ItemSprite(ItemSpriteSheet.SWORD, crystal.glowing());
            Map<String, Object> intact = Map.of("variant", "crystal_blue", "kind", "crystal_condition", "stage", "intact");
            assertEquals(intact, sprite.gameplayGlow());
            crystal.setDurability(50);
            assertEquals(intact, sprite.gameplayGlow(), "Observation must not reread an enchantment's private condition");
            sprite.glow(crystal.glowing());
            assertEquals("flawed", sprite.gameplayGlow().get("stage"));
            crystal.setDurability(10);
            assertEquals("flawed", sprite.gameplayGlow().get("stage"));
            sprite.glow(crystal.glowing());
            assertEquals("cracked", sprite.gameplayGlow().get("stage"));
            crystal.setThrownWep();
            sprite.glow(crystal.glowing());
            assertEquals(intact, sprite.gameplayGlow(), "Thrown crystal weapons select the native intact glow");
            sprite.visible = false;
            assertTrue(sprite.gameplayGlow().isEmpty());
            sprite.visible = true;
            sprite.revive();
            assertTrue(sprite.gameplayGlow().isEmpty(), "Pool reuse releases the selected native source");
        }
    }

    @Test void crystalSelectionThresholdsDoNotExposeRawDurabilityOrPulseState() throws Exception {
        try (Textures ignored = new Textures()) {
            Crystal crystal = new Crystal();
            ItemSprite sprite = new ItemSprite(ItemSpriteSheet.SWORD);
            float[] amounts = {100, 51, 50, 11, 10, 0};
            String[] stages = {"intact", "intact", "flawed", "flawed", "cracked", "cracked"};
            for (int i = 0; i < amounts.length; i++) {
                crystal.setDurability(amounts[i]);
                ItemSprite.Glowing nativeGlow = crystal.glowing();
                sprite.glow(nativeGlow);
                Map<String, Object> fact = sprite.gameplayGlow();
                assertEquals(stages[i], fact.get("stage"));
                assertEquals(3, fact.size());
                float period = nativeGlow.period;
                try {
                    nativeGlow.period = 99;
                    sprite.brightness(.1f);
                    assertEquals(fact, sprite.gameplayGlow());
                } finally { nativeGlow.period = period; }
            }
        }
    }

    @Test void newGlowPaletteComesFromSelectedNativeSpritesWithoutIdentifyingTheEnchantment() throws Exception {
        try (Textures ignored = new Textures()) {
            ItemSprite sprite = new ItemSprite(ItemSpriteSheet.SWORD, new Venomous().glowing());
            assertEquals(Map.of("variant", "vivid_purple"), sprite.gameplayGlow());
            sprite.glow(new Vorpal().glowing());
            assertEquals(Map.of("variant", "muted_red"), sprite.gameplayGlow());
            assertThrows(IllegalArgumentException.class,
                    () -> new ItemSprite.Glowing(0).gameplayHint("crystal_10"));
        }
    }

    @Test void repeatedCrystalObservationDoesNotConsumeRngOrChangeNativeDescription() throws Exception {
        try (Textures ignored = new Textures()) {
            Crystal crystal = new Crystal();
            crystal.setDurability(22);
            ItemSprite sprite = new ItemSprite(ItemSpriteSheet.SWORD, crystal.glowing());
            String before = crystal.desc();
            Random.pushGenerator(939);
            long expected;
            try { expected = Random.Long(); } finally { Random.popGenerator(); }
            Random.pushGenerator(939);
            try {
                for (int i = 0; i < 20; i++) sprite.gameplayGlow();
                assertEquals(expected, Random.Long());
                assertEquals(before, crystal.desc());
            } finally { Random.popGenerator(); }
        }
    }

    @Test void everyCrystalDescriptionBranchRetainsEnglishResourceProvenance() {
        RuntimeObserver priorObserver = Game.observer;
        Languages priorLanguage = Messages.selectedLanguage();
        TextProvenance texts = new TextProvenance();
        try {
            Game.observer = new RuntimeObserver() {
                @Override public String onTextResource(String value, String key, String language, Object[] args, String template) {
                    return texts.onTextResource(value, key, language, args, template);
                }
                @Override public String onTextOperation(String op, String value, Object... args) {
                    return texts.onTextOperation(op, value, args);
                }
            };
            Crystal crystal = new Crystal();
            for (float amount : new float[]{100, 75, 50, 10}) {
                crystal.setDurability(amount);
                Messages.setup(Languages.ENGLISH);
                String expected = crystal.desc();
                Messages.setup(Languages.CHI_SMPL);
                String shown = crystal.desc();
                Map<String, Object> rendered = texts.render(texts.capture(null, shown, false));
                assertEquals(expected, rendered.get("text"));
                assertEquals("complete", rendered.get("translation_status"));
                assertNotNull(rendered.get("source"));
            }
            crystal.setThrownWep();
            Messages.setup(Languages.ENGLISH);
            String expected = crystal.desc();
            Messages.setup(Languages.CHI_SMPL);
            String shown = crystal.desc();
            assertEquals(expected, texts.render(texts.capture(null, shown, false)).get("text"));
        } finally {
            Game.observer = priorObserver;
            Messages.setup(priorLanguage);
            texts.clear();
        }
    }

    private static final class Textures implements AutoCloseable {
        final Map<Object, SmartTexture> cache;
        final SmartTexture previous;
        @SuppressWarnings("unchecked") Textures() throws Exception {
            cache = (Map<Object, SmartTexture>) SnapshotFields.read(TextureCache.class, "all");
            SmartTexture texture = FloatingAppearanceTest.allocate(SmartTexture.class);
            texture.width = 256;
            texture.height = 512;
            previous = cache.put(Assets.Sprites.ITEMS, texture);
        }
        @Override public void close() {
            if (previous == null) cache.remove(Assets.Sprites.ITEMS);
            else cache.put(Assets.Sprites.ITEMS, previous);
        }
    }

    private static final class TrackingStone extends StoneOfAugmentation {
        final List<String> events = new ArrayList<>();
        @Override protected void activate(int cell) { events.add("select:" + cell); }
    }

    private static final class BareAugment extends StoneOfAugmentation.WndAugment {
        List<String> events;
        BareAugment(StoneOfAugmentation stone) { stone.super(null); }
        @Override public void hide() { events.add("hide"); }
    }
}
