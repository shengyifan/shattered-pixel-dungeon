package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Translates only supplied displayed text. It never calls the game, a window, or a model getter. */
public final class DisplayedTextEnglish {
    public static final String UNAVAILABLE_CODE = "PUBLIC_TEXT_UNAVAILABLE";
    private static final int MAX_TEXT = 131072, MAX_DEPTH = 16, MAX_WORK = 8192;
    // Space flags are intentionally excluded: ordinary English prose such as "25% more"
    // is not a printf placeholder. These conversions cover the paired bundled resources.
    private static final Pattern FORMAT = Pattern.compile("%(?:(\\d+)\\$)?([-#+0,(<]*)(\\d+)?(?:\\.(\\d+))?([sdfxXeEgGbBhH%])");
    private static final Map<String,String> PUBLIC_SCENE_SCOPES=publicSceneScopes();
    private static final Pattern CATALOG_HEADING=Pattern.compile("\\A_([^_\\r\\n]+)_ \\(([0-9]+)/([0-9]+)\\)(:?)\\z");
    private static final Pattern ABILITY_ROW=Pattern.compile("\\A_([^_\\r\\n]+) (\\([0-9]+(?:连击|内力)\\)):_ ([\\s\\S]+)\\z");
    private static final Pattern QUOTED_SPEECH=Pattern.compile("\\A([^\\r\\n\\\":]+): \\\"([^\\\"]*)\\\"( *)\\z",Pattern.DOTALL);
    // Reviewed display vocabulary preserves the distinction available in the Chinese
    // text itself. It must not infer whether this word describes an ability or a buff.
    private static final Map<String,String> CONSERVATIVE_WORDS=Collections.singletonMap("凝神","Focus");
    private final Dictionary dictionary;

    /** The immutable asset index is built once per classloader, outside subsequent translations. */
    public DisplayedTextEnglish() { this(DefaultDictionary.VALUE); }
    private DisplayedTextEnglish(Dictionary dictionary) { this.dictionary = dictionary; }
    public static DisplayedTextEnglish fromResources(Map<String,String> chinese, Map<String,String> english) {
        return new DisplayedTextEnglish(new Dictionary(chinese, english));
    }
    public static DisplayedTextEnglish fromClassLoader(ClassLoader loader) { return new DisplayedTextEnglish(load(loader)); }

    public String translate(String displayed) {
        return translateDisplayed(displayed,null);
    }

    private String translateDisplayed(String displayed,Boolean inspectedLevelKnown) {
        if (displayed == null) return null;
        String word=CONSERVATIVE_WORDS.get(displayed);if(word!=null)return word;
        String language=dictionary.languageNames.get(displayed.toLowerCase(Locale.ROOT));
        if(language!=null)return language;
        if (!containsNonLatinText(displayed)) return displayed;
        if (displayed.length() > MAX_TEXT) throw unavailable(displayed, "displayed_text_too_long");
        String row=abilityRow(displayed);if(row!=null)return row;
        if(dictionary.exact.containsKey(displayed)) {
            String exact=exactTranslation(displayed,inspectedLevelKnown);
            if(exact==null)throw unavailable(displayed,"ambiguous_resource_translation");
            return exact;
        }
        try {
            String translated = translate(displayed, new Context(inspectedLevelKnown), 0);
            if (translated == null || containsNonLatinText(translated)) throw unavailable(displayed, "no_safe_resource_translation");
            return translated;
        } catch (LimitReached limit) { throw unavailable(displayed, "translation_work_limit"); }
    }

    public VisibleText translateVisible(String displayed, boolean clipped) {
        return translateVisibleInScene(displayed,clipped,null);
    }

    /** Only reviewed, already-public scene names restrict the resource search. No live object is inspected. */
    public String translateInScene(String displayed,String publicScene) {
        String word=CONSERVATIVE_WORDS.get(displayed);if(word!=null)return word;
        String normalized=dictionary.normalized.get(displayed);if(normalized!=null)return normalized;
        String scope=PUBLIC_SCENE_SCOPES.get(publicScene);
        if("gamescene".equals(scope)&&"指南".equals(displayed)) {
            String guidebook=policyResource(displayed,"items.journal.guidebook.hint_status");
            if(guidebook!=null)return guidebook;
        }
        if(scope!=null && displayed!=null && containsNonLatinText(displayed)) {
            Map<String,Set<String>> entries=dictionary.sceneEntries.get(scope);
            String english=entries==null?null:unique(entries.get(displayed));
            if(english!=null)return english;
        }
        return translate(displayed);
    }

    public VisibleText translateVisibleInScene(String displayed,boolean clipped,String publicScene) {
        try { return new VisibleText(translateInScene(displayed,publicScene), false); }
        catch (PublicTextUnavailableException unavailable) {
            if (!clipped) throw unavailable;
            return new VisibleText("Partially displayed text", true);
        }
    }

