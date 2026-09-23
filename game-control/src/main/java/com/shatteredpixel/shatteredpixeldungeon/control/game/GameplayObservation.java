package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.game.text.PublicTextSources;
import com.shatteredpixel.shatteredpixeldungeon.control.game.text.TextProvenance;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import java.util.*;

/** Pure same-capture semantic merge. Native identities exist only in UiBridge's capture-local hints. */
public final class GameplayObservation {
    private GameplayObservation() { }

    /** Standalone actions have no world tables: replace each current UI subject by its captured facts. */
    public static Map<String,Object> uiForActions(Map<String,Object> observation){
        if(!(observation.get("ui") instanceof Map))return new LinkedHashMap<>();
        Map<String,Object> ui=copyMap(map(observation.get("ui")));
        if(!(ui.get("controls") instanceof List))return ui;
        for(Object raw:(List<?>)ui.get("controls")){
            if(!(raw instanceof Map))continue;
            Map<String,Object> node=map(raw);if(!node.containsKey("subject"))continue;
            Map<String,Object> target=resolve(observation,optionalMap(node.get("subject")));
            node.remove("subject");
            if(target!=null){node.put("subject_data",copyMap(target));continue;}
            if(!node.containsKey("subject_data"))node.put("subject_data",null);
            Map<String,Object> presentation=node.get("presentation") instanceof Map?copyMap(map(node.get("presentation"))):new LinkedHashMap<>();
            List<Object> diagnostics=presentation.get("diagnostics") instanceof List?new ArrayList<>((List<?>)presentation.get("diagnostics")):new ArrayList<>();
            diagnostics.add(mapOf("field","subject_data","code","unresolved_subject"));
            presentation.put("status","partial");presentation.put("diagnostics",diagnostics);node.put("presentation",presentation);
        }
        return ui;
    }

