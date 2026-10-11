package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.Recipe;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.scenes.AlchemyScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.ui.*;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.watabou.gltextures.SmartTexture;
import com.watabou.gltextures.TextureCache;
import com.watabou.noosa.*;
import com.watabou.utils.RectF;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native UI source branches and their existing capture/compact pipeline, with no game/profile/GL backend. */
class NativeJournalRecipeSemanticsTest {
    private Hero previousHero;
    private Game previousGame;
    private BitmapText.Font previousFont;
    private TextObservationFixture textHooks;
    private final List<Gizmo> cleanupRoots=new ArrayList<>();
    private final Map<Object,SmartTexture> previous=new LinkedHashMap<>();
    private static final List<Object> EXTRA_TEXTURES=List.of(Assets.Interfaces.ICONS,Assets.Environment.TERRAIN_FEATURES,
            "1x1:"+0x9953564D,"1x1:"+0xFF7b8073);

    @BeforeEach void setup() throws Exception {
        WndGuessAccessibilityTest.resources();
        previousHero=Dungeon.hero;previousGame=Game.instance;previousFont=PixelScene.pixelFont;
        textHooks=new TextObservationFixture();
        Map<Object,SmartTexture> cache=textures();
        for(Object key:EXTRA_TEXTURES) {
            previous.put(key,cache.get(key));SmartTexture tx=allocate(SmartTexture.class);
            tx.width=key.toString().startsWith("1x1:")?1:256;tx.height=key.toString().startsWith("1x1:")?1:512;cache.put(key,tx);
        }
        SmartTexture font=allocate(SmartTexture.class);font.width=512;font.height=8;
        PixelScene.pixelFont=new BitmapText.Font(font,4,8,BitmapText.Font.LATIN_FULL);
        Game.instance=allocate(Game.class);setScene(new Scene());Dungeon.hero=null;
    }
    @AfterEach void cleanup() {
        for(Gizmo root:cleanupRoots)root.destroy();
        Dungeon.hero=previousHero;Game.instance=previousGame;PixelScene.pixelFont=previousFont;
        textHooks.close();Map<Object,SmartTexture> cache=textures();
        for(Object key:EXTRA_TEXTURES){if(previous.get(key)==null)cache.remove(key);else cache.put(key,previous.get(key));}
        WndGuessAccessibilityTest.restore();
    }

    @Test void actualJournalPageButtonClassesRetainSourceIconsSelectionAndOperations() throws Exception {
        for(String name:List.of("AlchemyTab","CatalogTab")) {
            @SuppressWarnings("unchecked") Class<? extends StyledButton> type=(Class<? extends StyledButton>)Class.forName(
                    "com.shatteredpixel.shatteredpixeldungeon.windows.WndJournal$"+name+"$1");
            StyledButton page=group(allocate(type));
            Image icon=new ItemSprite(ItemSpriteSheet.POTION_HOLDER);page.add(icon);
            field(page,StyledButton.class,"icon",icon);icon.x=icon.y=8;
            Scene scene=new Scene();scene.add(page);UiBridge ui=new UiBridge(()->scene);
            page.recordSelectedAppearance(false);Map<String,Object> state=capture(ui);
            Map<?,?> node=nodes(state).get(0);
            assertEquals(false,node.get("selected"));assertEquals("item_potion_holder",((Map<?,?>)node.get("icon")).get("symbol"));
            assertTrue(ui.describeActions().stream().anyMatch(a->"ui.activate".equals(a.get("action"))));
            for(boolean source:List.of(false,true))for(boolean full:List.of(false,true)) {
                String projected=JsonCodec.encode(CompactProtocol.project(state,source,full));
                assertTrue(projected.contains("item_potion_holder"));assertTrue(projected.contains("selected"));
                assertTrue(projected.contains("false"));
            }
            String prior=ui.intentSignature();page.recordSelectedAppearance(true);
            assertNotEquals(prior,ui.intentSignature());assertFalse(ui.drawnIntentReady());
            assertEquals(true,nodes(capture(ui)).get(0).get("selected"));
            icon.frame(ItemSpriteSheet.film.get(ItemSpriteSheet.SEED_HOLDER));
            assertFalse(nodes(capture(ui)).get(0).containsKey("selected"),"A changed source frame cannot reuse selection binding");
            page.active=false;capture(ui);assertTrue(ui.describeActions().stream().noneMatch(a->"ui.activate".equals(a.get("action"))));
        }
    }

