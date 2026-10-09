package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Belongings.Backpack;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.TalentButton;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndBag;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTabbed;
import com.watabou.gltextures.SmartTexture;
import com.watabou.gltextures.TextureCache;
import com.watabou.noosa.Camera;
import com.watabou.noosa.ColorBlock;
import com.watabou.noosa.Image;
import com.watabou.noosa.Scene;
import com.watabou.utils.RectF;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.jupiter.api.Assertions.*;

/** Real tab/button constructors, selection and layout with resource-only textures, no GL or profile.
 * Only the unrelated outer Window backend is omitted, not the native Image.copy or fill branches. */
class NativeTabTalentObservationTest {
    @BeforeAll static void resources() { GameSnapshotterTest.resourceOnlyRuntime(); }

    private static final class Owner extends WndTabbed {
        NativeIconTab icon(Image image) { return new NativeIconTab(image); }
        final class NativeIconTab extends IconTab {
            NativeIconTab(Image image) { super(image); }
            void nativeSelect(boolean value) { super.select(value); }
            Image displayedIcon() { return icon; }
        }
    }

    @Test void nativeCopiedTabsKeepEveryCurrentMenuIconAndBothSelectionValues() throws Exception {
        try (Textures ignored = new Textures()) {
            Owner owner = allocate(Owner.class);
            Icons[] sources = {Icons.RANKINGS, Icons.TALENT, Icons.BUFFS, Icons.JOURNAL, Icons.ALCHEMY,
                    Icons.CATALOG, Icons.BADGES, Icons.DISPLAY, Icons.PREFS, Icons.CONTROLLER,
                    Icons.KEYBOARD, Icons.DATA, Icons.AUDIO, Icons.LANGS, Icons.BACKPACK_LRG,
                    Icons.CHALLENGE_COLOR, Icons.BACKPACK, Icons.SEED_POUCH, Icons.SCROLL_HOLDER,
                    Icons.WAND_HOLSTER, Icons.POTION_BANDOLIER};
            for (Icons source : sources) {
                Image original = source.get();
                Owner.NativeIconTab tab = owner.icon(original);
                Scene scene = scene(tab);
                try {
                    tab.setRect(10, 10, 30, 25);
                    UiBridge bridge = new UiBridge(() -> scene);
                    assertNull(Icons.displayedSymbol(tab.displayedIcon()), "Native copy still uses plain Image");
                    tab.nativeSelect(false);
                    Map<String,Object> before = firstNode(bridge);
                    assertEquals(false, before.get("selected"));
                    assertEquals(Icons.displayedSymbol(original), object(before.get("icon")).get("symbol"));
                    Map<String,Object> frozen=PublicEnglishProjection.copy(map("ui",UiDrawFixture.capture(bridge).frozenUi()));
                    assertTabViews(frozen,false,before.get("icon"));
                    String signature = bridge.intentSignature();
                    tab.nativeSelect(true);
                    Map<String,Object> after = firstNode(bridge);
                    assertEquals(true, after.get("selected"));
                    assertEquals(before.get("icon"), after.get("icon"), "Native alpha/crop must preserve identity");
                    assertNotEquals(signature, bridge.intentSignature(), "Selecting a different native page changes the intent");
                    assertTabViews(frozen,false,before.get("icon"));
                    assertTabViews(PublicEnglishProjection.copy(map("ui",UiDrawFixture.capture(bridge).frozenUi())),true,before.get("icon"));
                } finally { scene.destroy(); }
            }
        }
    }

    @Test void copiedItemTabsRetainNativeGuideMaskCrownSymbols() throws Exception {
        try (Textures ignored = new Textures()) {
            Owner owner = allocate(Owner.class);
            int[] sources = {ItemSpriteSheet.MASTERY, ItemSpriteSheet.MASK, ItemSpriteSheet.CROWN,
                    ItemSpriteSheet.SEAL, ItemSpriteSheet.MAGES_STAFF, ItemSpriteSheet.ARTIFACT_CLOAK,
                    ItemSpriteSheet.SPIRIT_BOW, ItemSpriteSheet.RAPIER, ItemSpriteSheet.ARTIFACT_TOME};
            String[] symbols = {"item_mastery", "item_mask", "item_crown", "item_seal", "item_mages_staff",
                    "item_artifact_cloak", "item_spirit_bow", "item_rapier", "item_artifact_tome"};
            for (int i=0; i<sources.length; i++) {
                Owner.NativeIconTab tab = owner.icon(new ItemSprite(sources[i], null));
                Scene scene = scene(tab);
                try {
                    tab.setRect(10, 10, 30, 25); tab.nativeSelect(false);
                    assertEquals(symbols[i], object(firstNode(new UiBridge(() -> scene)).get("icon")).get("symbol"));
                } finally { scene.destroy(); }
            }
        }
    }