    /** Return an independent canonical observation; never mutate a frozen UI, snapshot or source tree. */
    public static Map<String,Object> merge(Map<String,Object> observation) {
        Map<String,Object> result=copyMap(observation);
        mergeWorldFacts(result);
        if(!(result.get("ui") instanceof Map))return result;
        Map<String,Object> ui=map(result.get("ui"));
        if(!(ui.get("controls") instanceof List))return result;
        if(indexedControlMetadata(observation)||indexedControlMetadata(observation.get("ui")))return result;
        UiProjectionHints hints=UiProjectionHints.get(observation.get("ui"));
        List<Map<String,Object>> nodes=new ArrayList<>();
        Map<Object,Map<String,Object>> byId=new LinkedHashMap<>();
        for(Object raw:(List<?>)ui.get("controls"))if(raw instanceof Map){
            Map<String,Object> node=map(raw);nodes.add(node);byId.put(node.get("id"),node);
        }
        Set<Object> removed=new HashSet<>();
        List<Object> feedback=new ArrayList<>();
        if(ui.get("feedback") instanceof List)feedback.addAll((List<?>)ui.get("feedback"));
        for(Map<String,Object> node:nodes){
            UiProjectionHints.Node hint=hints.nodes.get(node.get("id"));
            if(hint==null)continue;
            // The native action list is authoritative. An inactive/icon-only node has no such guarantee.
            if(hint.actionable&&!protectedField(node,"gestures"))node.remove("gestures");
            Map<String,Object> target=resolve(result,hint.subject);
            if(target!=null){
                node.put("subject",copyMap(hint.subject));
                Map<String,Object> shown=node.get("shown") instanceof Map?copyMap(map(node.get("shown"))):new LinkedHashMap<>();
                List<Object> movedChildren=new ArrayList<>();
                for(Map.Entry<String,String> entry:hint.displayChildren.entrySet()){
                    Map<String,Object> child=byId.get(entry.getValue());
                    if(child==null||!plainText(child)||!child.containsKey("text"))continue;
                    Object existing=shown.get(entry.getKey());
                    if(existing!=null&&!sameDisplayText(existing,child.get("text")))continue;
                    shown.put(entry.getKey(),copy(child.get("text")));
                    moveTextMetadata(child,shown,entry.getKey());
                    movedChildren.add(child.get("id"));
                }
                if("item".equals(hint.subject.get("kind")))minimizeItemShown(target,shown);
                if((!shown.isEmpty()&&mergeFields(target,"shown",shown))||(shown.isEmpty()&&(node.get("shown") instanceof Map||!movedChildren.isEmpty()))){
                    node.remove("shown");removed.addAll(movedChildren);
                    if(!hint.displayChildren.isEmpty()&&movedChildren.containsAll(hint.displayChildren.values())&&!protectedContent(node))removeText(node);
                }
                if(node.get("health_estimate") instanceof Map){
                    if(mergeHealth(target,map(node.get("health_estimate")))){
                        node.remove("health_estimate");
                        if("health_bar".equals(node.get("role"))&&!hint.actionable&&!protectedContent(node))removed.add(node.get("id"));
                    }
                }
                if(node.get("turn_progress") instanceof Map&&mergeFields(target,"turn_progress",map(node.get("turn_progress"))))
                    node.remove("turn_progress");
                if("hero".equals(hint.subject.get("kind")))removeHeroText(target,node,hint,byId,removed);
                // A subject supplies its name. A distinct button title remains independently useful.
                Object name=target.get("name");
                if(name!=null&&node.get("label")!=null&&!protectedContent(node)
                        &&(sameText(name,node.get("label"))||titleCaseEqual(name,node.get("label"))))node.remove("label");
            }
            if(hint.feedbackKind!=null&&!hint.actionable){
                Map<String,Object> message=copyMap(node);
                if(!protectedContent(message)){
                    message.remove("role");message.remove("parent");message.remove("enabled");message.remove("gestures");
                    if("floating_text".equals(message.get("presentation")))message.remove("presentation");
                }
                message.put("kind",hint.feedbackKind);
                if(message.containsKey("text")||message.containsKey("symbol")||message.containsKey("banner_kind")
                        ||message.get("icon") instanceof Map&&!((Map<?,?>)message.get("icon")).isEmpty()||protectedContent(message))feedback.add(message);
                removed.add(node.get("id"));
            }
            if(hint.emptyPlaceholder&&!hint.actionable&&!protectedContent(node)){
                boolean useful=false;
                for(Map<String,Object> child:nodes)if(Objects.equals(node.get("id"),child.get("parent"))&&!emptyText(child))useful=true;
                if(!useful)removed.add(node.get("id"));
            }
            // Group text is exactly the concatenation of direct text children; retain one owned copy.
            if(!hint.ownedTextChildren.isEmpty()&&!protectedContent(node)&&node.containsKey("text")){
                List<String> parts=new ArrayList<>();boolean complete=true;
                for(String childId:hint.ownedTextChildren){
                    Map<String,Object> child=byId.get(childId);
                    if(child==null||!child.containsKey("text")||protectedContent(child)){complete=false;break;}
                    parts.add(rendered(child.get("text")));
                }
                if(complete&&Objects.equals(rendered(node.get("text")),String.join("\n",parts))
                        &&sameOwnedSources(node.get("text"),hint.ownedTextChildren,byId))removeText(node);
            }
        }
        // Empty text leaves have no public meaning. Preserve diagnostic/provenance exceptions.
        for(Map<String,Object> node:nodes)if(emptyText(node)&&!hasChildren(node,nodes))removed.add(node.get("id"));
        // Parents required by retained controls keep their identity even when their own display is redundant.
        Set<Object> needed=new HashSet<>();
        for(Map<String,Object> node:nodes)if(!removed.contains(node.get("id"))){
            Object parent=node.get("parent");Set<Object> visited=new HashSet<>();
            while(parent!=null&&visited.add(parent)){
                needed.add(parent);Map<String,Object> ancestor=byId.get(parent);parent=ancestor==null?null:ancestor.get("parent");
            }
        }
        removed.removeAll(needed);
        List<Object> retained=new ArrayList<>();
        for(Map<String,Object> node:nodes)if(!removed.contains(node.get("id")))retained.add(node);
        ui.put("controls",retained);
        if(!feedback.isEmpty())ui.put("feedback",feedback);
        return result;
    }

