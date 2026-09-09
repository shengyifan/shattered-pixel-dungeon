package com.shatteredpixel.shatteredpixeldungeon.control.game;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/** English presentation of already-public DTOs. Opaque identities and raw audit bytes are not prose. */
public final class PublicEnglishProjection {
    private static final Set<String> TEXT_FIELDS = new HashSet<>(Arrays.asList(
            "name", "class_name", "subclass_name", "label", "text", "description", "prompt",
            "cell_prompt", "item_prompt", "title", "message", "disabled_reason"));
    private static final class DictionaryHolder {
        private static final DisplayedTextEnglish VALUE = new DisplayedTextEnglish();
    }
    private PublicEnglishProjection() { }

    public static String text(String value) { return DictionaryHolder.VALUE.translate(value); }

    @SuppressWarnings("unchecked")
    public static <T> T copy(T source) { return (T) value(source, null, new Context(null)); }

    @SuppressWarnings("unchecked")
    public static <T> T copyInScene(T source,String publicScene) { return (T) value(source,null,new Context(publicScene)); }

    @SuppressWarnings("unchecked")
    public static <T> T copyWithUi(T source,Map<String,Object> publicUi) {
        return (T)value(source,null,new Context(null).derive(publicUi));
    }

    private static Object value(Object source, String field,Context context) {
        if (source instanceof Map) {
            Map<?,?> original = (Map<?,?>) source;
            context = context.derive(original);
            Map<String,Object> translated = new LinkedHashMap<>();
            boolean partial = false;
            for (Map.Entry<?,?> entry : original.entrySet()) {
                String key = (String) entry.getKey();
                Object item = entry.getValue();
                if (key.equals("text") && item instanceof String && Boolean.TRUE.equals(original.get("clipped"))) {
                    DisplayedTextEnglish.VisibleText visible = DictionaryHolder.VALUE.translateVisibleInContext((String) item, true,context.textContext);
                    translated.put(key, visible.text); partial |= visible.partial;
                } else translated.put(key, value(item, key,context));
            }
            if (partial) translated.put("translation_status", "partial");
            return translated;
        }
        if (source instanceof List) {
            List<Object> translated = new ArrayList<>();
            for (Object item : (List<?>) source) translated.add(value(item, field,context));
            return translated;
        }
        if (source instanceof String && (TEXT_FIELDS.contains(field) || "options".equals(field)))
            return DictionaryHolder.VALUE.translateInContext((String)source,context.textContext);
        return source;
    }