    @Test void copiedSymbolsCannotSurviveHiddenDetachedOffscreenOrReplacementImages() throws Exception {
        try (Textures ignored = new Textures()) {
            Owner.NativeIconTab tab = allocate(Owner.class).icon(Icons.TALENT.get());
            Scene scene = scene(tab);
            try {
                tab.setRect(10, 10, 30, 25); tab.nativeSelect(true);
                Image icon = tab.displayedIcon();
                assertTrue(tab.gameplayStatus().containsKey("icon"));
                icon.visible = false; assertFalse(tab.gameplayStatus().containsKey("icon")); icon.visible = true;
                icon.am = Float.NaN; assertFalse(tab.gameplayStatus().containsKey("icon")); icon.am = 1;
                float x=icon.x; icon.x=1000; assertFalse(tab.gameplayStatus().containsKey("icon")); icon.x=x;
                tab.erase(icon); assertFalse(tab.gameplayStatus().containsKey("icon")); tab.add(icon);
                RectF frame=icon.frame(); icon.frame(new RectF(0,0,1,1));
                assertFalse(tab.gameplayStatus().containsKey("icon")); icon.frame(frame);
                SmartTexture texture=icon.texture; icon.texture=newTexture(256,256);
                assertFalse(tab.gameplayStatus().containsKey("icon")); icon.texture=texture;
                assertTrue(tab.gameplayStatus().containsKey("icon"));
                assertEquals(true, tab.gameplayStatus().get("selected"));
            } finally { scene.destroy(); }
        }
    }

    @Test void realBagTabKeepsItsNameAndAddsExplicitSelection() throws Exception {
        try (Textures ignored = new Textures(); TextObservationFixture text = new TextObservationFixture()) {
            Class<?> type=Class.forName(WndBag.class.getName()+"$BagTab");
            Constructor<?> constructor=type.getDeclaredConstructor(WndBag.class,
                    com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag.class,int.class);
            constructor.setAccessible(true);
            Button tab=(Button)constructor.newInstance(allocate(WndBag.class),new Backpack(),0);
            Scene scene=scene(tab);
            try {
                tab.setRect(10,10,30,25);
                Method select=type.getSuperclass().getDeclaredMethod("select",boolean.class);select.setAccessible(true);
                select.invoke(tab,false);
                Map<String,Object> before=firstNode(new UiBridge(()->scene));
                assertEquals("Backpack",before.get("label"));assertEquals(false,before.get("selected"));
                select.invoke(tab,true);
                assertEquals(true,firstNode(new UiBridge(()->scene)).get("selected"));
            } finally { scene.destroy(); }
        }
    }

    @Test void laidOutTalentPointsRemainAvailableWithoutAnyHeroAndBeforeAfterViewsAreIndependent() throws Exception {
        try (Textures ignored = new Textures(); TextObservationFixture text = new TextObservationFixture()) {
            var oldHero=Dungeon.hero; Dungeon.hero=null;
            try {
                for (int spent=0;spent<=Talent.HEARTY_MEAL.maxPoints();spent++) {
                    TalentButton talent=new TalentButton(1,Talent.HEARTY_MEAL,spent,TalentButton.Mode.INFO);
                    Scene scene=scene(talent);
                    try {
                        assertTrue(talent.gameplayStatus().isEmpty(),"Unlaid-out points are not measured zero");
                        talent.setRect(10,10,TalentButton.WIDTH,TalentButton.HEIGHT);
                        UiBridge bridge=new UiBridge(()->scene);
                        Map<String,Object> expected=map("spent",spent,"max",2);
                        Map<String,Object> node=firstNode(bridge);
                        assertEquals(expected,node.get("talent_points"));
                        assertEquals("Hearty Meal",node.get("label"));
                        Map<String,Object> frozen=PublicEnglishProjection.copy(map("ui",UiDrawFixture.capture(bridge).frozenUi()));
                        assertFalse(frozen.containsKey("hero"));
                        assertViews(frozen,expected);
                        ColorBlock fill=(ColorBlock)SnapshotFields.read(talent,"fill");
                        fill.visible=false;
                        assertFalse(firstNode(bridge).containsKey("talent_points"));
                        assertViews(frozen,expected);
                    } finally { scene.destroy(); }
                }
            } finally { Dungeon.hero=oldHero; }
        }
    }

