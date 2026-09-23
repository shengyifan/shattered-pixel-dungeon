package com.shatteredpixel.shatteredpixeldungeon.control.game;

import org.junit.jupiter.api.Test;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import java.util.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.game.SnapshotFields.map;
import static org.junit.jupiter.api.Assertions.*;

class GameplayObservationTest {
    @Test void itemFactsMoveOnceUsingExactLocatorAndFrozenInputIsUnchanged(){
        Map<String,Object> item=map("locator","backpack.0","name","waterskin");
        Map<String,Object> other=map("locator","backpack.1","name","waterskin");
        Map<String,Object> slot=map("id","slot","role","button","text","6/20","shown",map("status","6/20"));
        Map<String,Object> child=map("id","count","role","text","parent","slot","text","6/20");
        Map<String,Object> quick=map("id","quick","role","button","shown",map("status","6/20"));
        Map<String,UiProjectionHints.Node> hints=new LinkedHashMap<>();
        hints.put("slot",hint(map("kind","item","loc","backpack.0"),List.of("count"),Map.of("status","count"),true));
        hints.put("quick",hint(map("kind","item","loc","backpack.0"),List.of(),Map.of(),true));
        Map<String,Object> source=observation(List.of(item,other),List.of(slot,child,quick),hints);
        String before=source.toString();Map<String,Object> merged=GameplayObservation.merge(source);
        assertEquals(before,source.toString());
        assertEquals(Map.of("status","6/20"),item(merged,0).get("shown"));assertFalse(item(merged,1).containsKey("shown"));
        assertEquals(List.of("slot","quick"),ids(merged));
        for(Map<String,Object> node:nodes(merged)){assertFalse(node.containsKey("shown"));assertFalse(node.containsKey("text"));assertEquals("backpack.0",object(node.get("subject")).get("loc"));}
        item(merged,0).put("name","changed");assertEquals("waterskin",item.get("name"));
    }

    @Test void twoDistinctQuantizedHealthSamplesAndBuffIndicesRemainDistinct(){
        Map<String,Object> sample=map("total",32,"filled",8,"with_shield",10,"basis","displayed");
        List<Object> controls=List.of(map("id","bar1","role","health_bar","health_estimate",map("samples",List.of(sample))),
                map("id","bar2","role","health_bar","health_estimate",map("samples",List.of(map("total",21,"filled",6,"with_shield",7,"basis","displayed")))),
                map("id","buff","role","button","shown",map("counter","2")));
        Map<String,UiProjectionHints.Node> hints=Map.of("bar1",hint(map("kind","entity","index",0),List.of(),Map.of(),false),
                "bar2",hint(map("kind","entity","index",0),List.of(),Map.of(),false),
                "buff",hint(map("kind","hero_buff","index",1),List.of(),Map.of(),true));
        Map<String,Object> source=observation(List.of(),controls,hints);
        source.put("hero",map("buffs",List.of(map("name","same"),map("name","same"))));
        source.put("visible_entities",List.of(map("kind","character","cell",0)));
        Map<String,Object> merged=GameplayObservation.merge(source);
        List<?> buffs=(List<?>)object(merged.get("hero")).get("buffs");assertFalse(object(buffs.get(0)).containsKey("shown"));assertEquals(Map.of("counter","2"),object(buffs.get(1)).get("shown"));
        Map<String,Object> entity=object(((List<?>)merged.get("visible_entities")).get(0));
        assertEquals(2,((List<?>)object(entity.get("health_estimate")).get("samples")).size());assertEquals(List.of("buff"),ids(merged));
    }

    @Test void unavailableOrAmbiguousSubjectsNeverConsumeDisplayEvidence(){
        Map<String,Object> node=map("id","slot","role","button","shown",map("status","0"));
        Map<String,Object> source=observation(List.of(map("locator","same"),map("locator","same")),List.of(node),
                Map.of("slot",hint(map("kind","item","loc","same"),List.of(),Map.of(),true)));
        Map<String,Object> merged=GameplayObservation.merge(source);
        assertEquals(node,nodes(merged).get(0));assertFalse(item(merged,0).containsKey("shown"));
    }

