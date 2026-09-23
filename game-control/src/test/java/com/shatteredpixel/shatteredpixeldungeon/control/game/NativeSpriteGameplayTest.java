package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.BrokenSeal;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClothArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.CeremonialCandle;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.SpiritBow;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.huntress.NaturesPower;
import com.shatteredpixel.shatteredpixeldungeon.ui.GameplayIcons;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.sprites.*;
import com.watabou.gltextures.SmartTexture;
import com.watabou.gltextures.TextureCache;
import com.watabou.noosa.*;
import com.watabou.noosa.particles.Emitter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Selected native display state, isolated from profiles, actor decisions and renderer timing. */
class NativeSpriteGameplayTest {
    @BeforeAll static void resources(){GameSnapshotterTest.resourceOnlyRuntime();}

    @Test void mimicMotionRequiresTwoEligibleFramesAndNeverLabelsAStaticDisguise() throws Exception {
        try(Fixture fixture=new Fixture()) {
            MimicSprite sprite=new MimicSprite();fixture.scene.add(sprite);sprite.place(22);
            MovieClip.Animation hiding=(MovieClip.Animation)SnapshotFields.read(sprite,"hiding");
            MovieClip.Animation advanced=(MovieClip.Animation)SnapshotFields.read(sprite,"advancedHiding");
            sprite.play(hiding,true);sprite.frame(hiding.frames[0]);assertNull(sprite.ch);
            assertNull(sprite.gameplayContainerCue());assertNull(sprite.gameplayContainerCue());
            sprite.frame(hiding.frames[hiding.frames.length-1]);
            VisualCue observed=sprite.gameplayContainerCue();assertEquals("animated_container",observed.kind);assertEquals(22,observed.cell);
            fixture.level.heroFOV[22]=false;assertNull(sprite.gameplayContainerCue());
            fixture.level.heroFOV[22]=true;assertNull(sprite.gameplayContainerCue(),"Motion during a hidden interval is not public evidence");
            sprite.frame(hiding.frames[0]);assertNotNull(sprite.gameplayContainerCue());
            sprite.play(advanced,true);assertNull(sprite.gameplayContainerCue(),"The indistinguishable static container is not labeled as a mimic");
            sprite.play(hiding,true);sprite.frame(hiding.frames[0]);assertNull(sprite.gameplayContainerCue());
            sprite.frame(hiding.frames[hiding.frames.length-1]);assertNotNull(sprite.gameplayContainerCue());
            sprite.revive();assertNull(sprite.gameplayContainerCue(),"Pool reuse cannot carry earlier observed motion");
        }
    }

    @Test void gnollArmorUsesTheOwnedEmitterEpisodeWithoutLookingAtItsActor() throws Exception {
        try(Fixture fixture=new Fixture()) {
            for(CharSprite sprite:new CharSprite[]{new GnollGuardSprite(),new GnollGeomancerSprite()}) {
                fixture.scene.add(sprite);sprite.place(22);assertNull(sprite.ch);
                Emitter armor=new Emitter();armor.on=true;fixture.scene.add(armor);
                Object episode=new Object();set(armor,Emitter.class,"observedDrawEpisode",episode);
                set(sprite,sprite.getClass(),"earthArmor",armor);set(sprite,sprite.getClass(),"gameplayArmorEpisode",episode);
                assertEquals("gnoll_earth_armor",armorCue(sprite).kind);assertEquals(22,armorCue(sprite).cell);
                armor.visible=false;assertNull(armorCue(sprite));armor.visible=true;
                set(armor,Emitter.class,"observedDrawEpisode",new Object());assertNull(armorCue(sprite),"A recycled emitter has a different source episode");
                set(armor,Emitter.class,"observedDrawEpisode",episode);fixture.scene.erase(armor);assertNull(armorCue(sprite));
                fixture.scene.add(armor);sprite.visible=false;assertNull(armorCue(sprite));sprite.visible=true;
                if(sprite instanceof GnollGuardSprite)((GnollGuardSprite)sprite).loseArmor();else ((GnollGeomancerSprite)sprite).loseArmor();
                assertNull(armorCue(sprite),"Detached armor is not kept by harmless tail particles");
            }
        }
    }