    @Test void talentPointCaptureUsesLastNativeLayoutAndRejectsMissingDisplay() throws Exception {
        try (Textures ignored = new Textures()) {
            TalentButton talent=new TalentButton(1,Talent.HEARTY_MEAL,1,TalentButton.Mode.INFO);
            Scene scene=scene(talent);
            try {
                talent.setRect(10,10,20,26);
                UiBridge bridge=new UiBridge(()->scene);
                String before=bridge.intentSignature();
                field(TalentButton.class,"pointsInTalent").set(talent,2);
                assertEquals(1,object(talent.gameplayStatus().get("talent_points")).get("spent"),
                        "Changing backing points alone is not a displayed update");
                talent.setRect(10,10,20,26);
                assertEquals(2,object(talent.gameplayStatus().get("talent_points")).get("spent"));
                assertNotEquals(before,bridge.intentSignature());
                ColorBlock fill=(ColorBlock)SnapshotFields.read(talent,"fill");
                fill.am=Float.NaN;assertTrue(talent.gameplayStatus().isEmpty());fill.am=1;
                float x=fill.x;fill.x=1000;assertTrue(talent.gameplayStatus().isEmpty());fill.x=x;
                talent.erase(fill);assertTrue(talent.gameplayStatus().isEmpty());talent.add(fill);
                assertFalse(talent.gameplayStatus().isEmpty());
            } finally {scene.destroy();}
        }
    }

    private static void assertViews(Map<String,Object> canonical,Map<String,Object> expected) {
        for(boolean[] view:new boolean[][]{{false,false},{false,true},{true,false}}) {
            Map<String,Object> wire=object(CompactProtocol.expandStructures(CompactProtocol.project(canonical,view[0],view[1])));
            assertEquals(expected,wireNodes(wire).stream().filter(n->n.containsKey("talent_points")).findFirst().orElseThrow().get("talent_points"));
        }
        Map<String,Object> standalone=GameplayObservation.uiForActions(canonical);
        assertEquals(expected,controls(standalone).stream().filter(n->n.containsKey("talent_points")).findFirst().orElseThrow().get("talent_points"));
    }

    private static void assertTabViews(Map<String,Object> canonical,boolean selected,Object icon) {
        for(boolean[] view:new boolean[][]{{false,false},{false,true},{true,false}}) {
            Map<String,Object> wire=object(CompactProtocol.expandStructures(CompactProtocol.project(canonical,view[0],view[1])));
            Map<String,Object> node=wireNodes(wire).stream().filter(n->n.containsKey("selected")).findFirst().orElseThrow();
            assertEquals(selected,node.get("selected"));assertEquals(icon,node.get("icon"));
        }
        Map<String,Object> node=controls(GameplayObservation.uiForActions(canonical)).stream().filter(n->n.containsKey("selected")).findFirst().orElseThrow();
        assertEquals(selected,node.get("selected"));assertEquals(icon,node.get("icon"));
    }

    private static Scene scene(Button button){Scene scene=new Scene();scene.camera=new Camera(0,0,200,200,1);scene.add(button);return scene;}
    private static Map<String,Object> firstNode(UiBridge bridge){return controls(UiDrawFixture.capture(bridge).describeUi()).stream().filter(n->"button".equals(n.get("role"))).findFirst().orElseThrow();}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){return(Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> controls(Map<String,Object> ui){return(List<Map<String,Object>>)ui.get("controls");}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> wireNodes(Map<String,Object> wire){return(List<Map<String,Object>>)object(wire.get("ui")).get("nodes");}
    private static Field field(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static <T>T allocate(Class<T> type)throws Exception{return FloatingAppearanceTest.allocate(type);}
    private static SmartTexture newTexture(int width,int height)throws Exception{SmartTexture t=allocate(SmartTexture.class);t.width=width;t.height=height;return t;}

    private static final class Textures implements AutoCloseable {
        final Map<Object,SmartTexture> cache;
        final Map<String,SmartTexture> previous=new LinkedHashMap<>();
        final int previousZoom=PixelScene.defaultZoom;
        @SuppressWarnings("unchecked") Textures()throws Exception {
            PixelScene.defaultZoom=1;
            cache=(Map<Object,SmartTexture>)SnapshotFields.read(TextureCache.class,"all");
            install(Assets.Interfaces.ICONS,256,256);install(Assets.Interfaces.CHROME,128,128);
            install(Assets.Sprites.ITEMS,256,512);install(Assets.Sprites.ITEM_ICONS,256,256);
            install(Assets.Interfaces.TALENT_BUTTON,80,26);install(Assets.Interfaces.TALENT_ICONS,256,256);
            install("1x1:"+0xFFFFFF44,1,1);
        }
        void install(String key,int width,int height)throws Exception{previous.put(key,cache.put(key,newTexture(width,height)));}
        @Override public void close(){PixelScene.defaultZoom=previousZoom;for(var e:previous.entrySet()){if(e.getValue()==null)cache.remove(e.getKey());else cache.put(e.getKey(),e.getValue());}}
    }
}
