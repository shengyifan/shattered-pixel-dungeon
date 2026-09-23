package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.effects.CircleArc;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.MissileSprite;
import com.shatteredpixel.shatteredpixeldungeon.ui.*;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndClericSpells;
import com.watabou.gltextures.SmartTexture;
import com.watabou.gltextures.TextureCache;
import com.watabou.noosa.*;
import com.watabou.utils.PointF;
import com.watabou.utils.RectF;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Existing native drawable fields, no game getters, GL calls, UI callbacks, or personal profiles. */
class NativeHudAppearanceTest {
    @BeforeAll static void resources(){GameSnapshotterTest.resourceOnlyRuntime();}

    @Test void iconOnlyButtonRetainsItsFactoryMeaningUntilItsPixelsChange() throws Exception {
        try(Textures fixture=new Textures()) {
            SmartTexture texture=allocate(SmartTexture.class);texture.width=256;texture.height=144;
            SmartTexture previous=fixture.cache.put(Assets.Interfaces.ICONS,texture);
            try {
                Image image=Icons.INFO.get();image.x=image.y=10;image.camera=new Camera(0,0,100,100,1);
                IconButton button=visual(allocate(IconButton.class));set(button,IconButton.class,"icon",image);image.parent=button;
                Map<?,?> icon=(Map<?,?>)button.renderedStatus().get("icon");
                assertEquals("info",icon.get("symbol"));assertEquals(Assets.Interfaces.ICONS,icon.get("atlas"));
                Map<String,Object> intent=button.intentStatus();image.alpha(.5f);assertEquals(intent,button.intentStatus());
                image.frame(new RectF(0,0,.05f,.1f));assertFalse(((Map<?,?>)button.renderedStatus().get("icon")).containsKey("symbol"));
                assertTrue(((Map<?,?>)button.renderedStatus().get("icon")).containsKey("frame_pixels"));
            } finally {if(previous==null)fixture.cache.remove(Assets.Interfaces.ICONS);else fixture.cache.put(Assets.Interfaces.ICONS,previous);}
        }
    }

    @Test void offPageQuickslotPreviewsPreserveVisibleOrderEmptyPositionsAndPlaceholderAlpha() throws Exception {
        try(Textures fixture=new Textures()) {
            Toolbar.SlotSwapTool swap=visual(allocate(Toolbar.SlotSwapTool.class));
            Image change=fixture.image(),first=fixture.image(),last=fixture.image();change.parent=first.parent=last.parent=swap;
            first.frame(new RectF(.25f,0,.5f,.25f));last.alpha(.29f);
            set(swap,Toolbar.SlotSwapTool.class,"icons",new Image[]{change,first,null,last});
            assertNull(SnapshotFields.read(swap,"items"),"The appearance does not inspect backing quickslot items");
            List<?> previews=(List<?>)swap.renderedStatus().get("preview_icons");assertEquals(4,previews.size());assertNull(previews.get(2));
            assertEquals(.29f,((Map<?,?>)previews.get(3)).get("alpha"));assertNotEquals(previews.get(0),previews.get(1));
            Map<String,Object> intent=swap.intentStatus();first.tint(0xCC00FF,.3f);assertEquals(intent,swap.intentStatus());
            last.alpha(1f);assertNotEquals(intent,swap.intentStatus());last.visible=false;
            assertNull(((List<?>)swap.renderedStatus().get("preview_icons")).get(3));
        }
    }

