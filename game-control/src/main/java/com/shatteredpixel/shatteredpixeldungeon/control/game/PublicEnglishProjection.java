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
        Context(String scene) {
            this.scene=scene;nodes=Collections.emptyMap();
            textContext=new LinkedHashMap<>();textContext.put("scene",scene);
        }
        Context(String scene,Map<String,Map<?,?>> nodes,Map<String,Object> textContext) {
            this.scene=scene;this.nodes=nodes;this.textContext=textContext;
        }
        Context derive(Map<?,?> source) {
            Map<?,?> ui=ui(source);
            String currentScene=scene;
            Map<String,Map<?,?>> currentNodes=nodes;
            Map<String,Object> properties=new LinkedHashMap<>(textContext);
            if(ui!=null) {
                currentScene=(String)ui.get("scene");
                currentNodes=new LinkedHashMap<>();
                Set<String> buttons=new HashSet<>(),texts=new HashSet<>();
                boolean hasBindingRow=false;
                for(Object value:(List<?>)ui.get("controls")) if(value instanceof Map) {
                    Map<?,?> node=(Map<?,?>)value;
                    if(node.get("id") instanceof String)currentNodes.put((String)node.get("id"),node);
                    if(bindingSlots(node.get("binding_slots")))hasBindingRow=true;
                    for(String key:Arrays.asList("text","label")) if(node.get(key) instanceof String) {
                        texts.add((String)node.get(key));
                        if("button".equals(node.get("role")))buttons.add((String)node.get(key));
                    }
                }
                properties.clear();properties.put("scene",currentScene);
                boolean game="GameScene".equals(currentScene)||"game".equals(currentScene);
                boolean start="StartScene".equals(currentScene)||"start".equals(currentScene);
                boolean modal=Boolean.TRUE.equals(ui.get("modal"));
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
            } else if(source.get("scene") instanceof String) {
                currentScene=(String)source.get("scene");properties.put("scene",currentScene);
            }
            Map<?,?> node=source.get("role") instanceof String?source:
                    source.get("control") instanceof String?currentNodes.get(source.get("control")):null;
            if(node!=null) {
                properties.put("role",node.get("role"));
                properties.remove("shortcut_action");properties.put("checkbox",false);properties.put("slider",false);properties.put("key_binding",false);
                Set<Object> visited=new HashSet<>();
                for(Map<?,?> ancestor=node;ancestor!=null;) {
                    if(ancestor.containsKey("checked"))properties.put("checkbox",true);
                    if("slider".equals(ancestor.get("role")))properties.put("slider",true);
                    if(bindingSlots(ancestor.get("binding_slots")))properties.put("key_binding",true);
                    if(!properties.containsKey("shortcut_action")&&ancestor.get("shortcut_action") instanceof String)
                        properties.put("shortcut_action",ancestor.get("shortcut_action"));
                    Object parent=ancestor.get("parent");
                    if(parent==null||!visited.add(parent))break;
                    ancestor=currentNodes.get(parent);
                }
            }
            return new Context(currentScene,currentNodes,properties);
        }
        private static boolean one(Set<String> values,String... alternatives) {
            for(String value:alternatives)if(values.contains(value))return true;
            return false;
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