    /** Context contains only previously public DTO facts; unknown fields never influence translation. */
    public String translateInContext(String displayed,Map<String,Object> publicContext) {
        String word=CONSERVATIVE_WORDS.get(displayed);if(word!=null)return word;
        if(displayed==null || !containsNonLatinText(displayed))return translate(displayed);
        if(displayed.length()>MAX_TEXT)throw unavailable(displayed,"displayed_text_too_long");
        Map<String,Object> context=publicContext==null?Collections.emptyMap():publicContext;
        String scene=context.get("scene") instanceof String?(String)context.get("scene"):null;
        String scope=PUBLIC_SCENE_SCOPES.get(scene);
        String shortcut=context.get("shortcut_action") instanceof String?(String)context.get("shortcut_action"):null;
        String resolved;
        if("floating_text".equals(context.get("presentation"))
                &&(resolved=policyResource(displayed,"actors.char.def_verb"))!=null)return resolved;
        if("floating_text".equals(context.get("presentation"))
                &&(resolved=policyResource(displayed,"actors.hero.abilities.rogue.deathmark$deathmarktracker.name"))!=null)return resolved;
        if(("gamescene".equals(scope)||"heroselectscene".equals(scope))&&Boolean.TRUE.equals(context.get("modal"))
                &&context.get("visible_window_texts") instanceof List
                &&(resolved=describedName(displayed,(List<?>)context.get("visible_window_texts")))!=null)return resolved;
        if("gamescene".equals(scope)&&Boolean.TRUE.equals(context.get("button"))
                &&context.get("inspected_item_level_known") instanceof Boolean) {
            if(Boolean.TRUE.equals(context.get("cloak_item_menu"))
                    &&(resolved=policyResource(displayed,"items.artifacts.cloakofshadows.ac_stealth"))!=null)return resolved;
            if(Boolean.TRUE.equals(context.get("sneak_weapon_menu"))
                    &&(resolved=policyResource(displayed,"items.weapon.melee.dagger.ability_name"))!=null)return resolved;
            if(Boolean.TRUE.equals(context.get("combo_weapon_menu"))
                    &&(resolved=policyResource(displayed,"items.weapon.melee.gloves.ability_name"))!=null)return resolved;
            if(Boolean.TRUE.equals(context.get("shadow_clone_menu"))
                    &&(resolved=policyResource(displayed,"actors.hero.abilities.rogue.shadowclone.name"))!=null)return resolved;
            if(Boolean.TRUE.equals(context.get("death_mark_menu"))
                    &&(resolved=policyResource(displayed,"actors.hero.abilities.rogue.deathmark.name"))!=null)return resolved;
        }
        if("gamescene".equals(scope)&&Boolean.TRUE.equals(context.get("button"))) {
            if(Boolean.TRUE.equals(context.get("steal_warning"))) {
                if((resolved=policyResource(displayed,"windows.wndtradeitem.steal_warn_yes"))!=null)return resolved;
                if((resolved=policyResource(displayed,"windows.wndtradeitem.steal_warn_no"))!=null)return resolved;
            }
            if(Boolean.TRUE.equals(context.get("resurrection_warning"))) {
                if((resolved=policyResource(displayed,"windows.wndresurrect.warn_yes"))!=null)return resolved;
                if((resolved=policyResource(displayed,"windows.wndresurrect.warn_no"))!=null)return resolved;
            }
            if(Boolean.TRUE.equals(context.get("reward_confirmation"))) {
                if((resolved=policyResource(displayed,"windows.wndsadghost.confirm"))!=null)return resolved;
                if((resolved=policyResource(displayed,"windows.wndsadghost.cancel"))!=null)return resolved;
            }
            if(Boolean.TRUE.equals(context.get("support_prompt_close"))
                    &&(resolved=policyResource(displayed,"windows.wndsupportprompt.close"))!=null)return resolved;
            if(Boolean.TRUE.equals(context.get("augmentation_window"))) {
                String label=unique(dictionary.augmentationLabels.get(displayed));if(label!=null)return label;
            }
            if(Boolean.TRUE.equals(context.get("upgrade_preview"))
                    &&(resolved=policyResource(displayed,"windows.wndupgrade.back"))!=null)return resolved;
            if(Boolean.TRUE.equals(context.get("scroll_cancel"))) {
                if((resolved=policyResource(displayed,"items.scrolls.inventoryscroll.yes"))!=null)return resolved;
                if((resolved=policyResource(displayed,"items.scrolls.inventoryscroll.no"))!=null)return resolved;
            }
        }
        if("heroselectscene".equals(scope)&&Boolean.TRUE.equals(context.get("hero_subclass_page"))
                &&(resolved=policyResource(displayed,"windows.wndheroinfo.subclasses"))!=null)return resolved;
        if("rankingsscene".equals(scope)&&Boolean.TRUE.equals(context.get("ranking_record"))) {
            if((resolved=policyResource(displayed,"rankings$record.won"))!=null)return resolved;
            String[] lines=displayed.split("\n",-1);
            if(lines.length==3&&lines[0].matches("(?:[1-9][0-9]*| )")&&lines[2].matches("[0-9]+")
                    &&(resolved=policyResource(lines[1],"rankings$record.won"))!=null)
                return lines[0]+"\n"+resolved+"\n"+lines[2];
        }
        if(Boolean.TRUE.equals(context.get("cell_input"))&&Boolean.FALSE.equals(context.get("modal"))
                &&context.get("cell_prompt") instanceof String
                &&(resolved=matchingPublicCellPrompt(displayed,(String)context.get("cell_prompt")))!=null)return resolved;
        if(Boolean.TRUE.equals(context.get("key_binding_input"))) {
            if(Boolean.TRUE.equals(context.get("button"))) {
                String label=unique(dictionary.bindingInputLabels.get(displayed));
                if(label!=null)return label;
            }
            String template=bindingInputTemplate(displayed);
            if(template!=null)return template;
            if(displayed.indexOf('\n')>=0) {
                StringBuilder result=new StringBuilder();String[] lines=displayed.split("\\n",-1);
                for(int i=0;i<lines.length;i++){if(i>0)result.append('\n');result.append(translateInContext(lines[i],context));}
                return result.toString();
            }
            // An isolated word is not enough to tell the current key value from
            // the Unbind Key button. Only the full template or public button role can.
            if(dictionary.bindingInputLabels.containsKey(displayed)&&dictionary.keyBindingLabels.containsKey(displayed)
                    &&!java.util.Objects.equals(unique(dictionary.bindingInputLabels.get(displayed)),unique(dictionary.keyBindingLabels.get(displayed))))
                throw unavailable(displayed,"binding_input_text_requires_public_role_or_complete_template");
        }
        if("gamescene".equals(scope)&&(Boolean.TRUE.equals(context.get("custom_note_input"))
                ||Boolean.TRUE.equals(context.get("custom_note_view"))||Boolean.TRUE.equals(context.get("custom_note_delete")))) {
            String label=unique(dictionary.customNoteLabels.get(displayed));
            if(label!=null)return label;
        }
        if(Boolean.TRUE.equals(context.get("key_binding"))||Boolean.TRUE.equals(context.get("key_binding_panel"))) {
            String label=unique(dictionary.keyBindingLabels.get(displayed));
            if(label!=null)return label;
            if(displayed.indexOf('\n')>=0) {
                StringBuilder result=new StringBuilder();String[] lines=displayed.split("\\n",-1);
                for(int i=0;i<lines.length;i++){if(i>0)result.append('\n');result.append(translateInContext(lines[i],context));}
                return result.toString();
            }
        }
        if("back".equalsIgnoreCase(shortcut)
                &&(resolved=policyResource(displayed,"windows.wndkeybindings.back"))!=null)return resolved;
        boolean slider=Boolean.TRUE.equals(context.get("slider")),checkbox=Boolean.TRUE.equals(context.get("checkbox"));
        if(slider||checkbox) {
            if((resolved=policyResource(displayed,"windows.wndsettings$displaytab.off"))!=null)return resolved;
            if(displayed.indexOf('\n')>=0) {
                StringBuilder result=new StringBuilder();String[] lines=displayed.split("\\n",-1);
                for(int i=0;i<lines.length;i++) {if(i>0)result.append('\n');result.append(translateInContext(lines[i],context));}
                return result.toString();
            }
        }
        if("startscene".equals(scope)&&Boolean.TRUE.equals(context.get("save_details"))
                &&(resolved=policyResource(displayed,"windows.wndgameinprogress.erase"))!=null)return resolved;
        if("gamescene".equals(scope)&&Boolean.TRUE.equals(context.get("game_menu"))
                &&(resolved=policyResource(displayed,"windows.wndgame.settings"))!=null)return resolved;
        if("gamescene".equals(scope)&&Boolean.TRUE.equals(context.get("chasm_prompt"))
                &&(resolved=policyResource(displayed,"levels.features.chasm.no"))!=null)return resolved;
        if("rankingsscene".equals(scope)&&Boolean.TRUE.equals(context.get("victory_congratulations"))
                &&Boolean.TRUE.equals(context.get("button"))
                &&(resolved=policyResource(displayed,"windows.wndvictorycongrats.close"))!=null)return resolved;
        if("journalscene".equals(scope)) {
            Matcher heading=CATALOG_HEADING.matcher(displayed);
            if(heading.matches()) {
                String title=unique(dictionary.catalogTitles.get(heading.group(1)));
                if(title!=null)return "_"+title+"_ ("+heading.group(2)+"/"+heading.group(3)+")"+heading.group(4);
            }
        }
        try { return translateInScene(displayed,scene); }
        catch(PublicTextUnavailableException unavailable) {
            Object known=context.get("inspected_item_level_known");
            if(!(known instanceof Boolean))throw unavailable;
            return translateDisplayed(displayed,(Boolean)known);
        }
    }

