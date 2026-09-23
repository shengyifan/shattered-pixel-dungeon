package com.shatteredpixel.shatteredpixeldungeon.ui;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.watabou.gltextures.TextureCache;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Image;
import com.watabou.noosa.TextureFilm;
import com.watabou.noosa.Visual;
import com.watabou.utils.RectF;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Public symbols selected by existing displayed images, without consulting their model owners. */
public final class GameplayIcons {
    private GameplayIcons() {}

    private static final Map<String,String> PORTRAITS = portraits();
    private static final Set<String> ICON_ASSETS = iconAssets();
    private static final Map<Integer,String> BUFFS = constants(BuffIndicator.class, "NONE", "SIZE_SMALL", "SIZE_LARGE");
    private static final Map<Integer,String> HEROES = constants(HeroIcon.class, "NONE", "SPELL_ACTION_OFFSET");

    /** The caller captures this result with the completed draw that made the image eligible. */
    public static Map<String,Object> image(Visual source) {
        if (!(source instanceof Image) || !visible(source)) return Collections.emptyMap();
        return imageSymbol((Image)source);
    }

    /** Only producers whose tint encodes a gameplay distinction opt into named variants. */
    public static Map<String,Object> variantImage(Visual source) {
        Map<String,Object> result = image(source);
        return source instanceof Image ? withColor(result, (Image)source) : result;
    }

    /** World collectors own scene, attachment and FOV checks; camera framing must not hide a symbol. */
    public static Map<String,Object> worldImage(Visual source) {
        if (!(source instanceof Image) || !present(source)) return Collections.emptyMap();
        return imageSymbol((Image)source);
    }

    private static Map<String,Object> imageSymbol(Image image) {
        if (image.texture == null || image.frame() == null) return Collections.emptyMap();
        String symbol = Icons.displayedSymbol(image);
        if (symbol != null) return symbol(symbol);
        String asset = TextureCache.cachedAssetKey(image.texture, ICON_ASSETS);
        if (Assets.Interfaces.BUFFS_SMALL.equals(asset)) return buffSymbol(gridIndex(image, 7, 7));
        if (Assets.Interfaces.BUFFS_LARGE.equals(asset)) return buffSymbol(gridIndex(image, 16, 16));
        if (Assets.Interfaces.HERO_ICONS.equals(asset)) return hero(gridIndex(image, 16, 16));
        if (Assets.Sprites.ITEM_ICONS.equals(asset)) {
            int index = matchingFrame(image, ItemSpriteSheet.Icons.film, ItemBadges.NAMES);
            return itemBadge(index);
        }
        if (Assets.Sprites.ITEMS.equals(asset)) {
            int index = matchingFrame(image, ItemSpriteSheet.film, Items.NAMES);
            String name = Items.NAMES.get(index);
            Map<String,Object> result = name == null ? unmapped() : symbol("item_" + name);
            if (image instanceof com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite) {
                com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite itemSprite=(com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite)image;
                Map<String,Object> glow = itemSprite.gameplayGlow(),status=itemSprite.gameplayItemStatus();
                if (!glow.isEmpty()||!status.isEmpty()) {
                    result = new LinkedHashMap<>(result);if(!glow.isEmpty())result.put("glow",glow);result.putAll(status);
                    result=Collections.unmodifiableMap(result);
                }
            }
            return result;
        }
        if (Assets.Effects.TEXT_ICONS.equals(asset)) return floating(gridIndex(image, 7, 8));
        String portrait = PORTRAITS.get(asset);
        if (portrait != null) {
            if (Assets.Sprites.AVATARS.equals(asset)) {
                // SurfaceScene.Avatar selects one 24x32 frame in native HeroClass order.
                String[] classes = {"warrior", "mage", "rogue", "huntress", "duelist", "cleric"};
                int index = gridIndex(image, 24, 32);
                return index >= 0 && index < classes.length ? symbol(classes[index] + "_avatar")
                        : partial(symbol(portrait), "variant", "unmapped_portrait_variant");
            }
            if (Assets.Sprites.FISTS.equals(asset)) {
                // FistSprite uses seven animation frames within each ten-frame subtype block.
                String[] forms = {"burning", "soiled", "rotting", "rusted", "bright", "dark"};
                int index = gridIndex(image, 24, 17);
                return index >= 0 && index / 10 < forms.length && index % 10 <= 6
                        ? symbol(forms[index / 10] + "_fist_portrait")
                        : partial(symbol(portrait), "variant", "unmapped_portrait_variant");
            }
            if (Assets.Sprites.ELEMENTAL.equals(asset)) {
                // ElementalSprite's complete native animation tables occupy fourteen frames per form.
                String[] forms = {"fire", "newborn_fire", "frost", "shock", "chaos"};
                int index = gridIndex(image, 12, 14);
                return index >= 0 && index / 14 < forms.length
                        ? symbol(forms[index / 14] + "_elemental_portrait")
                        : partial(symbol(portrait), "variant", "unmapped_portrait_variant");
            }
            return symbol(portrait);
        }
        return unmapped();
    }