    @Test void bannerOccurrenceRequiresUncoveredDrawAndDoesNotRepeatDuringItsFade() throws Exception {
        Game previousGame=Game.instance;RuntimeObserver previousObserver=Game.observer;String previousRun=Dungeon.runId;
        Camera previousCamera=Camera.main;
        List<String> observed=new ArrayList<>();
        try(Textures fixture=new Textures()) {
            Camera.main=new Camera(0,0,100,100,1);
            com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene scene=new com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene();
            Game.instance=allocate(Game.class);set(Game.instance,Game.class,"scene",scene);Dungeon.runId="banner-fixture";
            Game.observer=new RuntimeObserver(){@Override public void onBanner(String run,String kind,Map<String,Object> appearance){observed.add(kind);}};
            Banner banner=new Banner(fixture.image()).observationKind("boss_slain");banner.camera=new Camera(0,0,100,100,1);banner.x=banner.y=30;scene.add(banner);
            Method record=Banner.class.getDeclaredMethod("recordDisplayedBanner");record.setAccessible(true);
            assertTrue(banner.renderedStatus().isEmpty());record.invoke(banner);assertTrue(observed.isEmpty(),"Transparent first frame is not an announcement");
            banner.alpha(1);
            Visual cover=new Visual(25,25,20,20);cover.camera=banner.camera;scene.add(cover);
            record.invoke(banner);assertTrue(observed.isEmpty(),"An overlapping UI/fader blocks the announcement");
            cover.visible=false;record.invoke(banner);record.invoke(banner);assertEquals(Collections.singletonList("boss_slain"),observed);
            assertEquals("boss_slain",banner.renderedStatus().get("banner_kind"));
            banner.alpha(.5f);record.invoke(banner);assertEquals(1,observed.size());assertTrue(banner.intentStatus().isEmpty());
            cover.visible=true;assertTrue(banner.renderedStatus().isEmpty());cover.visible=false;
            banner.show(0xFFFFFF,.3f,5f);record.invoke(banner);assertEquals(2,observed.size(),"A newly shown native instance is a new occurrence");
            banner.frame(new RectF(.25f,0,.5f,.25f));assertTrue(banner.renderedStatus().isEmpty(),"Changed pixels cannot keep their old semantic kind");
        } finally {Game.instance=previousGame;Game.observer=previousObserver;Dungeon.runId=previousRun;Camera.main=previousCamera;}
    }

    @Test void clericBrightnessAndAttackPortraitHaveNoModelDependency() throws Exception {
        try(Textures fixture=new Textures()) {
            WndClericSpells.SpellButton spell=visual(allocate(WndClericSpells.SpellButton.class));
            Image icon=fixture.image();icon.parent=spell;set(spell,IconButton.class,"icon",icon);
            Map<String,Object> before=spell.renderedStatus();icon.brightness(3f);
            Map<?,?> highlighted=(Map<?,?>)spell.renderedStatus().get("spell_icon");
            assertNotEquals(before,spell.renderedStatus());
            assertEquals(Arrays.asList(3f,3f,3f),((Map<?,?>)highlighted.get("tint")).get("multiply"));
            assertEquals(Assets.Interfaces.HERO_ICONS,highlighted.get("atlas"));
            assertNull(SnapshotFields.read(spell,"spell"));assertNull(SnapshotFields.read(spell,"tome"));
            AttackIndicator attack=visual(allocate(AttackIndicator.class));
            CharSprite portrait=fixture.sprite();portrait.parent=attack;set(attack,AttackIndicator.class,"sprite",portrait);
            assertTrue(attack.renderedStatus().containsKey("target_icon"));
            assertFalse(attack.renderedStatus().containsKey("cell"));
            assertNull(SnapshotFields.read(attack,"lastTarget"));
            portrait.visible=false;assertTrue(attack.renderedStatus().isEmpty());
        }
    }

    @Test void actionPairAndBossWarningPreserveTheirCurrentPixels() throws Exception {
        try(Textures fixture=new Textures()) {
            ActionIndicator action=visual(allocate(ActionIndicator.class));Image primary=fixture.image(),secondary=fixture.image();
            primary.parent=secondary.parent=action;secondary.frame(new RectF(.25f,0,.5f,.25f));secondary.brightness(.6f);
            set(action,ActionIndicator.class,"primaryVis",primary);set(action,ActionIndicator.class,"secondVis",secondary);
            Map<String,Object> pair=action.renderedStatus();assertTrue(pair.containsKey("primary_icon"));assertTrue(pair.containsKey("secondary_icon"));
            assertNotEquals(pair.get("primary_icon"),pair.get("secondary_icon"));
            BossHealthBar boss=visual(allocate(BossHealthBar.class));Image skull=fixture.image();skull.parent=boss;set(boss,BossHealthBar.class,"skull",skull);
            Map<String,Object> ordinary=boss.renderedStatus();skull.tint(0xCC0000,.6f);
            assertNotEquals(ordinary,boss.renderedStatus());assertNull(SnapshotFields.read(boss,"blood"));
            assertFalse(boss.renderedStatus().containsKey("phase"));
            skull.x=99;assertTrue(boss.renderedStatus().isEmpty(),"A partially clipped portrait is not a full appearance");
        }
    }