    @Test void gridItemKeepsListSelectionAndCopiesNativeSymbolImages() {
        Scene scene=new Scene();ScrollingGridPane grid=new ScrollingGridPane();grid.camera=PixelScene.uiCamera;scene.add(grid);cleanupRoots.add(grid);
        final int[] clicks={0};
        ScrollingGridPane.GridItem entry=new ScrollingGridPane.GridItem(Icons.STAIRS_CHASM.get()) {
            @Override public boolean onClick(float x,float y){if(!inside(x,y))return false;clicks[0]++;return true;}
        };
        grid.addItem(entry);grid.setRect(0,0,100,60);
        UiBridge ui=new UiBridge(()->scene);Map<String,Object> state=capture(ui);
        Map<?,?> node=nodes(state).stream().filter(n->"entry".equals(n.get("role"))).findFirst().orElseThrow();
        assertTrue(((Map<?,?>)node.get("icon")).get("symbol").toString().contains("stairs_chasm"));
        Map<String,Object> operation=ui.describeActions().stream().filter(a->"ui.select".equals(a.get("action"))).findFirst().orElseThrow();
        ui.execute("ui.select",Map.of("control",operation.get("control")));assertEquals(1,clicks[0]);
        assertEquals(node.get("id"),operation.get("control"));
    }

    @Test void copiedCroppedIconsRetainMeaningButLaterReplacementCannotReuseIt() {
        Image source=new ItemSprite(ItemSpriteSheet.MAGES_STAFF);
        GameplayIcons.ImageSource original=GameplayIcons.copySource(source);
        RectF crop=new RectF(source.frame().left,source.frame().top+source.frame().height()/8f,source.frame().right,source.frame().bottom);
        source.frame(crop);
        ScrollingGridPane.GridItem entry=new ScrollingGridPane.GridItem(source,original);
        entry.camera=new Camera(0,0,100,100,1);entry.setRect(8,8,17,17);cleanupRoots.add(entry);
        assertEquals("item_mages_staff",((Map<?,?>)entry.gameplayStatus().get("icon")).get("symbol"));
        Image current=(Image)SnapshotFields.read(entry,"icon");current.frame(ItemSpriteSheet.film.get(ItemSpriteSheet.GOLD));
        assertEquals("unmapped_indicator",((Map<?,?>)entry.gameplayStatus().get("icon")).get("symbol"));
        assertTrue(entry.gameplayStatus().toString().contains("stale_indicator_binding"));
        current.visible=false;assertFalse(entry.gameplayStatus().containsKey("icon"));
        current.visible=true;current.x=-1;assertFalse(entry.gameplayStatus().containsKey("icon"),"Partially clipped image has no complete sample");
    }

    @Test void undiscoveredCatalogHidesExactIdentityAndBadgeWhileVisibleNumbersRemainText() {
        ScrollingGridPane.GridItem entry=new ScrollingGridPane.GridItem(new ItemSprite(ItemSpriteSheet.ARTIFACT_ARMBAND));
        entry.camera=new Camera(0,0,100,100,1);entry.setRect(8,8,17,17);cleanupRoots.add(entry);
        entry.addSecondIcon(new ItemSprite(ItemSpriteSheet.GOLD));entry.recordCatalogState("undiscovered");
        Map<String,Object> hidden=entry.gameplayStatus();
        assertEquals(Map.of("symbol","catalog_undiscovered","placeholder",true),hidden.get("icon"));
        assertEquals(Map.of("catalog_state","undiscovered"),hidden.get("shown"));assertFalse(hidden.containsKey("secondary_icon"));
        for(boolean src:List.of(false,true))assertFalse(JsonCodec.encode(CompactProtocol.project(hidden,src,true)).contains("armband"));
        entry.recordCatalogState("discovered");Map<String,Object> known=entry.gameplayStatus();
        assertTrue(known.get("icon").toString().contains("artifact_armband"));assertTrue(known.containsKey("secondary_icon"));
        entry.recordCatalogState("unread");assertEquals("unread",((Map<?,?>)entry.gameplayStatus().get("shown")).get("catalog_state"));
        entry.recordCatalogState("read");assertEquals("read",((Map<?,?>)entry.gameplayStatus().get("shown")).get("catalog_state"));
        assertThrows(IllegalArgumentException.class,()->entry.recordCatalogState("guessed"));
        BitmapText count=new BitmapText(ResourceTextFixture.literal("3"),PixelScene.pixelFont);count.measure();entry.addSecondIcon(count);
        assertFalse(entry.gameplayStatus().containsKey("secondary_icon"));
        Scene scene=new Scene();scene.add(entry);assertTrue(JsonCodec.encode(capture(new UiBridge(()->scene))).contains("3"));
    }