    /** Frost.fx supplies icy/paused; its captured resource identity disambiguates Chill's shared icon. */
    private static void mergeWorldFacts(Map<String,Object> observation){
        Map<String,Object> visuals=optionalMap(observation.get("visual_cues"));
        if(visuals==null||!(visuals.get("cues") instanceof List)||protectedContent(visuals))return;
        Map<String,Object> hero=optionalMap(observation.get("hero"));
        if(hero!=null&&visuals.containsKey("depth")&&hero.containsKey("depth")&&!Objects.equals(visuals.get("depth"),hero.get("depth")))return;
        List<Object> retained=new ArrayList<>();
        for(Object raw:(List<?>)visuals.get("cues")){
            if(!(raw instanceof Map)){retained.add(raw);continue;}
            Map<String,Object> cue=map(raw),appearance=optionalMap(cue.get("appearance"));
            Map<String,Object> owner=publicCharacterAt(observation,cue.get("cell"));
            boolean remove=false;
            if("sprite_state".equals(cue.get("kind"))&&appearance!=null&&owner!=null
                    &&!protectedContent(cue)&&!partialIndicator(cue)&&publicBuffSource(owner,BuffIndicator.FROST,"actors.buffs.frost.name")
                    &&"icy".equals(appearance.get("style"))){
                appearance.remove("style");if(Boolean.TRUE.equals(appearance.get("paused")))appearance.remove("paused");
                if(appearance.isEmpty())cue.remove("appearance");
                remove=cue.keySet().stream().allMatch(key->"kind".equals(key)||"cell".equals(key));
            }
            if(!remove)retained.add(cue);
        }
        visuals.put("cues",retained);
    }

    private static Map<String,Object> publicCharacterAt(Map<String,Object> observation,Object cell){
        if(!(cell instanceof Number))return null;
        Map<String,Object> found=null,hero=optionalMap(observation.get("hero"));
        if(hero!=null&&Objects.equals(hero.get("cell"),cell))found=hero;
        if(observation.get("visible_entities") instanceof List)for(Object raw:(List<?>)observation.get("visible_entities")){
            Map<String,Object> entity=optionalMap(raw);
            if(entity!=null&&"character".equals(entity.get("kind"))&&Objects.equals(entity.get("cell"),cell)){
                if(found!=null)return null;found=entity;
            }
        }
        return found;
    }

    private static boolean publicBuffSource(Map<String,Object> owner,int icon,String resource){
        if(owner.get("buffs") instanceof List)for(Object raw:(List<?>)owner.get("buffs")){
            Map<String,Object> buff=optionalMap(raw);if(buff==null||!(buff.get("icon") instanceof Number)
                    ||((Number)buff.get("icon")).intValue()!=icon||protectedContent(buff))continue;
            Object source=buff.get("text_sources") instanceof Map?((Map<?,?>)buff.get("text_sources")).get("name"):textSource(buff.get("name"));
            if(source instanceof Map&&"resource".equals(((Map<?,?>)source).get("kind"))&&resource.equals(((Map<?,?>)source).get("key")))return true;
        }
        return false;
    }

    private static boolean partialIndicator(Object value){
        if(value instanceof Map){Map<?,?> fields=(Map<?,?>)value;
            if(Boolean.TRUE.equals(fields.get("partial"))||fields.containsKey("unmapped_indicator"))return true;
            for(Object child:fields.values())if(partialIndicator(child))return true;
        }else if(value instanceof List)for(Object child:(List<?>)value)if(partialIndicator(child))return true;
        return false;
    }

    private static Map<String,Object> resolve(Map<String,Object> observation,Map<String,Object> subject){
        if(subject==null)return null;
        Object kind=subject.get("kind");Map<String,Object> hero=optionalMap(observation.get("hero"));
        if("hero".equals(kind))return subject.keySet().equals(Set.of("kind"))?hero:null;
        if("hero_buff".equals(kind))return hero==null||!subject.keySet().equals(Set.of("kind","index"))?null:entry(hero.get("buffs"),subject.get("index"));
        if("entity".equals(kind))return subject.keySet().equals(Set.of("kind","index"))?entry(observation.get("visible_entities"),subject.get("index")):null;
        if("entity_buff".equals(kind)){
            if(!subject.keySet().equals(Set.of("kind","entity","index")))return null;
            Map<String,Object> entity=entry(observation.get("visible_entities"),subject.get("entity"));
            return entity==null?null:entry(entity.get("buffs"),subject.get("index"));
        }
        if("item".equals(kind)){
            if(!subject.keySet().equals(Set.of("kind","loc"))||!(subject.get("loc") instanceof String)||((String)subject.get("loc")).isEmpty())return null;
            Map<String,Object> found=null;
            List<Object> candidates=new ArrayList<>();
            if(observation.get("inventory") instanceof List)candidates.addAll((List<?>)observation.get("inventory"));
            if(observation.get("visible_entities") instanceof List)for(Object raw:(List<?>)observation.get("visible_entities"))
                if(raw instanceof Map&&((Map<?,?>)raw).get("item") instanceof Map)candidates.add(((Map<?,?>)raw).get("item"));
            for(Object raw:candidates)if(raw instanceof Map&&Objects.equals(((Map<?,?>)raw).get("locator"),subject.get("loc"))){
                if(found!=null)return null;found=map(raw);
            }
            return found;
        }
        return null;
    }

