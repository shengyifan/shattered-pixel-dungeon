package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Spear;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndInfoItem;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;
import com.watabou.noosa.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.jupiter.api.Assertions.*;

/** No native window, GL context, game launch or user save is used by these tests. */
class InspectedItemKnowledgeTest {
    static final String CHINESE="决斗家用矛尖_刺退_在射程内但不与决斗家相邻的敌人，造成_11~24点伤害_，将敌人击退且必定命中。";
    static final String ACTUAL="The Duelist can use the tip of a spear to _spike_ an enemy that is in range but not adjacent. This deals _11-24 damage_, knocks the enemy back, and is guaranteed to hit.";
    static final String TYPICAL=ACTUAL.replace("This deals", "This typically deals");

    @BeforeAll static void resources(){GameSnapshotterTest.resourceOnlyRuntime();}

    @Test void knowledgeIsMinimalAndHiddenLevelOrCurseCannotChangeTheUnknownProjection() throws Exception {
        Spear first=new Spear(),second=new Spear();
        set(first,Item.class,"level",2);set(second,Item.class,"level",9);second.cursed=true;
        assertEquals(map("level_known",false),PlayerObservation.inspectedItemKnowledge(first,false));
        assertEquals(PlayerObservation.inspectedItemKnowledge(first,false),PlayerObservation.inspectedItemKnowledge(second,false));
        assertNull(PlayerObservation.inspectedItemKnowledge(null,false));
        assertEquals(map("level_known",null),PlayerObservation.inspectedItemKnowledge(new Food(),true));
    }

    @Test void levelKnowledgeDoesNotRequireCurseKnowledgeAndNeverDefaultsMissingToFalse() {
        Spear item=new Spear();item.levelKnown=true;item.cursedKnown=false;
        assertFalse(item.isIdentified());
        assertEquals(map("level_known",true),PlayerObservation.inspectedItemKnowledge(item,true));
        assertEquals(map("level_known",false),PlayerObservation.inspectedItemKnowledge(item,false));
        assertEquals(map("level_known",null),PlayerObservation.inspectedItemKnowledge(item,null));
    }