    /** An index already selected for the native Buff image; never calls Buff.icon or tintIcon. */
    public static Map<String,Object> buff(int displayedIndex, Image image) {
        return buff(displayedIndex, image, false);
    }

    /** The source-bound flag identifies Kinetic's displayed gradient, never its backing damage. */
    public static Map<String,Object> buff(int displayedIndex, Image image, boolean conservedDamageGradient) {
        if (image == null || image.texture == null || image.frame() == null) return unmapped();
        String asset = TextureCache.cachedAssetKey(image.texture, ICON_ASSETS);
        int actualIndex = Assets.Interfaces.BUFFS_SMALL.equals(asset) ? gridIndex(image, 7, 7)
                : Assets.Interfaces.BUFFS_LARGE.equals(asset) ? gridIndex(image, 16, 16) : -1;
        if (actualIndex < 0 || actualIndex != displayedIndex) {
            return partial(unmapped(), "symbol", "stale_indicator_binding");
        }
        if (conservedDamageGradient) return conservedDamageGradient(buffSymbol(displayedIndex), image);
        return withColor(buffSymbol(displayedIndex), image);
    }

    private static Map<String,Object> conservedDamageGradient(Map<String,Object> symbol, Image image) {
        float fraction;
        if (image.ra == 0 && image.ga == 0 && image.ba == 0 && image.rm == 1
                && image.gm == 1 && Float.isFinite(image.bm) && image.bm >= 0 && image.bm <= 1) {
            fraction = (1 - GameplayStatus.displayedChannel(image.bm)) / 2;
        } else if (image.ra == 0 && image.ga == 0 && image.ba == 0 && image.rm == 1
                && image.bm == 0 && Float.isFinite(image.gm) && image.gm >= 0 && image.gm <= 1) {
            fraction = 1 - GameplayStatus.displayedChannel(image.gm) / 2;
        } else {
            return partial(symbol, "charge", "unmapped_displayed_gradient");
        }
        Map<String,Object> charge = new LinkedHashMap<>();
        charge.put("fraction", Math.max(0, Math.min(1, fraction)));
        charge.put("basis", "displayed_gradient");
        Map<String,Object> result = new LinkedHashMap<>(symbol);
        result.put("charge", Collections.unmodifiableMap(charge));
        return Collections.unmodifiableMap(result);
    }

    private static Map<String,Object> buffSymbol(int displayedIndex) {
        if (displayedIndex == BuffIndicator.NONE) return Collections.emptyMap();
        String name = BUFFS.get(displayedIndex);
        return name == null ? unmapped() : symbol("buff_" + name);
    }

    /** Constructor-bound or exact displayed frame index, never a current ability/model callback. */
    public static Map<String,Object> hero(int displayedIndex) {
        if (displayedIndex == HeroIcon.NONE) return Collections.emptyMap();
        String name = HEROES.get(displayedIndex);
        if (name == null && displayedIndex >= HeroIcon.GUIDING_LIGHT + HeroIcon.SPELL_ACTION_OFFSET
                && displayedIndex <= HeroIcon.STASIS + HeroIcon.SPELL_ACTION_OFFSET) {
            name = HEROES.get(displayedIndex - HeroIcon.SPELL_ACTION_OFFSET);
        }
        return name == null ? unmapped() : symbol("hero_" + name);
    }