    @Test void terrainFeatureSymbolsDescribeOnlyDisplayedTrapShapeAndPlantIcon() {
        Image trap=new Image(textures().get(Assets.Environment.TERRAIN_FEATURES));trap.camera=new Camera(0,0,100,100,1);
        trap.frame(0,3*16,16,16);assertEquals("trap_red_stars",GameplayIcons.image(trap).get("symbol"));
        trap.frame(3*16,7*16,16,16);assertEquals("plant_sungrass",GameplayIcons.image(trap).get("symbol"));
        trap.frame(15*16,7*16,16,16);assertEquals("unmapped_indicator",GameplayIcons.image(trap).get("symbol"));
    }

    @Test void nativeQuickRecipeDistinguishesSufficientMissingNoHeroAndDisabledOutsideAlchemy() throws Exception {
        Hero hero=new Hero();hero.belongings.backpack.items.add(new Food().quantity(2));Dungeon.hero=hero;
        for(boolean inAlchemy:List.of(false,true)) {
            setScene(inAlchemy?new AlchemyScene():new Scene());
            QuickRecipe enough=recipe(new Food().quantity(2));place(enough);
            assertEquals(true,shown(input(enough),"ingredient_sufficient"));
            assertEquals(true,shown(output(enough),"ingredients_sufficient"));
            Button arrow=(Button)SnapshotFields.read(enough,"arrow");
            assertEquals(true,shown((GameplayStatus)arrow,"ingredients_sufficient"));
            assertEquals(inAlchemy,arrow.active,"Outside alchemy hasInputs remains true although filling is disabled");
        }
        QuickRecipe missing=recipe(new Food().quantity(3));place(missing);
        assertEquals(false,shown(input(missing),"ingredient_sufficient"));assertEquals(false,shown(output(missing),"ingredients_sufficient"));
        Dungeon.hero=null;QuickRecipe noHero=recipe(new Food());place(noHero);
        assertNull(shown(input(noHero),"ingredient_sufficient"));assertNull(shown(output(noHero),"ingredients_sufficient"));
        assertNull(shown((GameplayStatus)SnapshotFields.read(noHero,"arrow"),"ingredients_sufficient"));
    }

    @Test void nativeQuickRecipePreservesPerIngredientFailuresAndIdentificationGateWithoutRecomputing() throws Exception {
        Hero hero=new Hero();Dungeon.hero=hero;setScene(new AlchemyScene());Potion.clearColors();Potion.initColors();
        PotionOfHealing potion=new PotionOfHealing();hero.belongings.backpack.items.add(potion);
        hero.belongings.backpack.items.add(new Food());
        QuickRecipe mixed=new QuickRecipe(new CostOnlyRecipe(),new ArrayList<>(List.of(new Food(),new PotionOfHealing())),new Food());place(mixed);
        List<?> inputs=(List<?>)SnapshotFields.read(mixed,"inputs");
        assertEquals(true,shown((ItemSlot)inputs.get(0),"ingredient_sufficient"));
        assertEquals(false,shown((ItemSlot)inputs.get(1),"ingredient_sufficient"));
        assertEquals(false,shown(output(mixed),"ingredients_sufficient"));
        // Native construction has selected the display. Later observation never asks inventory/recipe again.
        hero.belongings.backpack.items.clear();
        assertEquals(true,shown((ItemSlot)inputs.get(0),"ingredient_sufficient"));
        ItemSlot first=(ItemSlot)inputs.get(0);first.item(new Food());
        assertNull(shown(first,"ingredient_sufficient"),"A reused item slot clears its previous recipe binding");
    }