    public VisibleText translateVisibleInContext(String displayed,boolean clipped,Map<String,Object> publicContext) {
        try{return new VisibleText(translateInContext(displayed,publicContext),false);}
        catch(PublicTextUnavailableException unavailable) {
            if(!clipped)throw unavailable;
            return new VisibleText("Partially displayed text",true);
        }
    }

    private String policyResource(String displayed,String key) {
        ResourcePair resource=dictionary.policyResources.get(key);
        return resource!=null&&resource.chinese.equals(displayed)&&!containsNonLatinText(resource.english)?resource.english:null;
    }

    /** Original complete WndSupportPrompt composition, using only bundled text and public nodes. */
    boolean matchesSupportPrompt(List<String> texts, List<String> buttons) {
        if(texts.size()!=2||buttons.size()!=2)return false;
        for(boolean chinese:new boolean[]{true,false}) {
            String title=supportResource("windows.wndsupportprompt.title",chinese);
            String intro=supportResource("windows.wndsupportprompt.intro",chinese);
            String message=supportResource("scenes.supporterscene.patreon_msg",chinese);
            String note=supportResource("scenes.supporterscene.patreon_english",chinese);
            String link=supportResource("scenes.supporterscene.supporter_link",chinese);
            String close=supportResource("windows.wndsupportprompt.close",chinese);
            if(title==null||intro==null||message==null||note==null||link==null||close==null)continue;
            if(!title.equals(texts.get(0))||!link.equals(buttons.get(0))||!close.equals(buttons.get(1)))continue;
            String body=intro+"\n\n"+message;
            // A Chinese GUI always displays the English-only rewards notice. Its
            // English public presentation retains that sentence. A native English
            // GUI omits it, as the original constructor specifies.
            if((body+"\n"+note+"\n- Evan").equals(texts.get(1))
                    ||!chinese&&(body+"\n- Evan").equals(texts.get(1)))return true;
        }
        return false;
    }

    private String supportResource(String key,boolean chinese) {
        ResourcePair pair=dictionary.policyResources.get(key);
        return pair==null?null:chinese?pair.chinese:pair.english;
    }

    private String bindingInputTemplate(String displayed) {
        Set<String> candidates=new TreeSet<>();boolean matched=false;
        for(Template template:dictionary.bindingInputTemplates) {
            if(!displayed.contains(template.anchor))continue;
            Matcher matcher=template.pattern.matcher(displayed);if(!matcher.matches())continue;
            matched=true;
            Map<Integer,String> arguments=new HashMap<>();boolean valid=true;
            for(int i=0;i<template.source.arguments.size();i++) {
                String value=matcher.group(i+1);
                String english=containsNonLatinText(value)?unique(dictionary.keyBindingLabels.get(value)):value;
                int slot=template.source.arguments.get(i).index;
                if(english==null||arguments.containsKey(slot)&&!arguments.get(slot).equals(english)){valid=false;break;}
                arguments.put(slot,english);
            }
            if(valid){String english=template.target.render(arguments);if(english!=null)candidates.add(english);}
        }
        String resolved=unique(candidates);
        if(matched&&resolved==null)throw unavailable(displayed,"binding_template_parameter_requires_public_key_domain");
        return resolved;
    }

    public Map<String,Integer> statistics() { return dictionary.statistics; }

    /** Only complete current public description units may resolve their own paired name. */
    private String describedName(String displayed,List<?> visibleTexts) {
        Set<String> original=dictionary.exact.get(displayed);
        if(original==null||unique(original)!=null)return null;
        Set<String> names=new TreeSet<>();
        for(NamedDescription description:dictionary.namedDescriptions) {
            if(!description.name.chinese.equals(displayed))continue;
            for(Object raw:visibleTexts)if(raw instanceof String&&descriptionMatches((String)raw,description.body)) {
                names.add(description.name.english);break;
            }
        }
        return unique(names);
    }

    private boolean descriptionMatches(String displayed,ResourcePair description) {
        try{translate(displayed);}catch(PublicTextUnavailableException unavailable){return false;}
        List<String> units=new ArrayList<>();units.add(displayed);
        Collections.addAll(units,displayed.split("\\n\\n",-1));
        for(String unit:units)for(String source:new String[]{description.chinese,description.english}) {
            if(unit.equals(source))return true;
            for(String separator:new String[]{" ","\n\n"})if(unit.startsWith(source+separator)) {
                String rest=unit.substring(source.length()+separator.length());
                for(Pattern cost:dictionary.descriptionCosts)if(cost.matcher(rest).matches())return true;
            }
        }
        return false;
    }

    private String abilityRow(String displayed) {
        Matcher header=ABILITY_ROW.matcher(displayed);if(!header.matches())return null;
        Set<String> candidates=new TreeSet<>();
        for(AbilityRow row:dictionary.abilityRows) {
            if(!row.name.chinese.equals(header.group(1)))continue;
            String cost=completePair(row.cost,row.costTemplate,header.group(2));
            String body=completePair(row.body,row.bodyTemplate,header.group(3));
            if(cost!=null&&body!=null)candidates.add("_"+row.name.english+" "+cost+":_ "+body);
        }
        String translated=unique(candidates);
        if(translated==null)throw unavailable(displayed,"displayed_ability_row_requires_matching_full_resources");
        return translated;
    }

    private String completePair(ResourcePair pair,Template template,String displayed) {
        if(template==null)return pair.chinese.equals(displayed)?pair.english:null;
        Matcher matcher=template.pattern.matcher(displayed);if(!matcher.matches())return null;
        Map<Integer,String> arguments=new HashMap<>();
        for(int i=0;i<template.source.arguments.size();i++) {
            int slot=template.source.arguments.get(i).index;String value;
            try{value=translate(matcher.group(i+1));}catch(PublicTextUnavailableException unavailable){return null;}
            if(arguments.containsKey(slot)&&!arguments.get(slot).equals(value))return null;
            arguments.put(slot,value);
        }
        return template.target.render(arguments);
    }

