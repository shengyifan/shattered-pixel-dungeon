package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.items.BrokenSeal;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.ui.*;
import com.watabou.gltextures.SmartTexture;
import com.watabou.gltextures.TextureCache;
import com.watabou.noosa.*;
import com.watabou.utils.PointF;
import com.watabou.utils.RectF;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Native semantic snapshots must not evaluate backing item/Buff state or disclose drawing details. */
class GameplayStatusTest {
    @BeforeAll static void resources() { GameSnapshotterTest.resourceOnlyRuntime(); }

    @Test void itemSnapshotRetainsTheDisplayedEstimateAndWarningsWithoutItemGetters() throws Exception {
        ItemSlot slot=visual(allocate(ItemSlot.class));
        Item item=new Item(){@Override public String status(){throw new AssertionError("Capture evaluated Item.status");}};
        set(slot,ItemSlot.class,"item",item);
        BitmapText status=text(slot,"3"),extra=text(slot,"14?"),level=text(slot,"+2");
        set(slot,ItemSlot.class,"status",status);set(slot,ItemSlot.class,"extra",extra);set(slot,ItemSlot.class,"level",level);
        set(slot,ItemSlot.class,"semanticStatusText","3");set(slot,ItemSlot.class,"semanticStatusFlag","last_use");
        set(slot,ItemSlot.class,"semanticStrengthText","14?");set(slot,ItemSlot.class,"semanticStrength",14);
        set(slot,ItemSlot.class,"semanticStrengthEstimated",true);
        set(slot,ItemSlot.class,"semanticLevelText","+2");set(slot,ItemSlot.class,"semanticLevelFlag","enhanced");
        Map<?,?> shown=(Map<?,?>)slot.gameplayStatus().get("shown");
        assertSame(item,slot.gameplaySubject());assertEquals("14?",shown.get("extra"));
        assertEquals(Map.of("value",14,"estimated",true),shown.get("strength"));
        assertEquals(List.of("last_use","enhanced"),shown.get("flags"));
        assertNoDrawing(shown);
        extra.text("12");
        assertFalse(((Map<?,?>)slot.gameplayStatus().get("shown")).containsKey("strength"),"A changed component cannot keep an old estimate binding");
        level.visible=false;
        assertEquals(List.of("last_use"),((Map<?,?>)slot.gameplayStatus().get("shown")).get("flags"));
    }

    @Test void buffSnapshotOwnsItsCounterAndMaskAndKeepsOnlyDisplayedProgress() throws Exception {
        try(Texture texture=new Texture(Assets.Interfaces.BUFFS_LARGE,256,96)) {
            Class<?> type=Class.forName("com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator$BuffButton");
            Gizmo control=visual((Gizmo)allocate(type));GameplayStatus status=(GameplayStatus)control;
            Buff backing=new Buff(){@Override public int icon(){throw new AssertionError("Capture evaluated Buff.icon");}};
            set(control,type,"buff",backing);
            BuffIcon icon=visual(new BuffIcon(BuffIndicator.MIND_VISION,true));icon.texture=texture.texture;icon.frame(new RectF(0,0,1f/16f,1f/6f));
            icon.x=icon.y=10;icon.parent=(Group)control;set(icon,BuffIcon.class,"displayedIndex",BuffIndicator.MIND_VISION);
            set(control,IconButton.class,"icon",icon);
            Image grey=visual(new Image());grey.x=grey.y=10;grey.width=16;grey.height=8;grey.parent=(Group)control;
            set(control,type,"grey",grey);BitmapText counter=text((Group)control,"2");counter.visible=false;set(control,type,"text",counter);
            Map<?,?> shown=(Map<?,?>)status.gameplayStatus().get("shown");
            assertSame(backing,status.gameplaySubject());assertEquals("buff_mind_vision",shown.get("symbol"));
            assertEquals(Map.of("covered",8,"total",16,"basis","displayed"),shown.get("progress"));
            assertFalse(shown.containsKey("turns"));assertNoDrawing(shown);
            grey.visible=false;counter.visible=true;
            shown=(Map<?,?>)status.gameplayStatus().get("shown");assertEquals("2",shown.get("counter"));assertFalse(shown.containsKey("progress"));
            icon.frame(new RectF(1f/16f,0,2f/16f,1f/6f));
            shown=(Map<?,?>)status.gameplayStatus().get("shown");assertEquals("unmapped_indicator",shown.get("symbol"));assertTrue(shown.containsKey("presentation"));
        }
    }

