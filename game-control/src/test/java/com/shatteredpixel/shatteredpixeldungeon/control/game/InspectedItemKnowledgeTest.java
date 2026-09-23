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

    @Test void originalResourceIdentitySelectsKnownVersusTypicalDamageWithoutWindowHeuristics() {
        String actual=ResourceTextFixture.source("items.weapon.melee.spear.ability_desc",11,24);
        String typical=ResourceTextFixture.source("items.weapon.melee.spear.typical_ability_desc",11,24);
        assertEquals(actual,typical,"The Chinese resource strings intentionally collide");
        assertEquals(ACTUAL,ResourceTextFixture.english(actual));
        assertEquals(TYPICAL,ResourceTextFixture.english(typical));
        assertEquals("partial",ResourceTextFixture.status(new String(actual)));
    }

    @Test void provenanceCannotBorrowHiddenStatsOrExpandAnUntrackedSuffix() {
        String typical=ResourceTextFixture.source("items.weapon.melee.spear.typical_ability_desc",11,24);
        Map<String,Object> first=map("text",typical,"hidden_level",99,"hidden_curse",true);
        Map<String,Object> second=map("text",typical,"hidden_level",-9,"hidden_curse",false);
        assertEquals(PublicEnglishProjection.copy(first).get("text"),PublicEnglishProjection.copy(second).get("text"));
        assertEquals("partial",ResourceTextFixture.status(typical+" unknown suffix"));
        Map<String,Object> clipped=PublicEnglishProjection.copy(map("text",typical,"clipped",true));
        assertEquals("Partially displayed text",clipped.get("text"));
        assertFalse(clipped.toString().contains("spear"));
    }

    @Test void repeatedUiQueriesUseTheCachedBodyAndCapturedKnowledgeEvenAfterTheItemChanges() throws Exception {
        SpySpear item=new SpySpear();Scene scene=new Scene();WndInfoItem window=window(item,false);scene.add(window);
        UiBridge bridge=new UiBridge(()->scene);
        Map<String,Object> first=UiDrawFixture.capture(bridge).describeUi();assertTrue(first.toString().contains(TYPICAL));
        item.levelKnown=true;item.cursedKnown=true;set(item,Item.class,"level",7);
        for(int i=0;i<10;i++) {
            assertEquals(first,UiDrawFixture.capture(bridge).describeUi());bridge.describeActions();
        }
        assertEquals(0,item.infoCalls);assertSame(item,window.inspectedItem());assertEquals(false,window.inspectedLevelKnown());
        scene.erase(window);WndInfoItem reopened=window(item,true);scene.add(reopened);
        assertTrue(UiDrawFixture.capture(bridge).describeUi().toString().contains(ACTUAL));assertEquals(0,item.infoCalls);
    }

    @Test void aContainerWindowWithoutADisplayedSubjectNeverReadsHiddenItems() throws Exception {
        Scene scene=new Scene();WndInfoItem container=window(null,null);scene.add(container);
        set(container,Group.class,"members",new ArrayList<Gizmo>());set(container,Group.class,"length",0);
        UiBridge bridge=new UiBridge(()->scene);
        for(int i=0;i<10;i++){assertNull(UiDrawFixture.capture(bridge).describeUi().get("inspected_item"));bridge.describeActions();}
    }

    @Test void coveringOrRemovingTheWindowDropsItsPublishedKnowledge() throws Exception {
        Scene scene=new Scene();WndInfoItem inspected=window(new Spear(),false);scene.add(inspected);
        UiBridge bridge=new UiBridge(()->scene);assertNotNull(UiDrawFixture.capture(bridge).describeUi().get("inspected_item"));
        Window other=allocate(Window.class);initGroup(other);scene.add(other);
        assertNull(UiDrawFixture.capture(bridge).describeUi().get("inspected_item"));scene.erase(other);
        assertNotNull(UiDrawFixture.capture(bridge).describeUi().get("inspected_item"));scene.erase(inspected);
        assertNull(UiDrawFixture.capture(bridge).describeUi().get("inspected_item"));
    }

    private static final class SpySpear extends Spear {
        int infoCalls;
        @Override public String info(){infoCalls++;throw new AssertionError("A query must not regenerate an item description");}
    }
    private static WndInfoItem window(Item item,Boolean known) throws Exception {
        WndInfoItem result=allocate(WndInfoItem.class);initGroup(result);
        set(result,WndInfoItem.class,"inspectedItem",item);set(result,WndInfoItem.class,"inspectedLevelKnown",known);
        RenderedTextBlock body=ResourceTextFixture.laidOut(ResourceTextFixture.source(Boolean.TRUE.equals(known)
                ?"items.weapon.melee.spear.ability_desc":"items.weapon.melee.spear.typical_ability_desc",11,24));result.add(body);
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