    /** A result already in this public UI may resolve a complete resource match, never a prefix. */
    private String matchingPublicCellPrompt(String displayed,String prompt) {
        if(prompt.isEmpty()||containsNonLatinText(prompt))return null;
        Set<String> exact=dictionary.exact.get(displayed);
        if(exact!=null&&exact.contains(prompt))return prompt;
        for(Template template:dictionary.templates) {
            if(!displayed.contains(template.anchor))continue;
            Matcher matcher=template.pattern.matcher(displayed);if(!matcher.matches())continue;
            Map<Integer,String> arguments=new HashMap<>();boolean valid=true;
            for(int i=0;i<template.source.arguments.size();i++) {
                int slot=template.source.arguments.get(i).index;String value;
                try{value=translate(matcher.group(i+1));}catch(PublicTextUnavailableException unavailable){valid=false;break;}
                if(arguments.containsKey(slot)&&!arguments.get(slot).equals(value)){valid=false;break;}
                arguments.put(slot,value);
            }
            if(valid&&prompt.equals(template.target.render(arguments)))return prompt;
        }
        return null;
    }

    public static final class VisibleText {
        public final String text;
        public final boolean partial;
        private VisibleText(String text, boolean partial) { this.text=text; this.partial=partial; }
    }

    public static final class PublicTextUnavailableException extends IllegalStateException {
        public final String code = UNAVAILABLE_CODE;
        private final String originalText, diagnosticReason;
        private PublicTextUnavailableException(String original, String reason) {
            super("Displayed text has no unambiguous English translation.");
            originalText=original; diagnosticReason=reason;
        }
        /** For the private diagnostic store only; never include this in the public error. */
        public String diagnosticOriginalText() { return originalText; }
        public String diagnosticReason() { return diagnosticReason; }
    }

    private static PublicTextUnavailableException unavailable(String text,String reason) { return new PublicTextUnavailableException(text,reason); }
    private static final class DefaultDictionary { private static final Dictionary VALUE=load(DisplayedTextEnglish.class.getClassLoader()); }
    private static final class LimitReached extends RuntimeException { }
    private static final class Context {
        int work;
        final Boolean inspectedLevelKnown;
        Context(Boolean inspectedLevelKnown){this.inspectedLevelKnown=inspectedLevelKnown;}
        final Map<String,String> translated=new HashMap<>();
        final Set<String> unavailable=new TreeSet<>();
        void spend() { if (++work > MAX_WORK) throw new LimitReached(); }
    }

    private String translate(String text, Context context, int depth) {
        String word=CONSERVATIVE_WORDS.get(text);if(word!=null)return word;
        String language=dictionary.languageNames.get(text.toLowerCase(Locale.ROOT));
        if(language!=null)return language;
        if (!containsNonLatinText(text)) return text;
        context.spend();
        if (depth > MAX_DEPTH || context.unavailable.contains(text)) return null;
        if (context.translated.containsKey(text)) return context.translated.get(text);
        String speech=quotedSpeech(text,context,depth+1);
        if(speech!=null)return remember(text,speech,context);
        Set<String> exact=dictionary.exact.get(text);
        if (exact != null) return remember(text, exactTranslation(text,context.inspectedLevelKnown), context);

        Set<String> candidates=new TreeSet<>();
        for (Template template : dictionary.templates) {
            if(context.inspectedLevelKnown!=null && (template==dictionary.spearActual&&!context.inspectedLevelKnown
                    ||template==dictionary.spearTypical&&context.inspectedLevelKnown))continue;
            if (!text.contains(template.anchor)) continue;
            Matcher matcher=template.pattern.matcher(text);
            if (!matcher.matches()) continue;
            Map<Integer,String> arguments=new HashMap<>();
            boolean valid=true;
            for (int i=0;i<template.source.arguments.size();i++) {
                int slot=template.source.arguments.get(i).index;
                String value=translate(matcher.group(i+1),context,depth+1);
                if(value==null || arguments.containsKey(slot) && !arguments.get(slot).equals(value)) {valid=false;break;}
                arguments.put(slot,value);
            }
            if(valid) {
                String output=template.target.render(arguments);
                if(output!=null && !containsChinese(output)) candidates.add(output);
            }
        }
        if(!candidates.isEmpty()) return remember(text,unique(candidates),context);
        // Native descriptions append complete resource/template units with visible
        // whitespace. A unit may itself contain several sentences: splitting it at every
        // Chinese full stop would discard that resource's exact matching boundary.
        Set<String> concatenated=new TreeSet<>();
        for(int i=1;i<text.length()-1;i++)if(Character.isWhitespace(text.charAt(i))&&!Character.isWhitespace(text.charAt(i-1))) {
            int end=i+1;while(end<text.length()&&Character.isWhitespace(text.charAt(end)))end++;
            if(end==text.length())continue;
            context.spend();
            Set<String> left=completeResourceUnit(text.substring(0,i),context,depth+1);
            if(left.isEmpty())continue;
            String right=translate(text.substring(end),context,depth+1);
            if(right!=null)for(String translated:left)concatenated.add(translated+text.substring(i,end)+right);
            i=end-1;
        }
        if(!concatenated.isEmpty())return remember(text,unique(concatenated),context);
        String marker=text.length()>4&&text.startsWith("**")&&text.endsWith("**")?"**":
                text.length()>2&&text.startsWith("_")&&text.endsWith("_")?"_":null;
        if(marker!=null) {
            String inner=translate(text.substring(marker.length(),text.length()-marker.length()),context,depth+1);
            return remember(text,inner==null?null:marker+inner+marker,context);
        }
        String trimmed=text.trim();
        if(!trimmed.equals(text)) {
            int start=text.indexOf(trimmed);
            String translated=translate(trimmed,context,depth+1);
            return remember(text,translated==null?null:text.substring(0,start)+translated+text.substring(start+trimmed.length()),context);
        }

        // Concatenated resource paragraphs are separate visible units. This only
        // divides supplied text; it never fetches the unseen rest of a resource.
        // Keep a resource's own single line breaks together (e.g. Spear's two
        // displayed stat lines) before trying smaller line units.
        if(text.contains("\n\n")) {
            StringBuilder result=new StringBuilder();boolean valid=true;int start=0;
            while(start<text.length()) {
                int end=text.indexOf("\n\n",start);if(end<0)end=text.length();
                String part=translate(text.substring(start,end),context,depth+1);
                if(part==null){valid=false;break;}
                result.append(part);
                if(end<text.length()){result.append("\n\n");start=end+2;}else start=end;
            }
            if(valid)return remember(text,result.toString(),context);
        }
        if(text.indexOf('\n')>=0) {
            StringBuilder result=new StringBuilder(); boolean valid=true;
            int start=0;
            for(int i=0;i<=text.length();i++) if(i==text.length() || text.charAt(i)=='\n') {
                String part=translate(text.substring(start,i),context,depth+1);
                if(part==null) {valid=false;break;}
                result.append(part); if(i<text.length())result.append('\n'); start=i+1;
            }
            if(valid)return remember(text,result.toString(),context);
        }
        List<String> sentences=new ArrayList<>();int sentenceStart=0;
        for(int i=0;i<text.length();i++)if("。！？".indexOf(text.charAt(i))>=0) {
            sentences.add(text.substring(sentenceStart,i+1));sentenceStart=i+1;
        }
        if(sentenceStart<text.length())sentences.add(text.substring(sentenceStart));
        if(sentences.size()>1) {
            StringBuilder result=new StringBuilder();boolean valid=true;
            for(String sentence:sentences) {
                String translated=translate(sentence,context,depth+1);
                if(translated==null){valid=false;break;}
                result.append(translated);
            }
            if(valid)return remember(text,result.toString(),context);
        }
        return remember(text,compose(text,0,context,new HashMap<>(),0),context);
    }

