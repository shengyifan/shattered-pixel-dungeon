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
    private final Dictionary dictionary;

    /** The immutable asset index is built once per classloader, outside subsequent translations. */
    public DisplayedTextEnglish() { this(DefaultDictionary.VALUE); }
    private DisplayedTextEnglish(Dictionary dictionary) { this.dictionary = dictionary; }
    public static DisplayedTextEnglish fromResources(Map<String,String> chinese, Map<String,String> english) {
        return new DisplayedTextEnglish(new Dictionary(chinese, english));
    }
    public static DisplayedTextEnglish fromClassLoader(ClassLoader loader) { return new DisplayedTextEnglish(load(loader)); }

    public String translate(String displayed) {
        if (displayed == null) return null;
        String language=dictionary.languageNames.get(displayed.toLowerCase(Locale.ROOT));
        if(language!=null)return language;
        if (!containsNonLatinText(displayed)) return displayed;
        if (displayed.length() > MAX_TEXT) throw unavailable(displayed, "displayed_text_too_long");
        if(dictionary.exact.containsKey(displayed)) {
            String exact=dictionary.resolved.get(displayed);
            if(exact==null)throw unavailable(displayed,"ambiguous_resource_translation");
            return exact;
        }
        try {
            String translated = translate(displayed, new Context(), 0);
            if (translated == null || containsNonLatinText(translated)) throw unavailable(displayed, "no_safe_resource_translation");
            return translated;
        } catch (LimitReached limit) { throw unavailable(displayed, "translation_work_limit"); }
    }

    public VisibleText translateVisible(String displayed, boolean clipped) {
        return translateVisibleInScene(displayed,clipped,null);
    }

    /** Only reviewed, already-public scene names restrict the resource search. No live object is inspected. */
    public String translateInScene(String displayed,String publicScene) {
        String scope=PUBLIC_SCENE_SCOPES.get(publicScene);
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

    public Map<String,Integer> statistics() { return dictionary.statistics; }

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
        final Map<String,String> translated=new HashMap<>();
        final Set<String> unavailable=new TreeSet<>();
        void spend() { if (++work > MAX_WORK) throw new LimitReached(); }
    }

    private String translate(String text, Context context, int depth) {
        String language=dictionary.languageNames.get(text.toLowerCase(Locale.ROOT));
        if(language!=null)return language;
        if (!containsNonLatinText(text)) return text;
        context.spend();
        if (depth > MAX_DEPTH || context.unavailable.contains(text)) return null;
        if (context.translated.containsKey(text)) return context.translated.get(text);
        Set<String> exact=dictionary.exact.get(text);
        if (exact != null) return remember(text, unique(exact), context);
        String trimmed=text.trim();
        if(!trimmed.equals(text)) {
            int start=text.indexOf(trimmed);
            String translated=translate(trimmed,context,depth+1);
            return remember(text,translated==null?null:text.substring(0,start)+translated+text.substring(start+trimmed.length()),context);
        }

        Set<String> candidates=new TreeSet<>();
        for (Template template : dictionary.templates) {
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

        // Concatenated resource paragraphs are separate visible units. This only
        // divides supplied text; it never fetches the unseen rest of a resource.
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
        if(!isNonLatinText(text.codePointAt(offset))) {
            int end=offset;
            while(end<text.length() && !isNonLatinText(text.codePointAt(end))) end+=Character.charCount(text.codePointAt(end));
            String rest=compose(text,end,context,memo,segments+1);
            String result=rest==null?null:join(text.substring(offset,end),rest);
            memo.put(offset,result);return result;
        }
        List<String> entries=dictionary.prefixes.get(text.charAt(offset));
        if(entries==null) {memo.put(offset,null);return null;}
        // Prefer the longest complete resource unit at this boundary. Equal source
        // strings retain all English candidates and cannot be resolved by insertion order.
        for(String source:entries) {
            if(!text.startsWith(source,offset))continue;
            String translated=unique(dictionary.exact.get(source));
            if(translated==null) {memo.put(offset,null);return null;}
            String rest=compose(text,offset+source.length(),context,memo,segments+1);
            if(rest!=null) {String result=join(translated,rest);memo.put(offset,result);return result;}
        }
        memo.put(offset,null);return null;
    }

    private static String remember(String source,String translated,Context context) {
        if(translated==null)context.unavailable.add(source);else context.translated.put(source,translated);
        return translated;
    }
    private static String unique(Set<String> values) {
        if(values==null || values.isEmpty())return null;
        String first=null,folded=null;
        for(String value:values) {
            if(containsNonLatinText(value))return null;
            if(first==null) {first=value;folded=value.toLowerCase(Locale.ROOT);}
            else if(!folded.equals(value.toLowerCase(Locale.ROOT)))return null;
        }
        return first;
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
        Template(String chinese,String english) {
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
        final Map<Character,List<String>> prefixes;
        final List<Template> templates;
        final Map<String,Integer> statistics;
        final Map<String,String> languageNames;
        final Map<String,Map<String,Set<String>>> sceneEntries;
        Dictionary(Map<String,String> chinese,Map<String,String> english) {
            Map<String,Set<String>> entries=new TreeMap<>();List<Template> patterns=new ArrayList<>();
            Map<String,Map<String,Set<String>>> byScene=new TreeMap<>();
            int pairs=0,templatePairs=0,unsupported=0;
            for(String key:new TreeSet<>(chinese.keySet())) {
                String source=chinese.get(key),target=english.get(key);
                if(source==null||target==null||!containsChinese(source))continue;
                pairs++;entries.computeIfAbsent(source,ignored->new TreeSet<>()).add(target);
                if(key.startsWith("scenes.")) {
                    int dot=key.indexOf('.',7);
                    String scope=dot<0?"":key.substring(7,dot);
                    if(PUBLIC_SCENE_SCOPES.containsValue(scope))byScene.computeIfAbsent(scope,ignored->new TreeMap<>())
                            .computeIfAbsent(source,ignored->new TreeSet<>()).add(target);
                }
                if(!new Printf(source).arguments.isEmpty()) {
                    templatePairs++;
                    try {patterns.add(new Template(source,target));}catch(IllegalArgumentException mismatch){unsupported++;}
                }
            }
            int resourceStrings=entries.size();
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
            Map<String,Set<String>> immutable=new LinkedHashMap<>();Map<String,String> unambiguous=new LinkedHashMap<>();
            Map<Character,List<String>> starts=new HashMap<>();
            int ambiguous=0;
            for(Map.Entry<String,Set<String>> entry:entries.entrySet()) {
                immutable.put(entry.getKey(),Collections.unmodifiableSet(entry.getValue()));
                String resolvedValue=unique(entry.getValue());
                if(resolvedValue==null)ambiguous++;else unambiguous.put(entry.getKey(),resolvedValue);
                if(!entry.getKey().isEmpty()&&isChinese(entry.getKey().codePointAt(0)))
                    starts.computeIfAbsent(entry.getKey().charAt(0),ignored->new ArrayList<>()).add(entry.getKey());
            }
            for(Map.Entry<Character,List<String>> entry:starts.entrySet()) {
                entry.getValue().sort((a,b)->a.length()!=b.length()?Integer.compare(b.length(),a.length()):a.compareTo(b));
                entry.setValue(Collections.unmodifiableList(entry.getValue()));
            }
            exact=Collections.unmodifiableMap(immutable);prefixes=Collections.unmodifiableMap(starts);templates=Collections.unmodifiableList(patterns);
            resolved=Collections.unmodifiableMap(unambiguous);
            Map<String,Integer> counts=new LinkedHashMap<>();counts.put("resource_pairs",pairs);counts.put("unique_chinese_strings",resourceStrings);
            counts.put("ambiguous_chinese_strings",ambiguous);counts.put("source_template_pairs",templatePairs);
            counts.put("compiled_template_pairs",patterns.size());counts.put("unsupported_template_pairs",unsupported);
            counts.put("language_names",names.size());
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