    @Test void feedbackAndProtectedTextSurviveWhileEmptyLeavesDisappear(){
        Map<String,Object> protectedText=map("id","protected","role","text","text","partial","clipped",true,
                "text_diagnostics",map("text","clipped_text"));
        Map<String,Object> message=map("id","log","role","text","text","user text","text_origins",map("text",List.of("external")));
        Map<String,UiProjectionHints.Node> hints=Map.of("log",new UiProjectionHints.Node(false,List.of(),null,Map.of(),null,"log",false),
                "banner",new UiProjectionHints.Node(false,List.of(),null,Map.of(),null,"banner",false));
        Map<String,Object> source=observation(List.of(),List.of(map("id","empty","role","text"),protectedText,message,
                map("id","banner","role","status","banner_kind","boss_slain")),hints);
        Map<String,Object> merged=GameplayObservation.merge(source);assertEquals(List.of("protected"),ids(merged));
        List<?> feedback=(List<?>)object(merged.get("ui")).get("feedback");assertEquals(2,feedback.size());
        assertEquals(message.get("text_origins"),object(feedback.get(0)).get("text_origins"));assertEquals("boss_slain",object(feedback.get(1)).get("banner_kind"));
    }

    @Test void iconOnlyFloatingFeedbackRetainsItsPublicSymbolWhenNoGlyphIsShown(){
        Map<String,Object> icon=map("symbol","physical_damage");
        Map<String,Object> source=observation(List.of(),List.of(map("id","floating","role","text","cell",42,"icon",icon)),
                Map.of("floating",new UiProjectionHints.Node(false,List.of(),null,Map.of(),null,"floating",false)));
        Map<String,Object> merged=GameplayObservation.merge(source);
        assertEquals(List.of(),nodes(merged));List<?> feedback=(List<?>)object(merged.get("ui")).get("feedback");
        assertEquals(1,feedback.size());Map<String,Object> entry=object(feedback.get(0));
        assertEquals("floating",entry.get("kind"));assertEquals(42,entry.get("cell"));assertEquals(icon,entry.get("icon"));
        assertFalse(entry.containsKey("text"),"An icon does not authorize inventing missing text");
    }

    @Test void indexedMetadataAndCapacitySlotsPreventUnsafeLayoutRewrites(){
        Map<String,Object> source=observation(List.of(),List.of(map("id","empty","role","text"),map("id","capacity","role","button","enabled",false)),Map.of());
        source.put("text_diagnostics",map("ui.controls[0].text","clipped_text"));assertEquals(source,GameplayObservation.merge(source));
        source.remove("text_diagnostics");assertEquals(List.of("capacity"),ids(GameplayObservation.merge(source)));
    }

    @Test void conflictingCurrentItemDisplaysRemainExplicit(){
        Map<String,Object> source=observation(List.of(map("locator","backpack.0")),List.of(
                map("id","first","role","button","shown",map("status","1/20")),map("id","second","role","button","shown",map("status","2/20"))),
                Map.of("first",hint(map("kind","item","loc","backpack.0"),List.of(),Map.of(),true),"second",hint(map("kind","item","loc","backpack.0"),List.of(),Map.of(),true)));
        Map<String,Object> merged=GameplayObservation.merge(source);
        assertEquals(Map.of("status","1/20"),item(merged,0).get("shown"));assertEquals(Map.of("status","2/20"),nodes(merged).get(1).get("shown"));
    }

    @Test void quantityAndKnownLevelStringsAreNotRepeatedButStrengthEstimatesRemainSemantic(){
        Map<String,Object> source=observation(List.of(map("locator","backpack.0","quantity",3,"level_known",true,"level",1)),
                List.of(map("id","slot","role","button","shown",map("status","3","status_kind","quantity","level","+1","extra","14?",
                        "strength",map("value",14,"estimated",true),"flags",List.of("last_use")))),
                Map.of("slot",hint(map("kind","item","loc","backpack.0"),List.of(),Map.of(),true)));
        Map<String,Object> shown=object(item(GameplayObservation.merge(source),0).get("shown"));
        assertFalse(shown.containsKey("status"));assertFalse(shown.containsKey("level"));assertFalse(shown.containsKey("extra"));
        assertEquals(Map.of("value",14,"estimated",true),shown.get("strength"));assertEquals(List.of("last_use"),shown.get("flags"));
    }

    @Test void numericSpecialStatusEqualToQuantityRemainsUnlessNativeQuantityRoleProvesIt(){
        Map<String,Object> source=observation(List.of(map("locator","equipment.artifact","quantity",1)),
                List.of(map("id","artifact","role","button","shown",map("status","1"))),
                Map.of("artifact",hint(map("kind","item","loc","equipment.artifact"),List.of(),Map.of(),true)));
        assertEquals(map("status","1"),item(GameplayObservation.merge(source),0).get("shown"));
        object(((List<?>)object(source.get("ui")).get("controls")).get(0)).put("shown",map("status","1","status_kind","quantity"));
        assertFalse(item(GameplayObservation.merge(source),0).containsKey("shown"));
    }