    private String compose(String text,int offset,Context context,Map<Integer,String> memo,int segments) {
        if(offset==text.length())return "";
        if(segments>256)throw new LimitReached();
        context.spend();
        if(memo.containsKey(offset))return memo.get(offset);
        List<String> entries=dictionary.prefixes.get(text.charAt(offset));
        // Prefer the longest complete resource unit at this boundary. Equal source
        // strings retain all English candidates and cannot be resolved by insertion order.
        if(entries!=null)for(String source:entries) {
            if(!text.startsWith(source,offset))continue;
            String translated=exactTranslation(source,context.inspectedLevelKnown);
            if(translated==null) {memo.put(offset,null);return null;}
            String rest=compose(text,offset+source.length(),context,memo,segments+1);
            if(rest!=null) {String result=join(translated,rest);memo.put(offset,result);return result;}
        }
        if(!isNonLatinText(text.codePointAt(offset))) {
            int end=offset+Character.charCount(text.codePointAt(offset));
            while(end<text.length() && !isNonLatinText(text.codePointAt(end)) && !resourceStartsAt(text,end))
                end+=Character.charCount(text.codePointAt(end));
            String rest=compose(text,end,context,memo,segments+1);
            String result=rest==null?null:join(text.substring(offset,end),rest);
            memo.put(offset,result);return result;
        }
        memo.put(offset,null);return null;
    }

    /** Full resource/template candidates only, with no prefix or partial fallback. */
    private Set<String> completeResourceUnit(String text,Context context,int depth) {
        Set<String> result=new TreeSet<>();
        String speech=quotedSpeech(text,context,depth+1);if(speech!=null){result.add(speech);return result;}
        Set<String> exact=dictionary.exact.get(text);
        if(exact!=null) {
            String known=exactTranslation(text,context.inspectedLevelKnown);
            if(known!=null)result.add(known);else result.addAll(exact);
            return result;
        }
        for(Template template:dictionary.templates) {
            if(context.inspectedLevelKnown!=null&&(template==dictionary.spearActual&&!context.inspectedLevelKnown
                    ||template==dictionary.spearTypical&&context.inspectedLevelKnown))continue;
            if(!text.contains(template.anchor))continue;
            Matcher match=template.pattern.matcher(text);if(!match.matches())continue;
            Map<Integer,String> arguments=new HashMap<>();boolean valid=true;
            for(int i=0;i<template.source.arguments.size();i++) {
                int slot=template.source.arguments.get(i).index;
                String value=translate(match.group(i+1),context,depth+1);
                if(value==null||arguments.containsKey(slot)&&!arguments.get(slot).equals(value)){valid=false;break;}
                arguments.put(slot,value);
            }
            if(valid){String translated=template.target.render(arguments);if(translated!=null)result.add(translated);}
        }
        return result;
    }

    /** Mob.yell's already displayed speaker: "speech" form, including its literal trailing spaces. */
    private String quotedSpeech(String displayed,Context context,int depth) {
        if(depth>MAX_DEPTH)return null;
        Matcher match=QUOTED_SPEECH.matcher(displayed);if(!match.matches())return null;
        String speaker=unique(completeResourceUnit(match.group(1),context,depth+1));
        if(speaker==null)return null;
        String words=translate(match.group(2),context,depth+1);
        return words==null?null:speaker+": \""+words+"\""+match.group(3);
    }

    private boolean resourceStartsAt(String text,int offset) {
        List<String> sources=dictionary.prefixes.get(text.charAt(offset));
        if(sources!=null)for(String source:sources)if(text.startsWith(source,offset))return true;
        return false;
    }

    private String exactTranslation(String displayed,Boolean inspectedLevelKnown) {
        Template selected=inspectedLevelKnown==null?null:inspectedLevelKnown?dictionary.spearActual:dictionary.spearTypical;
        if(selected!=null&&selected.chinese.equals(displayed))return selected.english;
        return dictionary.resolved.get(displayed);
    }

    private static String remember(String source,String translated,Context context) {
        if(translated==null)context.unavailable.add(source);else context.translated.put(source,translated);
        return translated;
    }
    private static String unique(Set<String> values) {
        String exact=uniqueIgnoringCase(values);
        if(exact!=null||values==null||values.isEmpty())return exact;
        Set<String> withoutOptionalPeriod=new TreeSet<>();
        for(String value:values)withoutOptionalPeriod.add(withoutSingleTerminalPeriod(value));
        return uniqueIgnoringCase(withoutOptionalPeriod);
    }
    private static String uniqueIgnoringCase(Set<String> values) {
        if(values==null || values.isEmpty())return null;
        String first=null,folded=null;
        for(String value:values) {
            if(containsNonLatinText(value))return null;
            if(first==null) {first=value;folded=value.toLowerCase(Locale.ROOT);}
            else if(!folded.equals(value.toLowerCase(Locale.ROOT)))return null;
        }
        return first;
    }
    private static String withoutSingleTerminalPeriod(String value) {
        // A normal word/number followed by one final period only. Do not trim,
        // collapse an ellipsis, or strip a period following other punctuation.
        if(value.length()>1&&value.endsWith(".")&&Character.isLetterOrDigit(value.codePointBefore(value.length()-1)))
            return value.substring(0,value.length()-1);
        return value;
    }
    private static String join(String left,String right) {
        if(!left.isEmpty()&&!right.isEmpty() && Character.isLetterOrDigit(left.charAt(left.length()-1))
                && Character.isLetterOrDigit(right.charAt(0)))return left+" "+right;
        return left+right;
    }
    private static boolean containsChinese(String text) {
        if(text==null)return false;
        for(int i=0;i<text.length();) {int cp=text.codePointAt(i);if(isChinese(cp))return true;i+=Character.charCount(cp);}
        return false;
    }
    private static boolean isChinese(int codePoint) { return Character.UnicodeScript.of(codePoint)==Character.UnicodeScript.HAN; }
    private static boolean containsNonLatinText(String text) {
        if(text==null)return false;
        for(int i=0;i<text.length();) {int cp=text.codePointAt(i);if(isNonLatinText(cp))return true;i+=Character.charCount(cp);}
        return false;
    }
    private static boolean isNonLatinText(int codePoint) {
        Character.UnicodeScript script=Character.UnicodeScript.of(codePoint);
        return Character.isLetter(codePoint) && script!=Character.UnicodeScript.LATIN && script!=Character.UnicodeScript.COMMON;
    }
    private static Map<String,String> publicSceneScopes() {
        Map<String,String> scopes=new HashMap<>();
        for(String name:new String[]{"Scene","AboutScene","AlchemyScene","AmuletScene","ChangesScene","GameScene",
                "HeroSelectScene","InterlevelScene","JournalScene","NewsScene","PixelScene","RankingsScene",
                "StartScene","SupporterScene","SurfaceScene","TitleScene","WelcomeScene"})
            scopes.put(name,name.toLowerCase(Locale.ROOT));
        // These aliases are explicitly published by GameSnapshotter.sceneName.
        String[][] aliases={{"title","titlescene"},{"start","startscene"},{"hero_select","heroselectscene"},
                {"game","gamescene"},{"alchemy","alchemyscene"},{"transition","interlevelscene"},
                {"surface","surfacescene"},{"rankings","rankingsscene"},{"badges","badgesscene"},
                {"welcome","welcomescene"},{"intro","introscene"},{"amulet","amuletscene"}};
        for(String[] alias:aliases)scopes.put(alias[0],alias[1]);
        return Collections.unmodifiableMap(scopes);
    }

