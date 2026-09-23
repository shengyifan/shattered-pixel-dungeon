package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Poison;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.HolyLance;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Kinetic;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.RoundShield;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ElementalSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.FistSprite;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIcon;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.shatteredpixel.shatteredpixeldungeon.ui.GameplayIcons;
import com.watabou.gltextures.SmartTexture;
import com.watabou.gltextures.TextureCache;
import com.watabou.noosa.Image;
import com.watabou.noosa.MovieClip;
import com.watabou.noosa.TextureFilm;
import com.watabou.utils.RectF;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Native icon/sprite producers with resource-only textures, without actors, game turns or profiles. */
class GameplayIconsCatalogTest {
    @BeforeAll static void resources() { GameSnapshotterTest.resourceOnlyRuntime(); }

    @Test void nativeBuffBranchesKeepNamedVariantsAndStaleFramesCannotKeepTheirBinding() throws Exception {
        try (Textures ignored = new Textures()) {
            BuffIcon poison = new BuffIcon(new Poison(), false);
            assertEquals("purple", GameplayIcons.buff(BuffIndicator.POISON, poison).get("variant"));
            BuffIcon lance = new BuffIcon(new HolyLance.LanceCooldown(), false);
            assertEquals("olive", GameplayIcons.buff(BuffIndicator.TIME, lance).get("variant"));
            RoundShield.GuardTracker guard = new RoundShield.GuardTracker();
            BuffIcon shield = new BuffIcon(guard, false);
            assertFalse(GameplayIcons.buff(BuffIndicator.DUEL_GUARD, shield).containsKey("variant"));
            guard.hasBlocked = true; shield.refresh(guard);
            assertEquals("plum_tinted", GameplayIcons.buff(BuffIndicator.DUEL_GUARD, shield).get("variant"));
            shield.frame(poison.frame());
            Map<String,Object> stale = GameplayIcons.buff(BuffIndicator.DUEL_GUARD, shield);
            assertEquals("unmapped_indicator", stale.get("symbol"));
            assertTrue(stale.containsKey("presentation"));
        }
    }

    @Test void nativeConservedDamageGradientIsAnObservedFractionOnlyWhenItsSourceIsBound() throws Exception {
        try (Textures ignored = new Textures()) {
            Kinetic.ConservedDamage damage = new Kinetic.ConservedDamage();
            BuffIcon icon = new BuffIcon(damage, false);
            int[] stages = {0, 2, 5, 7, 10};
            float[] expected = {0, .2f, .5f, .7f, 1};
            for (int i = 0; i < stages.length; i++) {
                damage.setBonus(stages[i]); icon.refresh(damage);
                Map<String,Object> result = GameplayIcons.buff(BuffIndicator.WEAPON, icon, true);
                Map<?,?> charge = (Map<?,?>)result.get("charge");
                assertEquals(expected[i], ((Number)charge.get("fraction")).floatValue(), .00001f);
                assertEquals("displayed_gradient", charge.get("basis"));
                assertFalse(result.containsKey("damage")); assertFalse(result.containsKey("tint"));
                assertFalse(GameplayIcons.buff(BuffIndicator.WEAPON, icon).containsKey("charge"));
            }
            icon.hardlight(1,1,.6001f);
            Map<String,Object> channelSample=GameplayIcons.buff(BuffIndicator.WEAPON,icon,true);
            icon.hardlight(1,1,.6002f);
            assertEquals(channelSample,GameplayIcons.buff(BuffIndicator.WEAPON,icon,true),
                    "Subpixel renderer parameters must not expose finer conserved-damage precision");
            icon.hardlight(0, 0, 1);
            Map<String,Object> invalid = GameplayIcons.buff(BuffIndicator.WEAPON, icon, true);
            assertFalse(invalid.containsKey("charge")); assertTrue(invalid.containsKey("presentation"));
        }
    }