    @Test void equalRenderedTextWithDifferentSourceTreesNeverCollapses(){
        Map<String,Object> first=map("$text_source",1,"node",map("kind","literal","origin","catalog","value","6/20"));
        Map<String,Object> second=map("$text_source",1,"node",map("kind","literal","origin","literal","value","6/20"));
        Map<String,Object> source=observation(List.of(map("locator","backpack.0")),List.of(
                map("id","first","role","button","shown",map("status",first)),map("id","second","role","button","shown",map("status",second))),
                Map.of("first",hint(map("kind","item","loc","backpack.0"),List.of(),Map.of(),true),"second",hint(map("kind","item","loc","backpack.0"),List.of(),Map.of(),true)));
        Map<String,Object> merged=GameplayObservation.merge(source);
        assertEquals(first,object(item(merged,0).get("shown")).get("status"));assertEquals(second,object(nodes(merged).get(1).get("shown")).get("status"));
    }

    @Test void frostOnlyRemovesItsProvenRedundantSpriteFactsWithoutRequiringAUi(){
        Map<String,Object> frost=frost();
        Map<String,Object> cue=map("kind","sprite_state","cell",12,"appearance",map("style","icy","paused",true));
        Map<String,Object> source=map("hero",map("cell",12,"depth",1,"buffs",List.of(frost)),
                "visual_cues",map("depth",1,"cues",List.of(cue)));
        assertEquals(List.of(),worldCues(GameplayObservation.merge(source)));assertEquals(1,worldCues(source).size());
        object(source.get("hero")).put("buffs",List.of(map("name","Frost")));
        assertEquals(List.of(cue),worldCues(GameplayObservation.merge(source)),"A matching name cannot prove a native visual equivalence");
    }

    @Test void frostKeepsAmbiguousCellsUniqueVisualFactsAndPartialEvidence(){
        Map<String,Object> hero=map("cell",12,"buffs",List.of(frost()));
        Map<String,Object> cue=map("kind","sprite_state","cell",12,"appearance",map("style","icy","paused",true,"invisible",true));
        Map<String,Object> source=map("hero",hero,"visual_cues",map("cues",List.of(cue)));
        assertEquals(map("invisible",true),object(worldCues(GameplayObservation.merge(source)).get(0)).get("appearance"));
        source.put("visible_entities",List.of(map("kind","character","cell",12,"buffs",List.of(map("icon",BuffIndicator.FROST)))));
        assertEquals(List.of(cue),worldCues(GameplayObservation.merge(source)));
        source.remove("visible_entities");object(cue.get("appearance")).put("partial",true);
        assertEquals(List.of(cue),worldCues(GameplayObservation.merge(source)));
        object(cue.get("appearance")).remove("partial");cue.put("text_diagnostics",map("appearance.style","fixture"));
        assertEquals(List.of(cue),worldCues(GameplayObservation.merge(source)));
    }

    @Test void chillSharedIconCannotAuthorizeFrostVisualRemoval(){
        Map<String,Object> chill=map("icon",BuffIndicator.FROST,"name",map("$text_source",1,"node",map("kind","resource","key","actors.buffs.chill.name","args",List.of())));
        Map<String,Object> cue=map("kind","sprite_state","cell",12,"appearance",map("style","icy","paused",true));
        Map<String,Object> source=map("hero",map("cell",12,"buffs",List.of(chill)),"visual_cues",map("cues",List.of(cue)));
        assertEquals(List.of(cue),worldCues(GameplayObservation.merge(source)));
        object(source.get("hero")).put("buffs",List.of(map("icon",BuffIndicator.FROST,"name","Frost")));
        assertEquals(List.of(cue),worldCues(GameplayObservation.merge(source)));
    }

    @Test void standaloneActionsCloseAllCurrentSubjectReferencesWithoutEngineReads(){
        Map<String,Object> item=map("locator","backpack.0","name","same","quantity",2,"shown",map("status","6/20"));
        Map<String,Object> heroBuff=map("name","hero buff","icon",1,"shown",map("counter","2"));
        Map<String,Object> mobBuff=map("name","mob buff","icon",2);
        Map<String,Object> hero=map("cell",10,"hp",5,"buffs",List.of(heroBuff));
        Map<String,Object> entity=map("kind","character","cell",11,"name","same","buffs",List.of(mobBuff));
        List<Object> controls=List.of(
                map("id","item","role","button","locator","backpack.0","label","Use this","subject",map("kind","item","loc","backpack.0")),
                map("id","hero","role","button","subject",map("kind","hero")),
                map("id","heroBuff","role","button","subject",map("kind","hero_buff","index",0)),
                map("id","entity","role","button","subject",map("kind","entity","index",0)),
                map("id","entityBuff","role","button","subject",map("kind","entity_buff","entity",0,"index",0)));
        Map<String,Object> source=map("inventory",List.of(item),"hero",hero,"visible_entities",List.of(entity),"ui",map("controls",controls));
        String before=source.toString();Map<String,Object> standalone=GameplayObservation.uiForActions(source);
        List<?> rows=(List<?>)standalone.get("controls");List<?> expected=List.of(item,hero,heroBuff,entity,mobBuff);
        for(int i=0;i<rows.size();i++){Map<String,Object> row=object(rows.get(i));assertFalse(row.containsKey("subject"));assertEquals(expected.get(i),row.get("subject_data"));}
        assertEquals("backpack.0",object(rows.get(0)).get("locator"));assertEquals("Use this",object(rows.get(0)).get("label"));
        object(object(rows.get(0)).get("subject_data")).put("quantity",99);assertEquals(before,source.toString());
    }