    @Test void onlyThePublishedBooleanSelectsTheTwoCompleteSpearTemplates() {
        DisplayedTextEnglish text=new DisplayedTextEnglish();
        assertEquals(ACTUAL,text.translateInContext(CHINESE,map("inspected_item_level_known",true)));
        assertEquals(TYPICAL,text.translateInContext(CHINESE,map("inspected_item_level_known",false)));
        for(Object missing:Arrays.asList(null,"false",0,1))
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->text.translateInContext(CHINESE,map("inspected_item_level_known",missing)));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->text.translate(CHINESE));
    }

    @Test void theContextCannotSupplyUndisplayedNumbersOrConsumeUnknownTailText() {
        DisplayedTextEnglish text=new DisplayedTextEnglish();
        Map<String,Object> first=map("inspected_item_level_known",false,"hidden_level",99,"hidden_curse",true);
        Map<String,Object> second=map("inspected_item_level_known",false,"hidden_level",-9,"hidden_curse",false);
        assertEquals(text.translateInContext(CHINESE,first),text.translateInContext(CHINESE,second));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->text.translateInContext(CHINESE+"未知的尾文",first));
        String prefix=CHINESE.substring(0,20);
        assertEquals("Partially displayed text",text.translateVisibleInContext(prefix,true,first).text);
        assertEquals(text.translateVisibleInContext(prefix,true,first).text,text.translateVisibleInContext(prefix,true,second).text);
    }

    @Test void fullNativeSpearBodyKeepsItsCompleteMultilineStatResourceTogether() {
        String body="这是一根装着锋锐铁刺的细长木杆。\n\n这件_2阶_近战武器可以造成_9~48点伤害_，并且需要_9点力量_来正常使用。 你的额外力量会使你在使用这件武器时造成_0~91点额外伤害_。\n\n这是一件相当慢的武器。\n这件武器有额外的攻击距离。\n\n"+CHINESE;
        DisplayedTextEnglish text=new DisplayedTextEnglish();
        String actual=text.translateInContext(body,map("inspected_item_level_known",true));
        assertTrue(actual.endsWith(ACTUAL));assertTrue(actual.contains("9-48"));
        assertTrue(text.translateInContext(body,map("inspected_item_level_known",false)).endsWith(TYPICAL));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->text.translateInContext(body+"未知尾文",map("inspected_item_level_known",true)));
    }

    @Test void booleanAppliesToThePublishedWindowAndItsDescendantsOnly() {
        Map<String,Object> ui=ui(false);
        assertEquals(TYPICAL,PublicEnglishProjection.copyWithUi(body(),ui).get("text"));
        Map<String,Object> unrelated=body();unrelated.put("parent","another-window");
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(unrelated,ui));
        Map<String,Object> projected=PublicEnglishProjection.copy(ui);
        assertEquals(map("control","window","level_known",false),projected.get("inspected_item"));
        assertTrue(projected.toString().contains(TYPICAL));assertEquals(CHINESE,body().get("text"));
    }

    @SuppressWarnings("unchecked")
    @Test void malformedOrNoncurrentWindowAssociationCannotGrantAnInterpretation() {
        for(Object known:Arrays.asList(null,"false",0)) {
            Map<String,Object> ui=ui(false);ui.put("inspected_item",map("control","window","level_known",known));
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(body(),ui));
        }
        for(String owner:Arrays.asList("missing","body")) {
            Map<String,Object> ui=ui(false);ui.put("inspected_item",map("control",owner,"level_known",false));
            assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(body(),ui));
        }
        Map<String,Object> ui=ui(false);ui.put("modal",false);
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(body(),ui));
        Map<String,Object> nested=ui(false);((Map<String,Object>)((List<?>)nested.get("controls")).get(0)).put("parent","covered-window");
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(body(),nested));
    }

    @Test void oldHistoryOrTheNextWindowCannotBorrowAFormerInspectedItem() {
        assertEquals(ACTUAL,PublicEnglishProjection.copyWithUi(body(),ui(true)).get("text"));
        Map<String,Object> next=ui(false);next.remove("inspected_item");
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(body(),next));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copy(body()));
        next.put("inventory",List.of(map("name","spear","level_known",false)));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copyWithUi(body(),next));
    }

    @Test void repeatedUiQueriesUseTheCachedBodyAndCapturedKnowledgeEvenAfterTheItemChanges() throws Exception {
        SpySpear item=new SpySpear();Scene scene=new Scene();WndInfoItem window=window(item,false);scene.add(window);
        UiBridge bridge=new UiBridge(()->scene);
        Map<String,Object> first=bridge.describeUi();assertTrue(first.toString().contains(TYPICAL));
        item.levelKnown=true;item.cursedKnown=true;set(item,Item.class,"level",7);
        for(int i=0;i<10;i++) {
            assertEquals(first,bridge.describeUi());bridge.describeActions();
        }
        assertEquals(0,item.infoCalls);assertSame(item,window.inspectedItem());assertEquals(false,window.inspectedLevelKnown());
        scene.erase(window);WndInfoItem reopened=window(item,true);scene.add(reopened);
        assertTrue(bridge.describeUi().toString().contains(ACTUAL));assertEquals(0,item.infoCalls);
    }

    @Test void aContainerWindowWithoutADisplayedSubjectNeverReadsHiddenItems() throws Exception {
        Scene scene=new Scene();WndInfoItem container=window(null,null);scene.add(container);
        set(container,Group.class,"members",new ArrayList<Gizmo>());set(container,Group.class,"length",0);
        UiBridge bridge=new UiBridge(()->scene);
        for(int i=0;i<10;i++){assertNull(bridge.describeUi().get("inspected_item"));bridge.describeActions();}
    }

    @Test void coveringOrRemovingTheWindowDropsItsPublishedKnowledge() throws Exception {
        Scene scene=new Scene();WndInfoItem inspected=window(new Spear(),false);scene.add(inspected);
        UiBridge bridge=new UiBridge(()->scene);assertNotNull(bridge.describeUi().get("inspected_item"));
        Window other=allocate(Window.class);initGroup(other);scene.add(other);
        assertNull(bridge.describeUi().get("inspected_item"));scene.erase(other);
        assertNotNull(bridge.describeUi().get("inspected_item"));scene.erase(inspected);
        assertNull(bridge.describeUi().get("inspected_item"));
    }

    private static Map<String,Object> body(){return map("id","body","role","renderedtext","parent","window","text",CHINESE);}
    private static Map<String,Object> ui(boolean known){return map("scene","GameScene","modal",true,
            "controls",List.of(map("id","window","role","window"),body()),"inspected_item",map("control","window","level_known",known));}
    private static final class SpySpear extends Spear {
        int infoCalls;
        @Override public String info(){infoCalls++;throw new AssertionError("A query must not regenerate an item description");}
    }
    private static WndInfoItem window(Item item,Boolean known) throws Exception {
        WndInfoItem result=allocate(WndInfoItem.class);initGroup(result);
        set(result,WndInfoItem.class,"inspectedItem",item);set(result,WndInfoItem.class,"inspectedLevelKnown",known);
        RenderedTextBlock body=allocate(RenderedTextBlock.class);initGroup(body);set(body,RenderedTextBlock.class,"text",CHINESE);result.add(body);
        return result;
    }
    private static void initGroup(Group group) throws Exception {
        group.exists=group.alive=group.active=group.visible=true;
        set(group,Group.class,"members",new ArrayList<Gizmo>());set(group,Group.class,"length",0);
    }
    private static <T>T allocate(Class<T> type) throws Exception {
        Class<?> unsafeType=Class.forName("sun.misc.Unsafe");Field field=unsafeType.getDeclaredField("theUnsafe");field.setAccessible(true);
        return type.cast(unsafeType.getMethod("allocateInstance",Class.class).invoke(field.get(null),type));
    }
    private static void set(Object target,Class<?> type,String name,Object value)throws Exception {
        Field field=type.getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
}