    /** A badge actually displayed beside an item, not the identity of an unidentified item. */
    public static Map<String,Object> itemBadge(int displayedIndex) {
        String name = ItemBadges.NAMES.get(displayedIndex);
        return name == null ? unmapped() : symbol("item_badge_" + name);
    }

    /** Native FloatingText's public icon vocabulary, without invoking its combat-reason logic. */
    public static Map<String,Object> floating(int displayedIndex) {
        if (displayedIndex == -1) return Collections.emptyMap();
        String name;
        switch (displayedIndex) {
            case 0: name = "physical_damage"; break;
            case 1: name = "physical_damage_armor_piercing"; break;
            case 2: name = "magic_damage"; break;
            case 3: name = "pickaxe_damage"; break;
            case 5: name = "hunger"; break;
            case 6: name = "burning"; break;
            case 7: name = "shocking"; break;
            case 8: name = "frost"; break;
            case 9: name = "water"; break;
            case 10: name = "bleeding"; break;
            case 11: name = "toxic"; break;
            case 12: name = "corrosion"; break;
            case 13: name = "poison"; break;
            case 14: name = "ooze"; break;
            case 15: name = "deferred"; break;
            case 16: name = "corruption"; break;
            case 17: name = "amulet"; break;
            case 18: name = "healing"; break;
            case 19: name = "shielding"; break;
            case 20: name = "experience"; break;
            case 21: name = "strength"; break;
            case 23: name = "gold"; break;
            case 24: name = "energy"; break;
            default: name = hitOrMiss(displayedIndex);
        }
        return name == null ? unmapped() : symbol(name);
    }

    /** The eight public SpellSprite symbols; an unknown index never becomes an asset descriptor. */
    public static Map<String,Object> spell(int displayedIndex) {
        String[] names = {"food", "map", "charge", "berserk", "ankh", "haste", "vision", "purity"};
        return displayedIndex >= 0 && displayedIndex < names.length ? symbol("spell_" + names[displayedIndex]) : unmapped();
    }

    public static Map<String,Object> unmapped() {
        return partial(symbol("unmapped_indicator"), "symbol", "unmapped_indicator");
    }

    /** GameLog uses these exact colors for GLog's five public message categories. */
    public static Map<String,Object> logTone(int displayedColor) {
        switch (displayedColor) {
            case CharSprite.DEFAULT: return tone("info");
            case CharSprite.POSITIVE: return tone("positive");
            case CharSprite.NEGATIVE: return tone("negative");
            case CharSprite.WARNING: return tone("warning");
            case CharSprite.NEUTRAL: return tone("highlight");
            default: return partial(tone("unmapped"), "tone", "unmapped_log_tone");
        }
    }

    /** Native floating feedback colors; yellow is neutral feedback rather than a log highlight. */
    public static Map<String,Object> feedback(int displayedColor) {
        switch (displayedColor) {
            case CharSprite.DEFAULT: return tone("info");
            case CharSprite.POSITIVE: return tone("positive");
            case CharSprite.NEGATIVE: return tone("negative");
            case CharSprite.WARNING: return tone("warning");
            case CharSprite.NEUTRAL: return tone("neutral");
            default: return partial(tone("unmapped"), "tone", "unmapped_feedback_tone");
        }
    }

    /** Generic text keeps known color distinctions without assigning a context-specific outcome. */
    public static Map<String,Object> textTone(int displayedColor) {
        switch (displayedColor) {
            // Native version/depth labels and the ordinary hero experience/level palette.
            case 0xCACFC2: case 0x888888: case 0xCCCCCC: case 0xFFFFAA: return tone("normal");
            case 0xFFFFFF: return tone("white");
            case 0x000000: return tone("black");
            case 0x00FF00: return tone("green");
            case 0xFF0000: return tone("red");
            case 0xFF8800: return tone("orange");
            case 0xFFFF00: return tone("yellow");
            case 0xFFFF44: return tone("title");
            case 0xAAAAAA: return tone("gray");
            default: return partial(tone("unmapped"), "tone", "unmapped_text_tone");
        }
    }