    @Test void standaloneActionsPreserveProtectedNamesAndUnavailableCapturedFacts(){
        Map<String,Object> origin=map("name",List.of("external"));
        Map<String,Object> item=map("locator","backpack.0","name","player supplied name","text_origins",origin,
                "text_diagnostics",map("name","fixture_partial"));
        Map<String,Object> unresolved=map("id","unknown","role","button","label","Captured choice","shown",map("counter","0"),
                "subject",map("kind","entity","index",4),"ops",List.of(map("action","ui.activate")));
        Map<String,Object> source=observation(List.of(item),List.of(
                map("id","item","subject",map("kind","item","loc","backpack.0")),unresolved),Map.of());
        List<?> rows=(List<?>)GameplayObservation.uiForActions(source).get("controls");
        assertEquals(item,object(rows.get(0)).get("subject_data"));
        Map<String,Object> unknown=object(rows.get(1));assertFalse(unknown.containsKey("subject"));assertTrue(unknown.containsKey("subject_data"));assertNull(unknown.get("subject_data"));
        assertEquals("Captured choice",unknown.get("label"));assertEquals(map("counter","0"),unknown.get("shown"));assertEquals(unresolved.get("ops"),unknown.get("ops"));
        assertEquals("partial",object(unknown.get("presentation")).get("status"));
        assertEquals("unresolved_subject",object(((List<?>)object(unknown.get("presentation")).get("diagnostics")).get(0)).get("code"));
        source.put("inventory",List.of(item,item));
        Map<?,?> ambiguous=object(((List<?>)GameplayObservation.uiForActions(source).get("controls")).get(0));
        assertNull(ambiguous.get("subject_data"));assertEquals("partial",object(ambiguous.get("presentation")).get("status"));
    }

    @Test void gesturesAreOmittedOnlyWithAuthoritativeNativeActionsAndNoProtectedField(){
        List<Object> controls=List.of(map("id","active","role","button","gestures",List.of("click","long")),
                map("id","inactive","role","button","enabled",false,"gestures",List.of("click","long")),
                map("id","protected","role","button","gestures",List.of("click"),"text_diagnostics",map("gestures","fixture")));
        Map<String,Object> merged=GameplayObservation.merge(observation(List.of(),controls,Map.of(
                "active",hint(null,List.of(),Map.of(),true),"inactive",hint(null,List.of(),Map.of(),false),"protected",hint(null,List.of(),Map.of(),true))));
        assertFalse(nodes(merged).get(0).containsKey("gestures"));assertEquals(List.of("click","long"),nodes(merged).get(1).get("gestures"));
        assertEquals(List.of("click"),nodes(merged).get(2).get("gestures"));
    }

    private static UiProjectionHints.Node hint(Map<String,Object> subject,List<String> owned,Map<String,String> fields,boolean action){return new UiProjectionHints.Node(false,owned,null,fields,subject,null,action);}
    private static Map<String,Object> observation(List<?> inventory,List<?> controls,Map<String,UiProjectionHints.Node> hints){return map("inventory",inventory,"ui",new UiProjectionHints(hints).attach(map("controls",controls)));}
    @SuppressWarnings("unchecked")private static Map<String,Object> object(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked")private static List<Map<String,Object>> nodes(Map<String,Object> observation){return (List<Map<String,Object>>)object(observation.get("ui")).get("controls");}
    private static Map<String,Object> item(Map<String,Object> observation,int index){return object(((List<?>)observation.get("inventory")).get(index));}
    private static List<Object> ids(Map<String,Object> observation){List<Object> result=new ArrayList<>();for(Map<String,Object> node:nodes(observation))result.add(node.get("id"));return result;}
    private static List<?> worldCues(Map<String,Object> observation){return (List<?>)object(observation.get("visual_cues")).get("cues");}
    private static Map<String,Object> frost(){return map("icon",BuffIndicator.FROST,"name",map("$text_source",1,"node",map("kind","resource","key","actors.buffs.frost.name","args",List.of())));}
}