    /** Context consists entirely of nodes, relations, labels and metadata already in the public DTO. */
    private static final class Context {
        final String scene;
        final Map<String,Map<?,?>> nodes;
        final Map<String,Object> textContext;
        final String inspectedControl;
        final Boolean inspectedLevelKnown;
        Context(String scene) {
            this.scene=scene;nodes=Collections.emptyMap();
            textContext=new LinkedHashMap<>();textContext.put("scene",scene);
            inspectedControl=null;inspectedLevelKnown=null;
        }
        Context(String scene,Map<String,Map<?,?>> nodes,Map<String,Object> textContext,String inspectedControl,Boolean inspectedLevelKnown) {
            this.scene=scene;this.nodes=nodes;this.textContext=textContext;
            this.inspectedControl=inspectedControl;this.inspectedLevelKnown=inspectedLevelKnown;
        }
        Context derive(Map<?,?> source) {
            Map<?,?> ui=ui(source);
            String currentScene=scene;
            Map<String,Map<?,?>> currentNodes=nodes;
            Map<String,Object> properties=new LinkedHashMap<>(textContext);
            String currentInspectedControl=inspectedControl;Boolean currentInspectedKnown=inspectedLevelKnown;
            if(ui!=null) {
                currentScene=(String)ui.get("scene");
                currentNodes=new LinkedHashMap<>();
                Set<String> buttons=new HashSet<>(),texts=new HashSet<>();
                boolean hasBindingRow=false,hasBindingInput=false,hasNoteTitleInput=false,hasNoteBodyInput=false;
                for(Object value:(List<?>)ui.get("controls")) if(value instanceof Map) {
                    Map<?,?> node=(Map<?,?>)value;
                    if(node.get("id") instanceof String)currentNodes.put((String)node.get("id"),node);
                    if(bindingSlots(node.get("binding_slots")))hasBindingRow=true;
                    if(Boolean.TRUE.equals(node.get("binding_input")))hasBindingInput=true;
                    if("text_input".equals(node.get("role"))&&node.get("max_length") instanceof Number) {
                        double limit=((Number)node.get("max_length")).doubleValue();
                        if(limit==50&&Boolean.FALSE.equals(node.get("multiline")))hasNoteTitleInput=true;
                        if(limit==500&&Boolean.TRUE.equals(node.get("multiline")))hasNoteBodyInput=true;
                    }
                    for(String key:Arrays.asList("text","label")) if(node.get(key) instanceof String) {
                        texts.add((String)node.get(key));
                        if("button".equals(node.get("role")))buttons.add((String)node.get(key));
                    }
                }
                properties.clear();properties.put("scene",currentScene);
                properties.put("modal",ui.get("modal"));properties.put("cell_input",ui.get("cell_input"));
                if(ui.get("cell_prompt") instanceof String)properties.put("cell_prompt",ui.get("cell_prompt"));
                currentInspectedControl=null;currentInspectedKnown=null;
                Object inspected=ui.get("inspected_item");
                if(Boolean.TRUE.equals(ui.get("modal"))&&inspected instanceof Map) {
                    Map<?,?> item=(Map<?,?>)inspected;
                    Map<?,?> owner=item.get("control") instanceof String?currentNodes.get(item.get("control")):null;
                    if(owner!=null&&"window".equals(owner.get("role"))&&owner.get("parent")==null&&item.get("level_known") instanceof Boolean) {
                        currentInspectedControl=(String)item.get("control");currentInspectedKnown=(Boolean)item.get("level_known");
                    }
                }
                boolean game="GameScene".equals(currentScene)||"game".equals(currentScene);
                boolean start="StartScene".equals(currentScene)||"start".equals(currentScene);
                boolean modal=Boolean.TRUE.equals(ui.get("modal"));
                Set<String> itemTexts=new HashSet<>(),itemButtons=new HashSet<>();
                if(currentInspectedControl!=null)for(Map<?,?> node:currentNodes.values())if(withinRoot(node,currentNodes,currentInspectedControl))
                    for(String key:Arrays.asList("text","label"))if(node.get(key) instanceof String) {
                        itemTexts.add((String)node.get(key));if("button".equals(node.get("role")))itemButtons.add((String)node.get(key));
                    }
                boolean originalItemMenu=game&&modal&&currentInspectedControl!=null
                        &&one(itemButtons,"DROP","放下")&&one(itemButtons,"THROW","扔出")&&one(itemButtons,"EQUIP","装备","UNEQUIP","取下");
                properties.put("cloak_item_menu",originalItemMenu&&itemTitle(itemTexts,"暗影斗篷","cloak of shadows"));
                properties.put("sneak_weapon_menu",originalItemMenu&&itemTitle(itemTexts,"匕首","长匕首","暗杀之刃","dagger","dirk","assassin's blade"));
                properties.put("combo_weapon_menu",originalItemMenu&&itemTitle(itemTexts,"魔岩拳套","镶钉手套","双钗","stone gauntlet","studded gloves","sai"));
                properties.put("shadow_clone_menu",originalItemMenu&&itemTitle(itemTexts,"英雄风衣","hero's garb")
                        &&shadowCloneDescription(itemTexts));
                properties.put("upgrade_preview",game&&modal&&one(texts,"升级一件物品","Upgrade an Item")
                        &&upgradeDescription(texts)&&one(buttons,"升级","Upgrade")&&one(buttons,"返回","Back"));
                properties.put("scroll_cancel",game&&modal&&one(texts,
                        "你真的想终止这张卷轴的施放？这张卷轴之前未被鉴定，因此它仍会被消耗掉。",
                        "Do you really want to cancel this scroll usage? The scroll wasn't previously identified, so it will be consumed anyway.")
                        &&one(buttons,"是的，我确定","Yes, I'm positive")&&one(buttons,"不，我改变主意了","No, I changed my mind"));
                properties.put("victory_congratulations",("RankingsScene".equals(currentScene)||"rankings".equals(currentScene))
                        &&modal&&one(texts,"Victory!","获胜！")&&one(buttons,"Support","赞助")&&one(buttons,"Close","关闭")
                        &&one(texts,"Congratulations on conquering the dungeon! You've unlocked some new features that are available when choosing a hero:",
                        "恭喜您征服了这座地牢！新的游戏选项已经解锁，你可以在选择英雄时查看并设置："));
                properties.put("hero_subclass_page",("HeroSelectScene".equals(currentScene)||"hero_select".equals(currentScene))
                        &&modal&&one(texts,"专精","subclasses","Subclasses")&&one(texts,
                        "击杀第二个Boss后可以选择一种职业专精。","A subclass can be chosen after defeating the second boss."));
                properties.put("save_details",start&&modal&&one(buttons,"Continue","继续")&&one(buttons,"Erase","删除")
                        &&one(texts,"Strength","力量")&&one(texts,"Health","生命")
                        &&one(texts,"Gold Collected","金币收集数")&&one(texts,"Maximum Depth","最高层数"));
                properties.put("game_menu",game&&modal&&one(buttons,"Settings","设置")&&one(buttons,"Main Menu","主菜单"));
                properties.put("chasm_prompt",game&&modal&&one(texts,
                        "Do you really want to jump into the chasm? A fall that far will be painful.",
                        "你确定要跳入深渊中？从这么高的地方摔下去一定很疼。"));
                properties.put("key_binding_panel",hasBindingRow&&one(texts,"Action","行动")
                        &&one(texts,"Key 1","按键1")&&one(texts,"Key 2","按键2")&&one(texts,"Key 3","按键3")
                        &&one(buttons,"Default Bindings","恢复默认键位"));
                properties.put("key_binding_input",hasBindingInput);
                boolean noteButtons=one(buttons,"Confirm","确定")&&one(buttons,"Cancel","取消");
                boolean noteTitle=one(texts,"New Text Note","新建文本备注","New Dungeon Floor Note","新建地牢楼层备注",
                        "New Inventory Item Note","新建背包物品备注","New Item Type Note","新建物品类别备注","Edit Title","编辑标题");
                boolean noteBody=one(texts,"Add Text","添加文本","Edit Text","编辑文本");
                properties.put("custom_note_input",game&&modal&&noteButtons&&(hasNoteTitleInput&&noteTitle||hasNoteBodyInput&&noteBody));
                properties.put("custom_note_view",game&&modal&&one(buttons,"Edit Title","编辑标题")
                        &&one(buttons,"Add Text","添加文本","Edit Text","编辑文本")&&one(buttons,"Delete","删除"));
                properties.put("custom_note_delete",game&&modal&&noteButtons&&one(texts,
                        "Are you sure you want to delete this custom note?","你确定要删除这个备注吗？"));
            } else if(source.get("scene") instanceof String) {
                currentScene=(String)source.get("scene");properties.put("scene",currentScene);
                if(!java.util.Objects.equals(currentScene,scene)){
                    currentInspectedControl=null;currentInspectedKnown=null;properties.remove("inspected_item_level_known");
                    properties.remove("cell_prompt");properties.remove("cell_input");properties.remove("modal");
                }
            }
            Map<?,?> node=source.get("role") instanceof String?source:
                    source.get("control") instanceof String?currentNodes.get(source.get("control")):null;
            if(node!=null) {
                properties.put("role",node.get("role"));
                properties.remove("shortcut_action");properties.put("checkbox",false);properties.put("slider",false);properties.put("key_binding",false);properties.put("button",false);
                properties.remove("inspected_item_level_known");
                properties.put("ranking_record",false);
                Set<Object> visited=new HashSet<>();
                for(Map<?,?> ancestor=node;ancestor!=null;) {
                    if(currentInspectedKnown!=null&&currentInspectedControl.equals(ancestor.get("id")))
                        properties.put("inspected_item_level_known",currentInspectedKnown);
                    if(ancestor.containsKey("checked"))properties.put("checkbox",true);
                    if("slider".equals(ancestor.get("role")))properties.put("slider",true);
                    if("button".equals(ancestor.get("role")))properties.put("button",true);
                    if(("RankingsScene".equals(currentScene)||"rankings".equals(currentScene))
                            &&Boolean.FALSE.equals(properties.get("modal"))&&"button".equals(ancestor.get("role"))
                            &&ancestor.get("text") instanceof String&&((String)ancestor.get("text")).matches(
                            "(?:[1-9][0-9]*| )\\n(?:获得Yendor护符|Obtained the Amulet of Yendor)\\n[0-9]+"))
                        properties.put("ranking_record",true);
                    if(bindingSlots(ancestor.get("binding_slots")))properties.put("key_binding",true);
                    if(!properties.containsKey("shortcut_action")&&ancestor.get("shortcut_action") instanceof String)
                        properties.put("shortcut_action",ancestor.get("shortcut_action"));
                    Object parent=ancestor.get("parent");
                    if(parent==null||!visited.add(parent))break;
                    ancestor=currentNodes.get(parent);
                }
            }
            return new Context(currentScene,currentNodes,properties,currentInspectedControl,currentInspectedKnown);
        }
        private static boolean one(Set<String> values,String... alternatives) {
            for(String value:alternatives)if(values.contains(value))return true;
            return false;
        }
        private static boolean itemTitle(Set<String> values,String... names) {
            for(String value:values)for(String name:names)
                if(value.matches("(?i)"+java.util.regex.Pattern.quote(name)+"(?: [+-][0-9]+)?(?: x[1-9][0-9]*)?"))return true;
            return false;
        }
        private static boolean withinRoot(Map<?,?> node,Map<String,Map<?,?>> nodes,String root) {
            Set<Object> visited=new HashSet<>();
            for(Map<?,?> current=node;current!=null;) {
                if(root.equals(current.get("id")))return true;
                Object parent=current.get("parent");if(parent==null||!visited.add(parent))return false;
                current=nodes.get(parent);
            }
            return false;
        }
        private static boolean upgradeDescription(Set<String> values) {
            for(String text:values)if(text.matches(java.util.regex.Pattern.quote("升级这件物品会永久提升其如下属性：")+"(?:\\n你还剩有_[0-9]+个_升级用物品。)?")
                    ||text.matches(java.util.regex.Pattern.quote("Upgrading an item permanently improves it:")+"(?:\\nYou have _[0-9]+_ upgrade items left\\.)?"))return true;
            return false;
        }
        private static boolean shadowCloneDescription(Set<String> values) {
            boolean armor=false,ability=false;
            for(String text:values)for(String paragraph:text.split("\\n\\n",-1)) {
                if(paragraph.equals("裹着这身与黑暗融为一体的斗篷时，盗贼能够施展一项特殊技能。")
                        ||paragraph.equals("While wearing this dark garb, the Rogue can perform a special ability."))armor=true;
                if(paragraph.matches(java.util.regex.Pattern.quote("盗贼召唤一个_暗影映像_，并能使唤其帮助自己战斗。")
                        +" 现在使用该能力将消耗_[0-9]+(?:\\.[0-9]+)?_的充能。")
                        ||paragraph.matches(java.util.regex.Pattern.quote("The Rogue summons a _Shadow Clone_, which can be directed to aid him in combat.")
                        +" Using the ability right now will consume _[0-9]+(?:\\.[0-9]+)?_ charge\\."))ability=true;
            }
            return armor&&ability;
        }
        private static boolean bindingSlots(Object value) {
            if(!(value instanceof List)||((List<?>)value).size()!=3)return false;
            for(int i=0;i<3;i++) {
                Object slot=((List<?>)value).get(i);
                if(!(slot instanceof Number)||((Number)slot).doubleValue()!=i+1)return false;
            }
            return true;
        }
        private static Map<?,?> ui(Map<?,?> source) {
            Object observation=source.get("observation");
            if(observation instanceof Map)return ui((Map<?,?>)observation);
            Object nested=source.get("ui");
            if(nested instanceof Map)return ui((Map<?,?>)nested);
            return source.get("scene") instanceof String&&source.get("controls") instanceof List?source:null;
        }
    }
}