    private static Map<String,Object> entry(Object values,Object index){
        if(!(values instanceof List)||!(index instanceof Integer)||((Integer)index)<0||((Integer)index)>=((List<?>)values).size())return null;
        return optionalMap(((List<?>)values).get((Integer)index));
    }

    private static boolean mergeFields(Map<String,Object> target,String key,Map<String,Object> fields){
        if(!target.containsKey(key)){target.put(key,copyMap(fields));return true;}
        if(!(target.get(key) instanceof Map))return false;
        Map<String,Object> existing=map(target.get(key));
        for(Map.Entry<String,Object> value:fields.entrySet())if(existing.containsKey(value.getKey())
                &&!sameText(existing.get(value.getKey()),value.getValue()))return false;
        for(Map.Entry<String,Object> value:fields.entrySet())existing.putIfAbsent(value.getKey(),copy(value.getValue()));
        return true;
    }

    /** Omit only display strings exactly reconstructed from this same item's public semantic values. */
    private static void minimizeItemShown(Map<String,Object> item,Map<String,Object> shown){
        if(protectedContent(shown))return;
        if("quantity".equals(shown.get("status_kind"))&&item.get("quantity") instanceof Number
                &&Objects.equals(String.valueOf(item.get("quantity")),rendered(shown.get("status")))){
            removeField(shown,"status");removeField(shown,"status_kind");
        }
        if(Boolean.TRUE.equals(item.get("level_known"))&&item.get("level") instanceof Number){
            int value=((Number)item.get("level")).intValue();String text=(value>=0?"+":"")+value;
            if(Objects.equals(text,rendered(shown.get("level"))))removeField(shown,"level");
        }
        if(shown.get("strength") instanceof Map){
            Map<String,Object> strength=map(shown.get("strength"));
            if(strength.get("value") instanceof Number&&strength.get("estimated") instanceof Boolean){
                String value=String.valueOf(strength.get("value"));
                String text=Boolean.TRUE.equals(strength.get("estimated"))?value+"?":":"+value;
                if(Objects.equals(text,rendered(shown.get("extra"))))removeField(shown,"extra");
            }
        }
    }

    private static boolean mergeHealth(Map<String,Object> target,Map<String,Object> value){
        if(!(value.get("samples") instanceof List))return false;
        if(target.containsKey("health_estimate")&&!(target.get("health_estimate") instanceof Map))return false;
        Map<String,Object> health=target.get("health_estimate") instanceof Map?map(target.get("health_estimate")):new LinkedHashMap<>();
        for(Map.Entry<String,Object> entry:value.entrySet())if(!"samples".equals(entry.getKey())&&health.containsKey(entry.getKey())
                &&!Objects.equals(health.get(entry.getKey()),entry.getValue()))return false;
        List<Object> samples=health.get("samples") instanceof List?new ArrayList<>((List<?>)health.get("samples")):new ArrayList<>();
        for(Object sample:(List<?>)value.get("samples"))if(!samples.contains(sample))samples.add(copy(sample));
        for(Map.Entry<String,Object> entry:value.entrySet())if(!"samples".equals(entry.getKey()))health.putIfAbsent(entry.getKey(),copy(entry.getValue()));
        health.put("samples",samples);target.put("health_estimate",health);
        return true;
    }

