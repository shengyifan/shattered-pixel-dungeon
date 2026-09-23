package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.watabou.noosa.Visual;
import com.watabou.utils.PointF;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class LootFlareSemanticTest {
    @Test void championAuraAndInvulnerabilityUseSelectedRayAndColorVariants() throws Exception {
        Flare aura=bare();set(aura,"nRays",6);aura.color(0xFFFF00,true);
        assertEquals(Collections.singletonMap("variant","blessed"),aura.auraFact());
        set(aura,"nRays",5);assertEquals(Collections.singletonMap("variant","invulnerable"),aura.auraFact());
        aura.color(0x0088FF,true);assertEquals(Collections.singletonMap("variant","giant"),aura.auraFact());
        set(aura,"nRays",99);assertUnmapped(aura.auraFact(),"variant","character_aura");
    }
    @Test void selectedRayColorAndRadiusRetainFourPublicLootTiersWithoutDropState() throws Exception {
        int[] colors={0x00FF00,0x00AAFF,0xAA00FF,0xFFAA00};
        for(int tier=1;tier<=4;tier++){
            Flare flare=bare();set(flare,"nRays",6);set(flare,"visualRadius",(float)(16+tier*4));
            flare.color(colors[tier-1],true).observeLootTier(tier);
            assertEquals(Collections.singletonMap("tier",tier),flare.lootFact());
            flare.angle=123;flare.angularSpeed=456;flare.alpha(.4f);flare.scale.set(.25f);
            assertEquals(Collections.singletonMap("tier",tier),flare.lootFact(),"Ordinary pulse and rotation do not change loot meaning");
            flare.color(0xFFFFFF,true);assertUnmapped(flare.lootFact(),"tier","loot_flare");
            assertFalse(flare.lootFact().containsKey("color"));
        }
    }
    private static void assertUnmapped(java.util.Map<String,Object> value,String field,String indicator){
        assertEquals(true,value.get("unmapped_indicator"));
        assertFalse(value.containsKey(field),"An unrecognized display cannot invent a semantic value");
        java.util.Map<?,?> presentation=(java.util.Map<?,?>)value.get("presentation");
        assertEquals("partial",presentation.get("status"));
        assertEquals(java.util.List.of(java.util.Map.of("field",field,"code","unmapped_indicator","indicator",indicator)),
                presentation.get("diagnostics"));
    }
    @Test void ordinaryFlareHasNoGameplayFactAndInvisibleLootFlareCannotPublish() throws Exception {
        Flare ordinary=bare();set(ordinary,"nRays",6);set(ordinary,"visualRadius",20f);ordinary.color(0x00FF00,true);
        assertNull(ordinary.lootFact(),"Color similarity cannot turn an unclassified effect into a loot event");
        GameplayVisualTraversalTest.Fixture f=new GameplayVisualTraversalTest.Fixture();f.scene.add(ordinary);
        com.watabou.noosa.VisualCue cue=new com.watabou.noosa.VisualCue("loot_flare",22,null,null,null,null,Collections.singletonMap("tier",1));
        ordinary.scale.set(0);f.collector.radialVisualDrawn(ordinary,cue,20);assertTrue(f.cues().isEmpty());
        ordinary.scale.set(1);ordinary.alpha(0);f.collector.radialVisualDrawn(ordinary,cue,20);assertTrue(f.cues().isEmpty());
        ordinary.alpha(1);f.collector.radialVisualDrawn(ordinary,cue,20);assertEquals(Collections.singletonList(cue),f.cues());
    }
    private static Flare bare()throws Exception{
        Class<?> type=Class.forName("sun.misc.Unsafe");Field f=type.getDeclaredField("theUnsafe");f.setAccessible(true);
        Flare flare=(Flare)type.getMethod("allocateInstance",Class.class).invoke(f.get(null),Flare.class);
        flare.exists=flare.alive=flare.visible=true;flare.scale=new PointF(1,1);flare.origin=new PointF();flare.resetColor();return flare;
    }
    private static void set(Flare target,String name,Object value)throws Exception{Field f=Flare.class.getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
}