    private static final class Argument {
        final int index;final char conversion;
        Argument(int index,char conversion){this.index=index;this.conversion=conversion;}
    }
    private static final class ResourcePair {
        final String chinese,english;
        ResourcePair(String chinese,String english){this.chinese=chinese;this.english=english;}
    }
    private static final class AbilityRow {
        final ResourcePair name,cost,body;
        final Template costTemplate,bodyTemplate;
        AbilityRow(ResourcePair name,ResourcePair cost,ResourcePair body) {
            this.name=name;this.cost=cost;this.body=body;
            costTemplate=new Template(cost.chinese,cost.english);
            bodyTemplate=new Printf(body.chinese).arguments.isEmpty()?null:new Template(body.chinese,body.english);
        }
    }
    private static final class NamedDescription {
        final ResourcePair name,body;
        NamedDescription(ResourcePair name,ResourcePair body){this.name=name;this.body=body;}
    }
    private static final class Printf {
        final List<String> literals=new ArrayList<>();
        final List<Argument> arguments=new ArrayList<>();
        final Set<Integer> slots=new TreeSet<>();
        Printf(String format) {
            Matcher matcher=FORMAT.matcher(format);StringBuilder literal=new StringBuilder();int offset=0,implicit=0,last=-1;
            while(matcher.find()) {
                literal.append(format,offset,matcher.start());offset=matcher.end();
                char conversion=matcher.group(5).charAt(0);
                if(conversion=='%'){literal.append('%');continue;}
                int index=matcher.group(1)!=null?Integer.parseInt(matcher.group(1))-1:
                        matcher.group(2).contains("<")?last:implicit++;
                if(index<0 || index>99)throw new IllegalArgumentException("Unsupported displayed-text format argument");
                literals.add(literal.toString());literal.setLength(0);
                arguments.add(new Argument(index,conversion));slots.add(index);last=index;
            }
            literal.append(format,offset,format.length());literals.add(literal.toString());
        }
        String render(Map<Integer,String> values) {
            StringBuilder output=new StringBuilder();
            for(int i=0;i<arguments.size();i++) {
                String value=values.get(arguments.get(i).index);if(value==null)return null;
                output.append(literals.get(i));output.append(value);
            }
            return output.append(literals.get(literals.size()-1)).toString();
        }
    }
    private static final class Template {
        final Printf source,target;final Pattern pattern;final String anchor;
        final String chinese,english;
        Template(String chinese,String english) {
            this.chinese=chinese;this.english=english;
            source=new Printf(chinese);target=new Printf(english);
            if(source.arguments.isEmpty() || !source.slots.equals(target.slots))throw new IllegalArgumentException("Resource argument mismatch");
            StringBuilder regex=new StringBuilder("\\A");String longest="";
            for(int i=0;i<source.arguments.size();i++) {
                String literal=source.literals.get(i);regex.append(Pattern.quote(literal));
                if(containsChinese(literal)&&literal.length()>longest.length())longest=literal;
                char conversion=source.arguments.get(i).conversion;
                if(conversion=='d')regex.append("([+-]?[0-9][0-9,]*)");
                else if("feEgG".indexOf(conversion)>=0)regex.append("([+-]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)");
                else regex.append("(.*?)");
            }
            String tail=source.literals.get(source.literals.size()-1);regex.append(Pattern.quote(tail)).append("\\z");
            if(containsChinese(tail)&&tail.length()>longest.length())longest=tail;
            if(longest.isEmpty())throw new IllegalArgumentException("No literal Chinese anchor");
            anchor=longest;pattern=Pattern.compile(regex.toString(),Pattern.DOTALL);
        }
    }