    private static Map<String,Object> tone(String tone) {
        return Collections.<String,Object>singletonMap("tone", tone);
    }

    private static String hitOrMiss(int index) {
        String[] hit = {"weapon", "armor", "bless", "hex", "daze", "accuracy", "evasion", "liquid_agility",
                "sword_dance", "surprise", "precise_assault", "projectile_momentum"};
        String[] miss = {"weapon", "armor", "bless", "hex", "daze", "accuracy", "evasion", "liquid_agility",
                "defense", "tuft", "freerunning"};
        if (index >= 36 && index < 36 + hit.length) return "hit_" + hit[index - 36];
        if (index >= 54 && index < 54 + hit.length) return "armor_piercing_hit_" + hit[index - 54];
        if (index >= 72 && index < 72 + miss.length) return "miss_" + miss[index - 72];
        return null;
    }

    private static Map<String,Object> withColor(Map<String,Object> value, Image image) {
        if (value.isEmpty() || image == null) return value;
        if (image.ra == 0 && image.ga == 0 && image.ba == 0 && image.rm == 1 && image.gm == 1 && image.bm == 1) return value;
        String color = colorName(image);
        Map<String,Object> result = new LinkedHashMap<>(value);
        if (color == null) return partial(result, "variant", "unmapped_indicator_variant");
        result.put("variant", color);
        return Collections.unmodifiableMap(result);
    }

    /** Exact native tint variants, named only as observed colors, never hidden Buff stages. */
    private static String colorName(Image image) {
        if (image.rm == -1 && image.gm == -1 && image.bm == -1
                && image.ra == 1 && image.ga == 1 && image.ba == 1) return "inverted";
        // RoundShield's GuardTracker applies this public, fixed overlay to the displayed icon.
        if (image.rm == 0.5f && image.gm == 0.5f && image.bm == 0.5f
                && image.ra == 0x65 / 255f * 0.5f && image.ga == 0x1f / 255f * 0.5f
                && image.ba == 0x66 / 255f * 0.5f) return "plum_tinted";
        if (image.ra != 0 || image.ga != 0 || image.ba != 0) return null;
        float r = image.rm, g = image.gm, b = image.bm;
        if (r == 0 && g == 0 && b == 0) return "black";
        if (r == 1 && g == 0 && b == 0) return "red";
        if (r == 0 && g == 1 && b == 0) return "green";
        if (r == 0 && g == 0 && b == 1) return "blue";
        if (r == 1 && g == 1 && b == 0) return "yellow";
        if (r == 0 && g == 1 && b == 1) return "cyan";
        if (r == 1 && g == 0 && b == 1) return "magenta";
        // Exact hex hardlight branches used by ChampionEnemy and Combo; no actor fields are read.
        if (r == 1 && g == 0x88 / 255f && b == 0) return "tangerine";
        if (r == 0x88 / 255f && g == 0 && b == 1) return "blue_violet";
        if (r == 0 && g == 0x88 / 255f && b == 1) return "azure";
        if (r == 1 && g == 0x22 / 255f && b == 0x22 / 255f) return "pale_red";
        if (r == 0xCC / 255f && g == 1 && b == 0) return "lime";
        if (r == 0.5f && g == 1 && b == 0) return "yellow_green";
        if (r == 1 && g == 0.67f && b == 0) return "amber";
        if (r == 1 && g == 0.6f && b == 0) return "orange_yellow";
        if (r == 1 && g == 0.5f && b == 0) return "orange";
        if (r == 1 && g == 0.33f && b == 0) return "orange_red";
        if (r == 1 && g == 0.3f && b == 0) return "deep_orange";
        if (r == 1 && g == 0.8f && b == 0) return "gold";
        if (r == 0.2f && g == 0.6f && b == 1) return "cornflower_blue";
        if (r == 1.8f && g == 1.8f && b == 0.6f) return "bright_pale_yellow";
        if (r == 0 && g == 0.6f && b == 1) return "ocean_blue";
        if (r == 0 && g == 0.75f && b == 0.75f) return "teal";
        if (r == 1 && g == 2 && b == 1.25f) return "bright_sea_green";
        if (r == 1 && g == 0 && b == 2) return "bright_magenta";
        if (r == 0.5f && g == 0.2f && b == 1) return "lavender_violet";
        if (r == 2 && g == 0.75f && b == 0) return "bright_orange";
        if (r == 3 && g == 3 && b == 2) return "brilliant_pale_yellow";
        if (r == 1 && g == 1 && b == 2) return "bright_lavender";
        if (r == 1 && g == 1.67f && b == 1) return "bright_pale_green";
        if (r == 0.15f && g == 0.2f && b == 0.5f) return "navy";
        if (r == 0 && g == 0.35f && b == 0.15f) return "forest_green";
        if (r == 0.7f && g == 0.4f && b == 0.7f) return "mauve";
        if (r == 0.5f && g == 0 && b == 1) return "violet";
        if (r == 0.35f && g == 0 && b == 0.7f) return "dark_violet";
        if (r == 0.85f && g == 0 && b == 1) return "orchid";
        if (r == 1.43f && g == 1.43f && b == 1.43f) return "bright_white";
        if (r == 1.43f && g == 1.43f && b == 0) return "bright_yellow";
        if (r == 0.67f && g == 0.67f && b == 0) return "olive";
        if (r == 1 && g == 0.2f && b == 0.2f) return "salmon_red";
        if (r == 0.5f && g == 1 && b == 0.5f) return "pale_green";
        if (r == 0.5f && g == 0.5f && b == 1) return "pale_blue";
        if (r == 0.33f && g == 0.33f && b == 1) return "violet_blue";
        if (r == 0.25f && g == 1.5f && b == 1) return "bright_mint";
        if (r == 1 && g == 2.1f && b == 2.5f) return "bright_pale_blue";
        if (r == 0 && g == 2 && b == 3) return "bright_cyan";
        if (r == 1 && g == 1.5f && b == 0) return "bright_yellow_green";
        if (r == 0.84f && g == 0.79f && b == 0.65f) return "parchment";
        if (r == 0.6f && g == 0.2f && b == 0.6f) return "purple";
        if (r == 0 && g == 0.75f && b == 1) return "sky_blue";
        if (r == 1 && g == 0.33f && b == 0.2f) return "coral";
        if (r == 1.9f && g == 2.4f && b == 3.25f) return "bright_steel_blue";
        if (r == 0.5f && g == 1 && b == 2) return "bright_sky_blue";
        if (r == 1 && g == 0.5f && b == 2) return "bright_violet";
        if (r == 0.5f && g == 0.5f && b == 0.5f) return "gray";
        if (r == 0.6f && g == 0.6f && b == 0.6f) return "light_gray";
        if (r == 0.3f && g == 0.3f && b == 0.3f) return "dark_gray";
        return null;
    }