    @Test void recipeSemanticFieldsSurviveMergeAllViewsAndFrozenStructureExpansion() throws Exception {
        Dungeon.hero=new Hero();setScene(new AlchemyScene());QuickRecipe recipe=recipe(new Food().quantity(2));place(recipe);
        Scene scene=Game.scene();scene.add(recipe);UiBridge ui=new UiBridge(()->scene);
        ui.captureDrawnEvidence();Map<String,Object> canonical=GameplayObservation.merge(Map.of("ui",ui.frozenUi(Map.of()),"actions",ui.frozenActions()));
        for(boolean src:List.of(false,true))for(boolean full:List.of(false,true)) {
            Object wire=CompactProtocol.project(PublicEnglishProjection.copy(canonical),src,full);String json=JsonCodec.encode(wire);
            assertTrue(json.contains("ingredient_sufficient"));assertTrue(json.contains("ingredients_sufficient"));assertTrue(json.contains("false"));
            Object expanded=CompactProtocol.expandStructures(wire);assertTrue(JsonCodec.encode(expanded).contains("ingredient_sufficient"));
        }
        Map<String,Object> snapshot=new LinkedHashMap<>(canonical);Map<String,Object> history=Map.of("before",snapshot,"after",snapshot);
        assertTrue(JsonCodec.encode(CompactProtocol.expandStructures(CompactProtocol.project(PublicEnglishProjection.copy(history),false,true))).contains("ingredients_sufficient"));
    }

    private static final class CostOnlyRecipe extends Recipe {
        @Override public boolean testIngredients(ArrayList<Item> i){throw new AssertionError("Unexpected recipe probe");}
        @Override public int cost(ArrayList<Item> i){return 0;}
        @Override public Item brew(ArrayList<Item> i){throw new AssertionError("Unexpected brew");}
        @Override public Item sampleOutput(ArrayList<Item> i){throw new AssertionError("Unexpected output creation");}
    }
    private static QuickRecipe recipe(Item input){return new QuickRecipe(new CostOnlyRecipe(),new ArrayList<>(List.of(input)),new Food());}
    private void place(QuickRecipe recipe){cleanupRoots.add(recipe);recipe.camera=new Camera(0,0,300,200,1);recipe.setPos(8,8);}
    private static ItemSlot input(QuickRecipe recipe){return (ItemSlot)((List<?>)SnapshotFields.read(recipe,"inputs")).get(0);}
    private static ItemSlot output(QuickRecipe recipe){return (ItemSlot)SnapshotFields.read(recipe,"output");}
    private static Object shown(GameplayStatus status,String key){Object shown=status.gameplayStatus().get("shown");return shown instanceof Map?((Map<?,?>)shown).get(key):null;}
    private static Map<String,Object> capture(UiBridge ui){ui.captureDrawnEvidence();return Map.of("ui",ui.describeUi(),"actions",ui.describeActions());}
    @SuppressWarnings("unchecked") private static List<Map<?,?>> nodes(Map<String,Object> state){return (List<Map<?,?>>)((Map<?,?>)state.get("ui")).get("controls");}
    @SuppressWarnings("unchecked") private static Map<Object,SmartTexture> textures(){return (Map<Object,SmartTexture>)SnapshotFields.read(TextureCache.class,"all");}
    private static void setScene(Scene scene)throws Exception{field(Game.instance,Game.class,"scene",scene);}
    private static <T>T allocate(Class<T> type)throws Exception{return FloatingAppearanceTest.allocate(type);}
    private static void field(Object value,Class<?> owner,String name,Object data)throws Exception{FloatingAppearanceTest.field(value,owner,name,data);}
    private static <T extends Group>T group(T value)throws Exception{field(value,Group.class,"members",new ArrayList<>());value.exists=value.alive=value.visible=value.active=true;value.camera=new Camera(0,0,300,200,1);return value;}
}