    @Test void warriorShieldCounterAndSmallBarKeepNativeMeaningWhenTheSameNumberMeansDifferentThings() throws Exception {
        Object previousFilm=SnapshotFields.read(BuffIcon.class,"largeFilm");
        try(Texture texture=new Texture(Assets.Interfaces.BUFFS_LARGE,256,128)) {
            set(null,BuffIcon.class,"largeFilm",new TextureFilm(texture.texture,16,16));
            BrokenSeal.WarriorShield buff=new BrokenSeal.WarriorShield();buff.setShield(2);
            set(buff,BrokenSeal.WarriorShield.class,"initialShield",4);
            Class<?> type=Class.forName("com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator$BuffButton");
            Gizmo control=visual((Gizmo)allocate(type));GameplayStatus status=(GameplayStatus)control;
            set(control,type,"buff",buff);set(control,type,"large",true);
            BuffIcon icon=new BuffIcon(buff,true);icon.x=icon.y=10;icon.parent=(Group)control;
            set(control,IconButton.class,"icon",icon);
            Image grey=new Image();grey.width=grey.height=1;grey.x=grey.y=10;grey.parent=(Group)control;
            set(control,type,"grey",grey);
            CounterText counter=new CounterText();counter.parent=(Group)control;counter.x=counter.y=10;set(control,type,"text",counter);
            java.lang.reflect.Method update=type.getDeclaredMethod("updateIcon");update.setAccessible(true);update.invoke(control);
            Map<?,?> shield=(Map<?,?>)status.gameplayStatus().get("shown");
            assertEquals("2",shield.get("counter"));assertEquals("shield",shield.get("counter_kind"));
            buff.decShield(2);set(buff,BrokenSeal.WarriorShield.class,"cooldown",2);
            assertEquals("shield",((Map<?,?>)status.gameplayStatus().get("shown")).get("counter_kind"),"Capture uses the native display choice, not the current backing Buff");
            update.invoke(control);icon.resetColor();
            Map<?,?> cooldown=(Map<?,?>)status.gameplayStatus().get("shown");
            assertEquals("2",cooldown.get("counter"));assertEquals("cooldown",cooldown.get("counter_kind"),"Meaning comes from the native branch, not text or tint");
            set(control,type,"large",false);update.invoke(control);
            Map<?,?> small=(Map<?,?>)status.gameplayStatus().get("shown");
            assertFalse(small.containsKey("counter"));assertEquals("cooldown",small.get("progress_kind"));assertTrue(small.containsKey("progress"));
            buff.setShield(2);update.invoke(control);
            assertEquals("shield",((Map<?,?>)status.gameplayStatus().get("shown")).get("progress_kind"));
            buff.decShield(2);set(buff,BrokenSeal.WarriorShield.class,"cooldown",-2);set(control,type,"large",true);update.invoke(control);
            Map<?,?> negative=(Map<?,?>)status.gameplayStatus().get("shown");
            assertEquals("-2",negative.get("counter"));assertEquals("cooldown",negative.get("counter_kind"));
        } finally {set(null,BuffIcon.class,"largeFilm",previousFilm);}
    }

    private static final class CounterText extends BitmapText {
        CounterText(){super("",null);}
        @Override public synchronized void measure(){width=8;height=8;}
    }

    @Test void worldImageKeepsKnownSymbolOffscreenWithoutDecorativeParameters() throws Exception {
        try(Texture texture=new Texture(Assets.Interfaces.HERO_ICONS,256,128)) {
            Image icon=visual(new Image());icon.texture=texture.texture;icon.frame(new RectF(0,0,1f/16f,1f/8f));icon.x=200;icon.y=10;
            assertTrue(GameplayIcons.image(icon).isEmpty());
            Map<String,Object> symbol=GameplayIcons.worldImage(icon);assertEquals("hero_berserker",symbol.get("symbol"));
            icon.flipHorizontal=true;icon.brightness(3f);icon.angle=45;
            assertEquals(symbol,GameplayIcons.worldImage(icon));assertNoDrawing(symbol);
            icon.visible=false;assertTrue(GameplayIcons.worldImage(icon).isEmpty());
        }
    }

    @Test void namedFeedbackPreservesDifferentOutcomesAndUnknownsRemainExplicit() {
        assertEquals("healing",GameplayIcons.floating(18).get("symbol"));
        assertEquals("hit_weapon",GameplayIcons.floating(36).get("symbol"));
        assertEquals("armor_piercing_hit_weapon",GameplayIcons.floating(54).get("symbol"));
        assertEquals("miss_weapon",GameplayIcons.floating(72).get("symbol"));
        assertEquals("spell_haste",GameplayIcons.spell(5).get("symbol"));
        assertEquals("unmapped_indicator",GameplayIcons.floating(999).get("symbol"));
        assertTrue(GameplayIcons.floating(999).containsKey("presentation"));
        assertNoDrawing(GameplayIcons.floating(999));assertNoDrawing(GameplayIcons.feedback(123));
    }

    private static BitmapText text(Group parent,String value) {
        BitmapText text=new BitmapText(value,null);text.parent=parent;text.x=text.y=10;text.width=16;text.height=8;return text;
    }
    private static void assertNoDrawing(Object value) {
        if(value instanceof Map)for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet()) {
            assertFalse(Set.of("atlas","frame_pixels","texture_size","tint","color","styles","alpha","angle","scale","flip_horizontal","flip_vertical").contains(entry.getKey()),"Drawing detail leaked: "+entry.getKey());
            assertNoDrawing(entry.getValue());
        } else if(value instanceof Collection)for(Object child:(Collection<?>)value)assertNoDrawing(child);
    }
    private static final class Texture implements AutoCloseable {
        final String asset;final Map<Object,SmartTexture> cache;final SmartTexture previous,texture;
        Texture(String asset,int width,int height)throws Exception {
            this.asset=asset;cache=(Map<Object,SmartTexture>)SnapshotFields.read(TextureCache.class,"all");previous=cache.get(asset);
            texture=allocate(SmartTexture.class);texture.width=width;texture.height=height;cache.put(asset,texture);
        }
        @Override public void close(){if(previous==null)cache.remove(asset);else cache.put(asset,previous);}
    }
    private static <T extends Gizmo>T visual(T value) {
        value.exists=value.alive=value.visible=value.active=true;value.camera=new Camera(0,0,100,100,1);
        if(value instanceof Visual) {
            Visual drawable=(Visual)value;drawable.x=drawable.y=0;drawable.width=drawable.height=60;
            drawable.origin=new PointF();drawable.scale=new PointF(1,1);drawable.rm=drawable.gm=drawable.bm=drawable.am=1;
        }
        return value;
    }
    private static <T>T allocate(Class<T> type)throws Exception{return FloatingAppearanceTest.allocate(type);}
    private static void set(Object value,Class<?> owner,String name,Object data)throws Exception{FloatingAppearanceTest.field(value,owner,name,data);}
}