    private static Map<String,Object> symbol(String symbol) {
        return Collections.<String,Object>singletonMap("symbol", symbol);
    }

    private static Map<String,Object> partial(Map<String,Object> value, String field, String code) {
        Map<String,Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("field", field); diagnostic.put("code", code);
        List<Object> diagnostics = new ArrayList<>();
        Object previous = value.get("presentation");
        if (previous instanceof Map) {
            Object earlier = ((Map<?,?>)previous).get("diagnostics");
            if (earlier instanceof List) diagnostics.addAll((List<?>)earlier);
        }
        diagnostics.add(Collections.unmodifiableMap(diagnostic));
        Map<String,Object> presentation = new LinkedHashMap<>();
        presentation.put("status", "partial");
        presentation.put("diagnostics", Collections.unmodifiableList(diagnostics));
        Map<String,Object> result = new LinkedHashMap<>(value);
        result.put("presentation", Collections.unmodifiableMap(presentation));
        return Collections.unmodifiableMap(result);
    }

    private static Map<Integer,String> constants(Class<?> catalog, String... excluded) {
        Set<String> excludedNames = new TreeSet<>(Arrays.asList(excluded));
        Map<Integer,String> names = new LinkedHashMap<>();
        for (Field field : catalog.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (field.getType() != int.class || !Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers)
                    || !Modifier.isFinal(modifiers) || excludedNames.contains(field.getName())) continue;
            try {
                int index = field.getInt(null);
                String name = field.getName().toLowerCase(Locale.ROOT);
                if (names.put(index, name) != null) throw new IllegalStateException("Ambiguous public icon catalog");
            } catch (IllegalAccessException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        return Collections.unmodifiableMap(names);
    }

    /** Asset constants describe the displayed family only, never which actor owns a portrait. */
    private static Map<String,String> portraits() {
        Map<String,String> names = new LinkedHashMap<>();
        for (Field field : Assets.Sprites.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (field.getType() != String.class || !Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers)
                    || !Modifier.isFinal(modifiers) || field.getName().equals("ITEMS")
                    || field.getName().equals("ITEM_ICONS")) continue;
            try {
                names.put((String)field.get(null), field.getName().toLowerCase(Locale.ROOT) + "_portrait");
            } catch (IllegalAccessException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        return Collections.unmodifiableMap(names);
    }

    private static Set<String> iconAssets() {
        Set<String> assets = new TreeSet<>(PORTRAITS.keySet());
        assets.addAll(Arrays.asList(Assets.Interfaces.BUFFS_SMALL, Assets.Interfaces.BUFFS_LARGE,
                Assets.Interfaces.HERO_ICONS, Assets.Sprites.ITEMS, Assets.Sprites.ITEM_ICONS,
                Assets.Effects.TEXT_ICONS));
        return Collections.unmodifiableSet(assets);
    }

    private static final class Items {
        static final Map<Integer,String> NAMES = constants(ItemSpriteSheet.class, "SIZE", "DARTS");
    }

    private static final class ItemBadges {
        static final Map<Integer,String> NAMES = constants(ItemSpriteSheet.Icons.class, "SIZE");
    }

    private static int matchingFrame(Image image, TextureFilm film, Map<Integer,String> names) {
        RectF current = image.frame();
        for (Integer index : names.keySet()) {
            RectF known = film.get(index);
            if (known != null && current.left == known.left && current.top == known.top
                    && current.right == known.right && current.bottom == known.bottom) return index;
        }
        return -1;
    }

    private static int gridIndex(Image image, int width, int height) {
        RectF frame = image.frame();
        if (!Float.isFinite(frame.left + frame.top + frame.right + frame.bottom)) return -1;
        float uw = (float)width / image.texture.width, vh = (float)height / image.texture.height;
        int column = Math.round(frame.left / uw), row = Math.round(frame.top / vh);
        int columns = image.texture.width / width, rows = image.texture.height / height;
        if (column < 0 || column >= columns || row < 0 || row >= rows
                || frame.left != column * uw || frame.top != row * vh
                || frame.right != (column + 1) * uw || frame.bottom != (row + 1) * vh) return -1;
        return column + row * columns;
    }

    private static boolean visible(Visual source) {
        if (!present(source)) return false;
        Camera camera = RenderedAppearance.camera(source);
        Camera.DrawnTransform transform = camera == null ? null : camera.observedTransform();
        if (camera == null || !camera.visible || transform == null || source.width <= 0 || source.height <= 0
                || !Float.isFinite(source.x + source.y + source.width + source.height + source.angle
                + source.scale.x + source.scale.y + source.origin.x + source.origin.y)) return false;
        double cosine = Math.cos(Math.toRadians(source.angle)), sine = Math.sin(Math.toRadians(source.angle));
        for (float x : new float[]{0, source.width}) for (float y : new float[]{0, source.height}) {
            float dx = (x - source.origin.x) * source.scale.x, dy = (y - source.origin.y) * source.scale.y;
            float px = source.x + source.origin.x + (float)(dx * cosine - dy * sine);
            float py = source.y + source.origin.y + (float)(dx * sine + dy * cosine);
            if (!Float.isFinite(px + py) || px < transform.scrollX || py < transform.scrollY
                    || px > transform.scrollX + transform.width || py > transform.scrollY + transform.height) return false;
        }
        return source.scale.x != 0 && source.scale.y != 0;
    }

    private static boolean present(Visual source) {
        if (!Float.isFinite(source.alpha()) || source.alpha() <= 0) return false;
        for (Gizmo node = source; node != null; node = node.parent) if (!node.exists || !node.visible) return false;
        return true;
    }
}