    private static void removeHeroText(Map<String,Object> hero,Map<String,Object> node,UiProjectionHints.Node hint,
                                       Map<Object,Map<String,Object>> byId,Set<Object> removed){
        Set<String> values=new HashSet<>(Arrays.asList(hero.get("hp")+"/"+hero.get("max_hp"),
                hero.get("experience")+"/"+hero.get("max_experience"),"lv. "+hero.get("level")));
        boolean all=true;
        for(String id:hint.ownedTextChildren){
            Map<String,Object> child=byId.get(id);
            if(child!=null&&plainText(child)&&values.contains(rendered(child.get("text"))))removed.add(id);else all=false;
        }
        if(all&&!hint.ownedTextChildren.isEmpty()&&!protectedContent(node))removeText(node);
    }

    private static boolean emptyText(Map<String,Object> node){
        return "text".equals(node.get("role"))&&!node.containsKey("text")&&!node.containsKey("ops")&&!protectedContent(node)
                &&node.keySet().stream().allMatch(key->Arrays.asList("id","role","parent","enabled","dimmed").contains(key));
    }
    private static boolean plainText(Map<String,Object> node){
        return "text".equals(node.get("role"))&&!protectedContent(node)&&!Boolean.TRUE.equals(node.get("dimmed"))
                &&node.keySet().stream().allMatch(key->Arrays.asList("id","role","parent","text","enabled","dimmed","text_sources","text_origins").contains(key));
    }
    private static boolean hasChildren(Map<String,Object> node,List<Map<String,Object>> nodes){
        for(Map<String,Object> other:nodes)if(Objects.equals(node.get("id"),other.get("parent")))return true;return false;
    }
    private static boolean protectedContent(Object value){
        if(value instanceof Map){
            Map<?,?> node=(Map<?,?>)value;
            if(TextProvenance.isToken(node))return PublicTextSources.protectsField(node.get("node"));
            if(node.containsKey("pres")||node.get("presentation") instanceof Map||node.containsKey("text_diagnostics"))return true;
            if(node.get("text_sources") instanceof Map&&PublicTextSources.protectsField(node.get("text_sources")))return true;
            if(node.get("text_origins") instanceof Map&&!((Map<?,?>)node.get("text_origins")).isEmpty())return true;
            if(Boolean.TRUE.equals(node.get("clipped")))return true;
            for(Object child:node.values())if(protectedContent(child))return true;
        }else if(value instanceof List)for(Object child:(List<?>)value)if(protectedContent(child))return true;
        return false;
    }
    private static boolean protectedField(Map<String,Object> node,String field){
        for(String key:Arrays.asList("text_sources","text_origins","text_diagnostics"))if(node.get(key) instanceof Map)
            for(Object path:((Map<?,?>)node.get(key)).keySet())if(fieldPath(path,field))return true;
        for(String key:Arrays.asList("presentation","pres"))if(node.get(key) instanceof Map){
            Map<?,?> metadata=(Map<?,?>)node.get(key);
            Object rows=metadata.containsKey("diagnostics")?metadata.get("diagnostics"):metadata.get("diag");
            if(rows instanceof List)for(Object raw:(List<?>)rows)if(raw instanceof Map&&fieldPath(((Map<?,?>)raw).get("field"),field))return true;
        }
        return false;
    }
    private static boolean fieldPath(Object path,String field){
        if(!(path instanceof String))return false;String value=(String)path;if(value.startsWith("$."))value=value.substring(2);
        return value.equals(field)||value.startsWith(field+".")||value.startsWith(field+"[");
    }
    private static boolean indexedControlMetadata(Object value){
        if(!(value instanceof Map))return false;
        for(String key:Arrays.asList("presentation","pres","text_sources","text_origins","text_diagnostics"))
            if(controlPath(((Map<?,?>)value).get(key)))return true;
        return false;
    }
    private static boolean controlPath(Object value){
        if(value instanceof String){String text=(String)value;return text.contains("controls[")||text.contains("nodes[")||text.equals("controls")||text.equals("ui");}
        if(value instanceof Map)for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet())if(controlPath(entry.getKey())||controlPath(entry.getValue()))return true;
        if(value instanceof List)for(Object child:(List<?>)value)if(controlPath(child))return true;
        return false;
    }
    private static void moveTextMetadata(Map<String,Object> source,Map<String,Object> target,String field){
        for(String key:Arrays.asList("text_sources","text_origins"))if(source.get(key) instanceof Map){
            Map<String,Object> metadata=map(source.get(key));if(!metadata.containsKey("text"))continue;
            Map<String,Object> dest=target.get(key) instanceof Map?map(target.get(key)):new LinkedHashMap<>();
            dest.put(field,copy(metadata.get("text")));target.put(key,dest);
        }
    }
    private static void removeText(Map<String,Object> node){
        removeField(node,"text");
    }
    private static void removeField(Map<String,Object> node,String field){
        node.remove(field);
        for(String key:Arrays.asList("text_sources","text_origins"))if(node.get(key) instanceof Map){
            Map<String,Object> metadata=map(node.get(key));metadata.remove(field);if(metadata.isEmpty())node.remove(key);
        }
    }
    private static boolean sameText(Object first,Object second){
        if(isText(first)&&isText(second))return Objects.equals(textSource(first),textSource(second));
        return Objects.equals(first,second);
    }
    private static boolean titleCaseEqual(Object name,Object label){
        String text=rendered(name);Object source=textSource(label);
        if(text==null||!Objects.equals(CompactStructures.titleCase(text),rendered(label))||!(source instanceof Map))return false;
        Map<?,?> labeled=(Map<?,?>)source;
        return "case".equals(labeled.get("kind"))&&"title_case".equals(labeled.get("operation"))
                &&Objects.equals(textSource(name),labeled.get("value"));
    }
    private static boolean sameDisplayText(Object first,Object second){
        return sameText(first,second)||isText(first)&&isText(second)&&Objects.equals(rendered(first),rendered(second));
    }
    private static boolean isText(Object value){return value instanceof String||value instanceof Map&&TextProvenance.isToken((Map<?,?>)value);}
    private static Object textSource(Object value){
        if(value instanceof Map&&TextProvenance.isToken((Map<?,?>)value))return ((Map<?,?>)value).get("node");
        if(value instanceof String)return TextProvenance.INSTANCE.capture(null,(String)value,false).get("node");
        return null;
    }
    private static boolean sameOwnedSources(Object parent,List<String> children,Map<Object,Map<String,Object>> nodes){
        List<Object> expected=new ArrayList<>();boolean first=true;
        for(String id:children){if(!first)expected.add("\n");first=false;sourceParts(textSource(nodes.get(id).get("text")),expected);}
        List<Object> actual=new ArrayList<>();sourceParts(textSource(parent),actual);return actual.equals(expected);
    }
    private static void sourceParts(Object source,List<Object> result){
        if(source instanceof Map){Map<?,?> fields=(Map<?,?>)source;
            if("concat".equals(fields.get("kind"))&&fields.get("parts") instanceof List){for(Object part:(List<?>)fields.get("parts"))sourceParts(part,result);return;}
            if(("literal".equals(fields.get("kind"))||"scalar".equals(fields.get("kind")))&&!PublicTextSources.protectsField(source)){
                if("".equals(fields.get("value")))return;
                if("\n".equals(fields.get("value"))){result.add("\n");return;}
            }
        }
        result.add(source);
    }
    private static String rendered(Object value){
        if(value==null)return null;
        Map<String,Object> wrapper=new LinkedHashMap<>();wrapper.put("text",value);
        Object rendered=PublicEnglishProjection.copy(wrapper).get("text");return rendered instanceof String?(String)rendered:null;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    private static Map<String,Object> optionalMap(Object value){return value instanceof Map?map(value):null;}
    private static Map<String,Object> copyMap(Map<String,Object> value){return map(copy(value));}
    private static Object copy(Object value){
        if(value instanceof Map){Map<String,Object> result=new LinkedHashMap<>();
            for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet())result.put((String)entry.getKey(),copy(entry.getValue()));
            return UiProjectionHints.preserve(value,result);}
        if(value instanceof List){List<Object> result=new ArrayList<>();for(Object child:(List<?>)value)result.add(copy(child));return result;}
        return value;
    }
    private static Map<String,Object> mapOf(Object... fields){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<fields.length;i+=2)result.put((String)fields[i],fields[i+1]);return result;}
}