    @Test void statusArcReadsOnlyTheExistingSweepAndPublishesThroughUiBridge() throws Exception {
        com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero previousHero=Dungeon.hero;Dungeon.hero=null;
        try(Textures fixture=new Textures()) {
            StatusPane status=visual(allocate(StatusPane.class));set(status,Group.class,"members",new ArrayList<Gizmo>());
            CircleArc arc=visual(allocate(CircleArc.class));arc.x=20;arc.y=20;arc.parent=status;
            set(arc,CircleArc.class,"rad",3f);set(arc,CircleArc.class,"sweep",.25f);set(status,StatusPane.class,"counter",arc);
            assertEquals(.25f,((Map<?,?>)status.renderedStatus().get("turn_progress")).get("sweep"));
            Scene scene=new Scene();scene.add(status);UiBridge bridge=new UiBridge(()->scene);
            Map<?,?> ui=UiDrawFixture.capture(bridge).describeUi();
            assertTrue(((List<?>)ui.get("controls")).stream().anyMatch(node->((Map<?,?>)node).containsKey("turn_progress")));
            String before=bridge.intentSignature();arc.setSweep(.5f);assertNotEquals(before,bridge.intentSignature());
            assertNull(Dungeon.hero,"No hero or actor-clock read is needed to capture the displayed arc");
            arc.x=99;assertFalse(status.renderedStatus().containsKey("turn_progress"));
        } finally {Dungeon.hero=previousHero;}
    }

    @Test void decorativeAnimationDoesNotChangeIntentButMeaningfulHighlightAndSelectionDo() throws Exception {
        try(Textures fixture=new Textures()) {
            BossHealthBar boss=visual(allocate(BossHealthBar.class));Image skull=fixture.image();skull.parent=boss;set(boss,BossHealthBar.class,"skull",skull);
            Map<String,Object> bossIntent=boss.intentStatus(),bossPixels=boss.renderedStatus();
            skull.frame(new RectF(.25f,0,.5f,.25f));
            assertNotEquals(bossPixels,boss.renderedStatus());assertEquals(bossIntent,boss.intentStatus());
            skull.tint(0xCC0000,.6f);assertNotEquals(bossIntent,boss.intentStatus());
            StatusPane status=visual(allocate(StatusPane.class));Image avatar=fixture.image();avatar.parent=status;set(status,StatusPane.class,"avatar",avatar);
            Map<String,Object> statusIntent=status.intentStatus(),statusPixels=status.renderedStatus();avatar.tint(0xFF0000,.5f);
            assertNotEquals(statusPixels,status.renderedStatus());assertEquals(statusIntent,status.intentStatus());
            QuickSlotButton quick=visual(allocate(QuickSlotButton.class));Image marker=fixture.image();marker.parent=quick;set(quick,QuickSlotButton.class,"crossB",marker);
            Map<String,Object> selection=quick.intentStatus(),pixels=quick.renderedStatus();marker.alpha(.5f);
            assertNotEquals(pixels,quick.renderedStatus());assertEquals(selection,quick.intentStatus());
            marker.visible=false;assertNotEquals(selection,quick.intentStatus());
            WndClericSpells.SpellButton spell=visual(allocate(WndClericSpells.SpellButton.class));Image icon=fixture.image();icon.parent=spell;set(spell,IconButton.class,"icon",icon);
            Map<String,Object> ordinary=spell.intentStatus();icon.brightness(3);assertNotEquals(ordinary,spell.intentStatus());
        }
    }

    @Test void quickslotMarkerAndRaisedScaledSpriteUseDisplayedGeometryNotAnActorCell() throws Exception {
        Level old=Dungeon.level;Dungeon.level=new Grid();
        try(Textures fixture=new Textures()) {
            QuickSlotButton button=visual(allocate(QuickSlotButton.class));Image cross=fixture.image();cross.parent=button;
            set(button,QuickSlotButton.class,"crossB",cross);set(button,QuickSlotButton.class,"slotNum",2);
            assertEquals(3,button.renderedStatus().get("quickslot"));cross.visible=false;assertTrue(button.renderedStatus().isEmpty());
            CharSprite sprite=fixture.sprite();sprite.width=16;sprite.height=16;sprite.scale.set(2f,2f);
            // Native worldToCamera for cell 22, including its six-pixel perspective raise.
            sprite.x=40-16;sprite.y=48-32-6;
            assertNull(sprite.ch);assertEquals(22,sprite.renderedCell());
            QuickSlotButton.TargetMarker marker=new QuickSlotButton.TargetMarker();marker.width=8;marker.height=8;
            Group parent=new Group();parent.add(sprite);parent.add(marker);marker.point(sprite.center(marker));
            Method bind=QuickSlotButton.TargetMarker.class.getDeclaredMethod("target",CharSprite.class);bind.setAccessible(true);bind.invoke(marker,sprite);
            assertEquals(22,marker.renderedTargetCell());sprite.isMoving=true;assertEquals(-1,marker.renderedTargetCell());
            sprite.isMoving=false;marker.x+=2;assertEquals(-1,marker.renderedTargetCell());
            marker.point(sprite.center(marker));sprite.visible=false;assertEquals(-1,marker.renderedTargetCell());
        } finally {Dungeon.level=old;}
    }