    @Test void eachNativeElementalAndFistAnimationKeepsItsDisplayedSubtypeWithoutAnActor() throws Exception {
        try (Textures ignored = new Textures()) {
            CharSprite[] sprites = {new ElementalSprite.Fire(), new ElementalSprite.NewbornFire(),
                    new ElementalSprite.Frost(), new ElementalSprite.Shock(), new ElementalSprite.Chaos(),
                    new FistSprite.Burning(), new FistSprite.Soiled(), new FistSprite.Rotting(),
                    new FistSprite.Rusted(), new FistSprite.Bright(), new FistSprite.Dark()};
            String[] symbols = {"fire_elemental_portrait", "newborn_fire_elemental_portrait",
                    "frost_elemental_portrait", "shock_elemental_portrait", "chaos_elemental_portrait",
                    "burning_fist_portrait", "soiled_fist_portrait", "rotting_fist_portrait",
                    "rusted_fist_portrait", "bright_fist_portrait", "dark_fist_portrait"};
            for (int i = 0; i < sprites.length; i++) {
                CharSprite sprite = sprites[i]; assertNull(sprite.ch);
                for (String name : new String[]{"idle", "run", "attack", "zap", "die"}) {
                    MovieClip.Animation animation = (MovieClip.Animation)SnapshotFields.read(sprite, name);
                    for (RectF frame : animation.frames) {
                        sprite.frame(frame);
                        Map<String,Object> result = GameplayIcons.worldImage(sprite);
                        assertEquals(symbols[i], result.get("symbol"), name);
                        assertFalse(result.containsKey("presentation"), result.toString());
                        sprite.brightness(1.5f); assertEquals(result, GameplayIcons.worldImage(sprite));
                    }
                }
            }
            Image unused = new Image(Assets.Sprites.FISTS);
            unused.frame(new TextureFilm(unused.texture, 24, 17).get(7));
            assertTrue(GameplayIcons.worldImage(unused).containsKey("presentation"));
        }
    }

    @Test void nativeSurfaceAvatarsMapTheirSelectedPublicClassFrame() throws Exception {
        try (Textures ignored = new Textures()) {
            Class<?> type = Class.forName("com.shatteredpixel.shatteredpixeldungeon.scenes.SurfaceScene$Avatar");
            Constructor<?> constructor = type.getDeclaredConstructor(HeroClass.class); constructor.setAccessible(true);
            for (HeroClass heroClass : HeroClass.values()) {
                Image avatar = (Image)constructor.newInstance(heroClass);
                Map<String,Object> result = GameplayIcons.worldImage(avatar);
                assertEquals(heroClass.name().toLowerCase(java.util.Locale.ROOT) + "_avatar", result.get("symbol"));
                assertFalse(result.containsKey("presentation"));
                assertTrue(GameplayIcons.image(avatar).isEmpty(), "The world route does not need a UI camera");
            }
        }
    }

    private static final class Textures implements AutoCloseable {
        private final Map<Object,SmartTexture> cache;
        private final Map<String,SmartTexture> previous = new LinkedHashMap<>();

        @SuppressWarnings("unchecked")
        Textures() throws Exception {
            cache = (Map<Object,SmartTexture>)SnapshotFields.read(TextureCache.class, "all");
            install(Assets.Interfaces.BUFFS_SMALL, 128, 64);
            install(Assets.Sprites.ELEMENTAL, 512, 32);
            install(Assets.Sprites.FISTS, 256, 128);
            install(Assets.Sprites.AVATARS, 256, 32);
        }

        private void install(String asset, int width, int height) throws Exception {
            SmartTexture texture = FloatingAppearanceTest.allocate(SmartTexture.class);
            texture.width = width; texture.height = height;
            previous.put(asset, cache.put(asset, texture));
        }

        @Override public void close() {
            for (Map.Entry<String,SmartTexture> entry : previous.entrySet()) {
                if (entry.getValue() == null) cache.remove(entry.getKey());
                else cache.put(entry.getKey(), entry.getValue());
            }
        }
    }
}
