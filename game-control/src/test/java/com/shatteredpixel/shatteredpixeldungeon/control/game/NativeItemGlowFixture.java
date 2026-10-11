package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Crystal;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Sword;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.ui.ItemSlot;
import com.watabou.gltextures.SmartTexture;
import com.watabou.gltextures.TextureCache;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Group;
import com.watabou.noosa.VisualCue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/**
 * Native item-display fixtures consumed by the production Python decoder tests.
 * Uses synthetic textures and in-memory preferences, without a backend, GL context,
 * game launch, player profile, or save/audit access. Stdout contains one JSON value.
 */
public final class NativeItemGlowFixture {
    private static final String SCOPE = "isolated-item-glow-scope";
    private static final String REVISION = "isolated-item-glow-revision";
    private static final List<String> VIEWS = List.of("play", "full", "src");

    private NativeItemGlowFixture() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("This fixture takes no arguments");
        GameSnapshotterTest.resourceOnlyRuntime();
        try (Resources ignored = new Resources()) {
            List<Object> cases = new ArrayList<>();
            List<Map<String,Object>> observations = new ArrayList<>();
            float[] fixtureAmounts = {100, 50, 10, 0};
            String[] stages = {"intact", "flawed", "cracked", "unmapped"};
            for (int index = 0; index < stages.length; index++) {
                Sword sword;
                if ("unmapped".equals(stages[index])) {
                    // Exercise the native fallback, not a hand-authored partial map.
                    sword = new Sword() {
                        @Override public ItemSprite.Glowing glowing() {
                            return new ItemSprite.Glowing(0x123456);
                        }
                    };
                } else {
                    Crystal crystal = new Crystal();
                    crystal.setDurability(fixtureAmounts[index]);
                    sword = new Sword();
                    sword.enchant(crystal);
                }

                ItemSprite sprite = new ItemSprite(sword);
                ItemSlot slot = slot(sword, sprite);
                Map<String,Object> nativeGlow = sprite.gameplayGlow();
                Map<String,Object> nativeStatus = slot.gameplayStatus();
                Map<String,Object> nativeCue = heapCue(sword);

                Map<String,Object> node = map("id", "item-slot", "role", "button");
                node.putAll(nativeStatus);
                UiProjectionHints.Node hint = new UiProjectionHints.Node(false, List.of(), null, Map.of(),
                        map("kind", "item", "loc", "backpack.0"), null, false);
                Map<String,Object> ui = new UiProjectionHints(Map.of("item-slot", hint))
                        .attach(map("controls", List.of(node)));
                Map<String,Object> observation = GameplayObservation.merge(map(
                        "inventory", List.of(PlayerObservation.item(sword, "backpack.0", false)),
                        "ui", ui,
                        "visual_cues", map("status", "last_observed", "cues", List.of(nativeCue))));
                observations.add(observation);

                List<Object> responses = new ArrayList<>();
                for (String view : VIEWS) {
                    Map<String,Object> state = response("state-" + stages[index] + "-" + view,
                            map("scope_id", SCOPE, "state_version", REVISION, "observation", observation), view, true);
                    Map<String,Object> actions = response("actions-" + stages[index] + "-" + view,
                            map("scope_id", SCOPE, "state_version", REVISION,
                                    "observation", map("ui", GameplayObservation.uiForActions(observation))), view, true);
                    responses.add(map("view", view, "state", state, "actions", actions));
                }
                cases.add(map("stage", stages[index], "native_glow", nativeGlow,
                        "native_slot_status", nativeStatus, "native_world_cue", nativeCue,
                        "responses", responses));
            }

            List<Object> frozen = new ArrayList<>();
            for (int[] pair : new int[][]{{0, 2}, {3, 1}}) {
                for (String view : VIEWS) {
                    // Each frozen snapshot owns its own item table, even with the same locator.
                    frozen.add(map("view", view, "before_stage", stages[pair[0]], "after_stage", stages[pair[1]],
                            "wire", response("frozen-" + stages[pair[0]] + "-" + view,
                                    map("before", observations.get(pair[0]), "after", observations.get(pair[1])), view, false)));
                }
            }

            Map<String,Object> locations = new LinkedHashMap<>();
            for (Class<?> type : List.of(ItemSprite.class, ItemSlot.class, PlayerObservation.class,
                    GameplayObservation.class, CompactProtocol.class)) {
                locations.put(type.getSimpleName(), type.getProtectionDomain().getCodeSource().getLocation().toString());
            }
            System.out.println(JsonCodec.encode(map("fixture", "native_item_glow",
                    "class_locations", locations, "cases", cases, "frozen", frozen)));
        }
    }

    private static Map<String,Object> response(String id, Map<String,Object> canonical, String view, boolean live) {
        return CompactProtocol.success(id, SCOPE, "completed", canonical, live,
                "src".equals(view), "full".equals(view));
    }

    private static ItemSlot slot(Sword sword, ItemSprite sprite) throws Exception {
        // Only visual construction is omitted. The production gameplayStatus method runs.
        ItemSlot slot = FloatingAppearanceTest.allocate(ItemSlot.class);
        slot.exists = slot.alive = slot.visible = slot.active = true;
        slot.camera = new Camera(0, 0, 100, 100, 1);
        sprite.x = sprite.y = 10;
        sprite.camera = slot.camera;
        sprite.parent = slot;
        FloatingAppearanceTest.field(slot, ItemSlot.class, "sprite", sprite);
        FloatingAppearanceTest.field(slot, ItemSlot.class, "item", sword);
        return slot;
    }

    private static Map<String,Object> heapCue(Sword sword) {
        ItemSprite sprite = new ItemSprite(sword);
        new Group().add(sprite);
        sprite.heap = new Heap();
        sprite.place(22);
        VisualCue cue = sprite.gameplayGlowCue();
        if (cue == null) throw new AssertionError("The bound fixture heap must expose its selected native glow");
        return map("kind", cue.kind, "cell", cue.cell, "appearance", cue.appearance);
    }

    private static final class Grid extends Level {
        Grid() { width = height = 10; length = 100; }
        @Override protected boolean build() { return false; }
        @Override protected void createMobs() { }
        @Override protected void createItems() { }
    }

    private static final class Resources implements AutoCloseable {
        private final Level previousLevel = Dungeon.level;
        private final Camera previousCamera = Camera.main;
        private final Map<Object,SmartTexture> cache;
        private final SmartTexture previousTexture;

        @SuppressWarnings("unchecked") Resources() throws Exception {
            Dungeon.level = new Grid();
            Camera.main = new Camera(0, 0, 100, 100, 1);
            cache = (Map<Object,SmartTexture>) SnapshotFields.read(TextureCache.class, "all");
            SmartTexture texture = FloatingAppearanceTest.allocate(SmartTexture.class);
            texture.width = 256;
            texture.height = 512;
            previousTexture = cache.put(Assets.Sprites.ITEMS, texture);
        }

        @Override public void close() {
            Dungeon.level = previousLevel;
            Camera.main = previousCamera;
            if (previousTexture == null) cache.remove(Assets.Sprites.ITEMS);
            else cache.put(Assets.Sprites.ITEMS, previousTexture);
        }
    }
}