    @Test void itemGlowKeepsNativeHeatStagesButDropsPulsePhaseAndPrivateAmounts() throws Exception {
        try(Fixture fixture=new Fixture()) {
            ItemSprite sprite=new ItemSprite(ItemSpriteSheet.SWORD,new ItemSprite.Glowing(0).gameplayHint("explosive_warm"));
            fixture.scene.add(sprite);sprite.place(22);
            Map<String,Object> warm=sprite.gameplayGlow();assertEquals("warm",warm.get("stage"));assertEquals("black",warm.get("variant"));
            set(sprite,ItemSprite.class,"phase",.123f);sprite.brightness(.12f);assertEquals(warm,sprite.gameplayGlow());
            assertNull(sprite.gameplayGlowCue(),"Unbound item previews and missiles do not create heap cues");
            sprite.heap=new Heap();assertEquals(22,sprite.gameplayGlowCue().cell);
            sprite.glow(new ItemSprite.Glowing(0,.25f).gameplayHint("explosive_hot"));assertEquals("hot",sprite.gameplayGlow().get("stage"));
            sprite.glow(new ItemSprite.Glowing(0xFFFFFF,.333f).gameplayHint("resin_fortified"));
            assertEquals(Map.of("variant","white","kind","resin_fortified"),sprite.gameplayGlow());
            sprite.glow(new ItemSprite.Glowing(0x123456));assertTrue(sprite.gameplayGlow().containsKey("presentation"));
            assertFalse(sprite.gameplayGlow().containsKey("color"));
            sprite.revive();assertTrue(sprite.gameplayGlow().isEmpty());
            sprite.glow(new ItemSprite.Glowing(0xFFFF00));assertEquals("yellow",sprite.gameplayGlow().get("variant"));
            sprite.glow(null);assertTrue(sprite.gameplayGlow().isEmpty());
        }
    }

    @Test void brokenSealStatusBelongsToTheOriginalLiveItemEmitterAndDoesNotSurviveReuse() throws Exception {
        try(Fixture fixture=new Fixture()) {
            Armor armor=new ClothArmor();assertNull(armor.emitter(),"An unsealed armor has no red-light source");
            set(armor,Armor.class,"seal",new BrokenSeal());
            Emitter emitter=armor.emitter();
            ItemSprite sprite=new ItemSprite(ItemSpriteSheet.ARMOR_CLOTH);fixture.scene.add(sprite);sprite.place(22);
            emitter.pos(sprite);fixture.scene.add(emitter);set(sprite,ItemSprite.class,"emitter",emitter);
            assertEquals(Map.of("broken_seal",true),sprite.gameplayItemStatus());
            set(armor,Armor.class,"seal",null);
            assertEquals(Map.of("broken_seal",true),sprite.gameplayItemStatus(),"Capture reads the selected emitter, never rechecks hidden Armor.seal");
            sprite.heap=new Heap();assertEquals("item_status",sprite.gameplayItemStatusCue().kind);
            assertEquals(true,sprite.gameplayItemStatusCue().appearance.get("broken_seal"));
            emitter.visible=false;assertTrue(sprite.gameplayItemStatus().isEmpty());emitter.visible=true;
            fixture.scene.erase(emitter);assertTrue(sprite.gameplayItemStatus().isEmpty());fixture.scene.add(emitter);
            emitter.revive();assertTrue(sprite.gameplayItemStatus().isEmpty(),"A reused emitter loses its old semantic source binding");
            assertNull(armor.emitter(),"No-seal native selection cannot inherit the earlier flag");
        }
    }

    @Test void candleMeaningUsesTheSelectedFlameInsteadOfRereadingTheRitualModel() throws Exception {
        try(Fixture fixture=new Fixture()) {
            CeremonialCandle candle=new CeremonialCandle();assertNull(candle.emitter());candle.aflame=true;
            Emitter emitter=candle.emitter();
            ItemSprite sprite=new ItemSprite(ItemSpriteSheet.CANDLE);fixture.scene.add(sprite);sprite.place(22);
            emitter.pos(sprite);fixture.scene.add(emitter);set(sprite,ItemSprite.class,"emitter",emitter);
            assertEquals(Map.of("lit_candle",true),sprite.gameplayItemStatus());
            candle.aflame=false;assertEquals(Map.of("lit_candle",true),sprite.gameplayItemStatus());
            sprite.heap=new Heap();assertEquals(true,sprite.gameplayItemStatusCue().appearance.get("lit_candle"));
            emitter.on=false;assertTrue(sprite.gameplayItemStatus().isEmpty());emitter.on=true;
            fixture.scene.erase(emitter);assertTrue(sprite.gameplayItemStatus().isEmpty());fixture.scene.add(emitter);
            emitter.revive();assertTrue(sprite.gameplayItemStatus().isEmpty());assertNull(candle.emitter());
        }
    }