    @Test void projectileAppearanceContainsOnlyCurrentFrameAndMotionAndFreezesItsValues() throws Exception {
        Level old=Dungeon.level;Dungeon.level=new Grid();
        try(Textures fixture=new Textures()) {
            MissileSprite missile=visual(allocate(MissileSprite.class));set(missile,Image.class,"frame",new RectF(0,0,.25f,.25f));
            missile.texture=fixture.texture;missile.width=8;missile.height=8;missile.x=36;missile.y=36;
            missile.origin.set(4,4);missile.angle=45;missile.speed=new PointF(2,0);
            VisualCue cue=missile.renderedProjectileCue();assertNotNull(cue);assertEquals(22,cue.cell);assertNull(cue.direction,"The collector measures direction only from consecutive visible draws");
            assertNull(cue.sourceCell);assertEquals(Assets.Interfaces.HERO_ICONS,cue.appearance.get("atlas"));
            assertThrows(UnsupportedOperationException.class,()->cue.appearance.put("target",99));
            missile.brightness(3);assertNotEquals(cue,missile.renderedProjectileCue());
            assertFalse(cue.appearance.containsKey("item"));assertFalse(cue.appearance.containsKey("class"));
            assertNull(SnapshotFields.read(missile,"callback"));
            missile.x=98;assertNull(missile.renderedProjectileCue());
            missile.x=36;missile.am=Float.NaN;assertNull(missile.renderedProjectileCue());
        } finally {Dungeon.level=old;}
    }

    @Test void unknownTextureAndImmutableCueValuesFailConservatively() throws Exception {
        try(Textures fixture=new Textures()) {
            Image image=fixture.image();image.texture=allocate(SmartTexture.class);image.texture.width=image.texture.height=32;
            Map<String,Object> unknown=RenderedAppearance.image(image);assertEquals(true,unknown.get("asset_unknown"));assertTrue(unknown.containsKey("presentation"));
            assertFalse(unknown.containsKey("atlas"));
            List<Object> list=new ArrayList<>(Arrays.asList(null,false,0));Map<String,Object> mutable=new LinkedHashMap<>();mutable.put("values",list);
            VisualCue cue=new VisualCue("drawn",1,null,null,null,null,mutable);list.set(2,3);
            assertEquals(Arrays.asList(null,false,0),cue.appearance.get("values"));
            assertThrows(UnsupportedOperationException.class,()->((List<Object>)cue.appearance.get("values")).set(0,1));
            for(Object invalid:Arrays.asList(new Object(),Float.NaN,Double.POSITIVE_INFINITY)) {
                mutable.put("invalid",invalid);assertThrows(IllegalArgumentException.class,()->new VisualCue("drawn",1,null,null,null,null,mutable));
            }
        }
    }

    private static final class Grid extends Level {
        Grid(){setSize(10,10);}
        @Override protected boolean build(){return false;}
        @Override protected void createMobs(){}
        @Override protected void createItems(){}
    }

    private static final class Textures implements AutoCloseable {
        final Map<Object,SmartTexture> cache;final SmartTexture previous,texture;
        Textures()throws Exception {
            cache=(Map<Object,SmartTexture>)SnapshotFields.read(TextureCache.class,"all");previous=cache.get(Assets.Interfaces.HERO_ICONS);
            texture=allocate(SmartTexture.class);texture.width=texture.height=32;cache.put(Assets.Interfaces.HERO_ICONS,texture);
        }
        Image image(){Image image=new Image();image.texture=texture;image.frame(new RectF(0,0,.25f,.25f));image.x=image.y=10;image.camera=new Camera(0,0,100,100,1);return image;}
        CharSprite sprite(){CharSprite sprite=new CharSprite();sprite.texture=texture;sprite.frame(new RectF(0,0,.25f,.25f));sprite.x=sprite.y=10;sprite.camera=new Camera(0,0,100,100,1);return sprite;}
        public void close(){if(previous==null)cache.remove(Assets.Interfaces.HERO_ICONS);else cache.put(Assets.Interfaces.HERO_ICONS,previous);}
    }
    private static <T extends Gizmo>T visual(T value){
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
