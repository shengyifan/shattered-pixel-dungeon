package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Crystal;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Sword;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.ui.ItemSlot;
import com.watabou.gltextures.SmartTexture;
import com.watabou.gltextures.TextureCache;
import com.watabou.noosa.Camera;
import java.util.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** Isolated synthetic-texture native producer probe: no backend, GL context, or game profile. */
public final class NativeCrystalGlowProbe {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        GameSnapshotterTest.resourceOnlyRuntime();
        Map<Object,SmartTexture> cache=(Map<Object,SmartTexture>)SnapshotFields.read(TextureCache.class,"all");
        SmartTexture texture=FloatingAppearanceTest.allocate(SmartTexture.class);
        texture.width=256;texture.height=512;cache.put(Assets.Sprites.ITEMS,texture);
        List<Object> cases=new ArrayList<>();
        float[] amounts={100,50,10};
        String[] stages={"intact","flawed","cracked"};
        for(int i=0;i<amounts.length;i++) {
            Crystal crystal=new Crystal();crystal.setDurability(amounts[i]);
            Sword sword=new Sword();sword.enchant(crystal);
            // The sprite selects the native item image and current native glow.
            ItemSprite sprite=new ItemSprite(sword);
            ItemSlot slot=FloatingAppearanceTest.allocate(ItemSlot.class);
            slot.exists=slot.alive=slot.visible=slot.active=true;
            slot.camera=new Camera(0,0,100,100,1);
            sprite.x=sprite.y=10;sprite.camera=slot.camera;sprite.parent=slot;
            FloatingAppearanceTest.field(slot,ItemSlot.class,"sprite",sprite);
            FloatingAppearanceTest.field(slot,ItemSlot.class,"item",sword);
            Map<String,Object> nativeGlow=sprite.gameplayGlow();
            Map<String,Object> nativeSlot=slot.gameplayStatus();
            Map<String,Object> node=map("id","native-fixture-slot","role","button");
            node.putAll(nativeSlot);
            UiProjectionHints.Node hint=new UiProjectionHints.Node(false,List.of(),null,Map.of(),
                    map("kind","item","loc","backpack.0"),null,false);
            Map<String,Object> ui=new UiProjectionHints(Map.of("native-fixture-slot",hint)).attach(map("controls",List.of(node)));
            Map<String,Object> original=map("inventory",List.of(PlayerObservation.item(sword,"backpack.0",false)),"ui",ui);
            Map<String,Object> merged=GameplayObservation.merge(original);
            Map<String,Object> canonical=map("scope_id","isolated_fixture_scope","state_version","isolated_fixture_revision","observation",merged);
            List<Object> wires=new ArrayList<>();
            for(String view:List.of("play","full","src")) {
                Map<String,Object> wire=CompactProtocol.success("native-crystal-"+stages[i]+"-"+view,
                        "isolated_fixture_scope","completed",canonical,true,view.equals("src"),view.equals("full"));
                wires.add(map("view",view,"wire",wire));
            }
            cases.add(map("stage",stages[i],"fixture_durability",amounts[i],"native_glow",nativeGlow,
                    "native_slot_status",nativeSlot,"merged",merged,"responses",wires));
        }
        Map<String,Object> locations=new LinkedHashMap<>();
        for(Class<?> type:List.of(ItemSprite.class,ItemSlot.class,GameplayObservation.class,CompactProtocol.class,PlayerObservation.class))
            locations.put(type.getSimpleName(),type.getProtectionDomain().getCodeSource().getLocation().toString());
        System.out.println(JsonCodec.encode(map("evidence_kind","isolated_native_producer_fixture",
                "scope","Synthetic textures and fixture item objects; no game launch, profile, save, or audit access.",
                "class_locations",locations,"cases",cases)));
    }
}