    @Test void natureArrowTagFollowsOnlyItsSelectedLeafEmitterAndRejectsOrdinaryOrReusedSources() throws Exception {
        Hero previousHero=Dungeon.hero;
        try(Fixture fixture=new Fixture()) {
            NatureHero hero=new NatureHero();Dungeon.hero=hero;
            SpiritBow bow=new SpiritBow();SpiritBow.SpiritArrow arrow=bow.knockArrow();
            assertNull(arrow.emitter(),"Ordinary arrows have no Nature's Power leaf source");
            hero.natureActive=true;bow.sniperSpecial=true;assertNull(arrow.emitter(),"Sniper special arrows do not select this trail");
            bow.sniperSpecial=false;
            MissileSprite sprite=new MissileSprite();fixture.scene.add(sprite);sprite.view(arrow);
            assertEquals(Map.of("nature_powered",true),sprite.gameplayItemStatus());
            assertEquals(true,GameplayIcons.worldImage(sprite).get("nature_powered"));
            hero.natureActive=false;bow.sniperSpecial=true;
            assertEquals(Map.of("nature_powered",true),sprite.gameplayItemStatus(),"Capture never rechecks the hero Buff or sniper flag");
            Emitter selected=(Emitter)SnapshotFields.read(sprite,"emitter");selected.revive();
            assertTrue(sprite.gameplayItemStatus().isEmpty());
            sprite.view(arrow);assertTrue(sprite.gameplayItemStatus().isEmpty());
            assertFalse(GameplayIcons.worldImage(sprite).containsKey("nature_powered"));
        } finally {Dungeon.hero=previousHero;}
    }

    private static final class NatureHero extends Hero {
        boolean natureActive;
        private final NaturesPower.naturesPowerTracker nature=new NaturesPower.naturesPowerTracker();
        @Override public synchronized <T extends Buff>T buff(Class<T> type) {
            return natureActive&&type==NaturesPower.naturesPowerTracker.class?type.cast(nature):null;
        }
    }

    private static VisualCue armorCue(CharSprite sprite) {
        return sprite instanceof GnollGuardSprite?((GnollGuardSprite)sprite).gameplayArmorCue():((GnollGeomancerSprite)sprite).gameplayArmorCue();
    }
    private static void set(Object value,Class<?> owner,String name,Object data)throws Exception {FloatingAppearanceTest.field(value,owner,name,data);}
    private static final class Grid extends Level {
        Grid(){width=height=10;length=100;heroFOV=new boolean[length];Arrays.fill(heroFOV,true);}
        @Override protected boolean build(){return false;}
        @Override protected void createMobs(){}
        @Override protected void createItems(){}
    }
    private static final class Fixture implements AutoCloseable {
        final Level previousLevel=Dungeon.level;final Camera previousCamera=Camera.main;
        final Grid level=new Grid();final Group scene=new Group();
        final Map<Object,SmartTexture> cache;final Map<String,SmartTexture> previous=new LinkedHashMap<>();
        @SuppressWarnings("unchecked") Fixture()throws Exception {
            Dungeon.level=level;Camera.main=new Camera(0,0,8,8,1);Camera.main.visible=false;
            cache=(Map<Object,SmartTexture>)SnapshotFields.read(TextureCache.class,"all");
            install(Assets.Sprites.MIMIC,256,64);install(Assets.Sprites.GNOLL_GUARD,256,32);
            install(Assets.Sprites.GNOLL_GEOMANCER,256,32);install(Assets.Sprites.ITEMS,256,512);
        }
        void install(String asset,int width,int height)throws Exception {
            SmartTexture texture=FloatingAppearanceTest.allocate(SmartTexture.class);texture.width=width;texture.height=height;
            previous.put(asset,cache.put(asset,texture));
        }
        @Override public void close(){
            Dungeon.level=previousLevel;Camera.main=previousCamera;
            for(Map.Entry<String,SmartTexture> entry:previous.entrySet()) {
                if(entry.getValue()==null)cache.remove(entry.getKey());else cache.put(entry.getKey(),entry.getValue());
            }
        }
    }
}