    private static final class Dictionary {
        final Map<String,Set<String>> exact;
        final Map<String,String> resolved;
        final Map<String,String> normalized;
        final Map<Character,List<String>> prefixes;
        final List<Template> templates;
        final Map<String,Integer> statistics;
        final Map<String,String> languageNames;
        final Map<String,Map<String,Set<String>>> sceneEntries;
        final Map<String,ResourcePair> policyResources;
        final Map<String,Set<String>> catalogTitles;
        final Map<String,Set<String>> keyBindingLabels;
        final Map<String,Set<String>> customNoteLabels;
        final Map<String,Set<String>> bindingInputLabels;
        final List<Template> bindingInputTemplates;
        final Template spearActual,spearTypical;
        final List<AbilityRow> abilityRows;
        final List<NamedDescription> namedDescriptions;
        final List<Pattern> descriptionCosts;
        final Map<String,Set<String>> augmentationLabels;
        Dictionary(Map<String,String> chinese,Map<String,String> english) {
            Map<String,Set<String>> entries=new TreeMap<>();List<Template> patterns=new ArrayList<>();
            Map<String,Map<String,Set<String>>> byScene=new TreeMap<>();
            Map<String,ResourcePair> policies=new TreeMap<>();Map<String,Set<String>> catalogs=new TreeMap<>(),bindings=new TreeMap<>(),notes=new TreeMap<>(),bindingInputs=new TreeMap<>();
            List<Template> bindingTemplates=new ArrayList<>();
            Template actualSpear=null,typicalSpear=null;
            int pairs=0,templatePairs=0,unsupported=0;
            List<AbilityRow> rows=new ArrayList<>();
            for(String key:new TreeSet<>(chinese.keySet())) {
                boolean combo=key.matches("actors\\.buffs\\.combo\\$combomove\\.[a-z]+\\.name");
                boolean monk=key.matches("actors\\.buffs\\.monkenergy\\$monkability\\$[a-z]+\\.name");
                if(!combo&&!monk)continue;
                String cost=combo?"windows.wndcombo.combo_req":"windows.wndmonkabilities.energycost";
                String base=key.substring(0,key.length()-4);
                for(String suffix:new String[]{"desc","empower_desc"}) {
                    if(english.get(key)==null||chinese.get(cost)==null||english.get(cost)==null
                            ||chinese.get(base+suffix)==null||english.get(base+suffix)==null)continue;
                    try{rows.add(new AbilityRow(new ResourcePair(chinese.get(key),english.get(key)),
                            new ResourcePair(chinese.get(cost),english.get(cost)),
                            new ResourcePair(chinese.get(base+suffix),english.get(base+suffix))));}
                    catch(IllegalArgumentException unsupportedRow){/* Exact displayed row remains unavailable. */}
                }
            }
            abilityRows=Collections.unmodifiableList(rows);
            List<NamedDescription> descriptions=new ArrayList<>();List<Pattern> costs=new ArrayList<>();
            for(String key:new TreeSet<>(chinese.keySet())) {
                boolean ability=key.startsWith("actors.hero.abilities.")&&!key.contains("$")&&key.endsWith(".name");
                boolean talent=key.matches("actors\\.hero\\.talent\\.[a-z_]+\\.title");
                if(!ability&&!talent||english.get(key)==null)continue;
                String base=key.substring(0,key.lastIndexOf('.')+1);
                for(String suffix:new String[]{"desc"}) {
                    String cn=chinese.get(base+suffix),en=english.get(base+suffix);
                    // No live formatting arguments or object identities participate.
                    if(cn!=null&&en!=null&&new Printf(cn).arguments.isEmpty()&&new Printf(en).arguments.isEmpty())
                        descriptions.add(new NamedDescription(new ResourcePair(chinese.get(key),english.get(key)),new ResourcePair(cn,en)));
                }
            }
            for(String key:new String[]{"actors.hero.abilities.armorability.cost","items.armor.classarmor.charge_use","actors.hero.abilities.cleric.trinity.cost"})
                for(String format:new String[]{chinese.get(key),english.get(key)})if(format!=null) {
                    Printf parsed=new Printf(format);StringBuilder pattern=new StringBuilder("\\A");
                    for(int i=0;i<parsed.arguments.size();i++)pattern.append(Pattern.quote(parsed.literals.get(i))).append("[0-9]+(?:[.,][0-9]+)*");
                    pattern.append(Pattern.quote(parsed.literals.get(parsed.literals.size()-1))).append("\\z");
                    costs.add(Pattern.compile(pattern.toString()));
                }
            namedDescriptions=Collections.unmodifiableList(descriptions);descriptionCosts=Collections.unmodifiableList(costs);
            Map<String,Set<String>> augmentation=new TreeMap<>();
            for(String key:new TreeSet<>(chinese.keySet())) {
                String source=chinese.get(key),target=english.get(key);
                if(source==null||target==null||!containsChinese(source))continue;
                pairs++;entries.computeIfAbsent(source,ignored->new TreeSet<>()).add(target);
                if(key.startsWith("items.stones.stoneofaugmentation$wndaugment."))
                    augmentation.computeIfAbsent(source,ignored->new TreeSet<>()).add(target);
                if(key.startsWith("windows.wndkeybindings."))bindings.computeIfAbsent(source,ignored->new TreeSet<>()).add(target);
                if(key.startsWith("ui.customnotebutton$customnotewindow."))notes.computeIfAbsent(source,ignored->new TreeSet<>()).add(target);
                if(key.startsWith("windows.wndkeybindings$wndchangebinding.")) {
                    bindingInputs.computeIfAbsent(source,ignored->new TreeSet<>()).add(target);
                    if(!new Printf(source).arguments.isEmpty())try{bindingTemplates.add(new Template(source,target));}catch(IllegalArgumentException mismatch){/* remains unavailable */}
                }
                if(key.equals("windows.wndkeybindings.back")||key.equals("windows.wndsettings$displaytab.off")
                        ||key.equals("windows.wndgameinprogress.erase")||key.equals("windows.wndgame.settings")||key.equals("levels.features.chasm.no")
                        ||key.equals("items.journal.guidebook.hint_status")||key.equals("windows.wndvictorycongrats.close")
                        ||key.equals("windows.wndheroinfo.subclasses")||key.equals("rankings$record.won")
                        ||key.equals("items.artifacts.cloakofshadows.ac_stealth")||key.equals("items.weapon.melee.dagger.ability_name")
                        ||key.equals("items.weapon.melee.gloves.ability_name")||key.equals("actors.hero.abilities.rogue.shadowclone.name")
                        ||key.equals("actors.hero.abilities.rogue.deathmark.name")
                        ||key.equals("windows.wndupgrade.back")||key.equals("items.scrolls.inventoryscroll.yes")||key.equals("items.scrolls.inventoryscroll.no"))
                    policies.put(key,new ResourcePair(source,target));
                if(key.equals("actors.char.def_verb")||key.equals("windows.wndtradeitem.steal_warn_yes")||key.equals("windows.wndtradeitem.steal_warn_no")
                        ||key.equals("actors.hero.abilities.rogue.deathmark$deathmarktracker.name")
                        ||key.equals("windows.wndresurrect.warn_yes")||key.equals("windows.wndresurrect.warn_no")
                        ||key.equals("windows.wndsadghost.confirm")||key.equals("windows.wndsadghost.cancel"))
                    policies.put(key,new ResourcePair(source,target));
                if(key.startsWith("windows.wndsupportprompt.")||key.equals("scenes.supporterscene.patreon_msg")
                        ||key.equals("scenes.supporterscene.patreon_english")||key.equals("scenes.supporterscene.supporter_link"))
                    policies.put(key,new ResourcePair(source,target));
                if(key.startsWith("journal.catalog.")&&key.endsWith(".title")||key.startsWith("windows.wndjournal$catalogtab.title_"))
                    catalogs.computeIfAbsent(source,ignored->new TreeSet<>()).add(target);
                if(key.startsWith("scenes.")) {
                    int dot=key.indexOf('.',7);
                    String scope=dot<0?"":key.substring(7,dot);
                    if(PUBLIC_SCENE_SCOPES.containsValue(scope))byScene.computeIfAbsent(scope,ignored->new TreeMap<>())
                            .computeIfAbsent(source,ignored->new TreeSet<>()).add(target);
                }
                if(!new Printf(source).arguments.isEmpty()) {
                    templatePairs++;
                    try {
                        Template template=new Template(source,target);patterns.add(template);
                        if(key.equals("items.weapon.melee.spear.ability_desc"))actualSpear=template;
                        if(key.equals("items.weapon.melee.spear.typical_ability_desc"))typicalSpear=template;
                    }catch(IllegalArgumentException mismatch){unsupported++;}
                }
            }
            Map<String,Set<String>> immutableAugmentation=new TreeMap<>();
            for(Map.Entry<String,Set<String>> entry:augmentation.entrySet())immutableAugmentation.put(entry.getKey(),Collections.unmodifiableSet(entry.getValue()));
            augmentationLabels=Collections.unmodifiableMap(immutableAugmentation);
            int resourceStrings=entries.size();
            // WndHeroInfo renders these resource paragraphs as separate complete
            // text blocks. Pair only the six static, argument-free descriptions;
            // never translate a clipped prefix or infer a missing paragraph.
            int heroParagraphs=0;
            for(String hero:new String[]{"warrior","mage","rogue","huntress","duelist","cleric"}) {
                String key="actors.hero.heroclass."+hero+"_desc";
                String source=chinese.get(key),target=english.get(key);
                if(source==null||target==null||!new Printf(source).arguments.isEmpty()||!new Printf(target).arguments.isEmpty())continue;
                String[] sources=source.split("\n\n",-1),targets=target.split("\n\n",-1);
                if(sources.length<2||sources.length!=targets.length)continue;
                boolean complete=true;
                for(int i=0;i<sources.length;i++)if(sources[i].isEmpty()||targets[i].isEmpty())complete=false;
                if(!complete)continue;
                for(int i=0;i<sources.length;i++)if(containsChinese(sources[i])) {
                    entries.computeIfAbsent(sources[i],ignored->new TreeSet<>()).add(targets[i]);heroParagraphs++;
                }
            }
            Map<String,String> names=new TreeMap<>();
            for(Languages language:Languages.values()) {
                String englishName=language==Languages.CHI_SMPL?"Simplified Chinese":language==Languages.CHI_TRAD?"Traditional Chinese":
                        language.name().charAt(0)+language.name().substring(1).toLowerCase(Locale.ROOT).replace('_',' ');
                names.put(language.nativeName().toLowerCase(Locale.ROOT),englishName);
                if(containsChinese(language.nativeName()))entries.computeIfAbsent(language.nativeName(),ignored->new TreeSet<>()).add(englishName);
            }
            languageNames=Collections.unmodifiableMap(names);
            Map<String,Map<String,Set<String>>> scenes=new TreeMap<>();
            for(Map.Entry<String,Map<String,Set<String>>> scene:byScene.entrySet()) {
                Map<String,Set<String>> strings=new TreeMap<>();
                for(Map.Entry<String,Set<String>> entry:scene.getValue().entrySet())strings.put(entry.getKey(),Collections.unmodifiableSet(entry.getValue()));
                scenes.put(scene.getKey(),Collections.unmodifiableMap(strings));
            }
            sceneEntries=Collections.unmodifiableMap(scenes);
            policyResources=Collections.unmodifiableMap(policies);
            Map<String,Set<String>> titles=new TreeMap<>();
            for(Map.Entry<String,Set<String>> entry:catalogs.entrySet())titles.put(entry.getKey(),Collections.unmodifiableSet(entry.getValue()));
            catalogTitles=Collections.unmodifiableMap(titles);
            Map<String,Set<String>> bindingLabels=new TreeMap<>();
            for(Map.Entry<String,Set<String>> entry:bindings.entrySet())bindingLabels.put(entry.getKey(),Collections.unmodifiableSet(entry.getValue()));
            keyBindingLabels=Collections.unmodifiableMap(bindingLabels);
            Map<String,Set<String>> noteLabels=new TreeMap<>();
            for(Map.Entry<String,Set<String>> entry:notes.entrySet())noteLabels.put(entry.getKey(),Collections.unmodifiableSet(entry.getValue()));
            customNoteLabels=Collections.unmodifiableMap(noteLabels);
            Map<String,Set<String>> inputLabels=new TreeMap<>();
            for(Map.Entry<String,Set<String>> entry:bindingInputs.entrySet())inputLabels.put(entry.getKey(),Collections.unmodifiableSet(entry.getValue()));
            bindingInputLabels=Collections.unmodifiableMap(inputLabels);bindingInputTemplates=Collections.unmodifiableList(bindingTemplates);
            Map<String,Set<String>> immutable=new LinkedHashMap<>();Map<String,String> unambiguous=new LinkedHashMap<>(),normalizedValues=new LinkedHashMap<>();
            Map<Character,List<String>> starts=new HashMap<>();
            int ambiguous=0,normalizedAmbiguous=0;
            for(Map.Entry<String,Set<String>> entry:entries.entrySet()) {
                immutable.put(entry.getKey(),Collections.unmodifiableSet(entry.getValue()));
                boolean wasAmbiguous=uniqueIgnoringCase(entry.getValue())==null;
                if(wasAmbiguous)ambiguous++;
                String resolvedValue=CONSERVATIVE_WORDS.containsKey(entry.getKey())?CONSERVATIVE_WORDS.get(entry.getKey()):unique(entry.getValue());
                if(resolvedValue!=null) {
                    unambiguous.put(entry.getKey(),resolvedValue);
                    if(wasAmbiguous) {normalizedAmbiguous++;normalizedValues.put(entry.getKey(),resolvedValue);}
                }
                if(!entry.getKey().isEmpty())
                    starts.computeIfAbsent(entry.getKey().charAt(0),ignored->new ArrayList<>()).add(entry.getKey());
            }
            for(Map.Entry<String,String> word:CONSERVATIVE_WORDS.entrySet()) {
                unambiguous.put(word.getKey(),word.getValue());
                List<String> startsWith=starts.computeIfAbsent(word.getKey().charAt(0),ignored->new ArrayList<>());
                if(!startsWith.contains(word.getKey()))startsWith.add(word.getKey());
            }
            for(Map.Entry<Character,List<String>> entry:starts.entrySet()) {
                entry.getValue().sort((a,b)->a.length()!=b.length()?Integer.compare(b.length(),a.length()):a.compareTo(b));
                entry.setValue(Collections.unmodifiableList(entry.getValue()));
            }
            exact=Collections.unmodifiableMap(immutable);prefixes=Collections.unmodifiableMap(starts);templates=Collections.unmodifiableList(patterns);
            spearActual=actualSpear;spearTypical=typicalSpear;
            resolved=Collections.unmodifiableMap(unambiguous);
            normalized=Collections.unmodifiableMap(normalizedValues);
            Map<String,Integer> counts=new LinkedHashMap<>();counts.put("resource_pairs",pairs);counts.put("unique_chinese_strings",resourceStrings);
            counts.put("ambiguous_chinese_strings",ambiguous);counts.put("source_template_pairs",templatePairs);
            counts.put("normalized_ambiguous_strings",normalizedAmbiguous);
            counts.put("compiled_template_pairs",patterns.size());counts.put("unsupported_template_pairs",unsupported);
            counts.put("language_names",names.size());
            counts.put("hero_description_paragraphs",heroParagraphs);
            statistics=Collections.unmodifiableMap(counts);
        }
    }

    private static Dictionary load(ClassLoader loader) {
        Map<String,String> chinese=new TreeMap<>(),english=new TreeMap<>();
        for(String group:new String[]{"actors","items","journal","levels","misc","plants","scenes","ui","windows"}) {
            String base="messages/"+group+"/"+group;
            read(loader,base+"_zh.properties",chinese);read(loader,base+".properties",english);
        }
        return new Dictionary(chinese,english);
    }
    private static void read(ClassLoader loader,String path,Map<String,String> destination) {
        try(InputStream stream=loader.getResourceAsStream(path)) {
            if(stream==null)throw new IOException("Required localization resource is unavailable: "+path);
            Properties properties=new Properties();properties.load(new InputStreamReader(stream,StandardCharsets.UTF_8));
            for(String key:properties.stringPropertyNames())destination.put(key,properties.getProperty(key));
        }catch(IOException error){throw new IllegalStateException("DISPLAYED_TEXT_RESOURCES_UNAVAILABLE",error);}
    }
}
