package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.game.text.TextProvenance;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.watabou.noosa.Camera;
import com.watabou.noosa.RenderedText;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** Explicit resource lookup at composition time; never reverse-matches rendered text. */
final class ResourceTextFixture {
    private static final Map<String,Properties> RESOURCES=new LinkedHashMap<>();
    private ResourceTextFixture() {}
    static String source(String key,Object... args) { return source(Languages.CHI_SMPL,key,args); }
    static String source(Languages language,String key,Object... args) {
        String template=resource(language,key);
        String shown=args.length==0?template:String.format(new Locale(language.code()),template,args);
        return TextProvenance.INSTANCE.onTextResource(shown,key,language.code(),args);
    }
    static String resource(Languages language,String key) {
        String group=key.substring(0,key.indexOf('.'));
        if (!java.util.List.of("actors","items","journal","levels","plants","scenes","ui","windows").contains(group))group="misc";
        String value=properties(group,language).getProperty(key);
        if(value==null)value=properties(group,Languages.ENGLISH).getProperty(key);
        if(value==null)throw new AssertionError("Missing fixture resource "+key);
        return value;
    }
    static Properties properties(String group,Languages language) {
        String path="messages/"+group+"/"+group+(language==Languages.ENGLISH?"":"_"+language.code())+".properties";
        return RESOURCES.computeIfAbsent(path,p->{
            Properties values=new Properties();
            try(InputStream stream=ResourceTextFixture.class.getClassLoader().getResourceAsStream(p)) {
                if(stream!=null)values.load(new InputStreamReader(stream,StandardCharsets.UTF_8));
            }catch(Exception failure){throw new AssertionError(failure);}
            return values;
        });
    }
    static String literal(String text) { return TextProvenance.INSTANCE.onTextOperation("literal",text,text); }
    static String join(String... parts) { return TextProvenance.INSTANCE.onTextOperation("concat",String.join("",parts),(Object[])parts); }
    static String english(String source) { return (String)PublicEnglishProjection.copy(map("text",source)).get("text"); }
    static String status(String source) { return (String)PublicEnglishProjection.presentation(PublicEnglishProjection.copy(map("text",source))).get("status"); }
    static RenderedTextBlock laidOut(String source) {
        return new LaidOutBlock(source);
    }
    private static final class LaidOutBlock extends RenderedTextBlock {
        LaidOutBlock(String source) {
            super(6); setHightlighting(false); text=source;
            camera=new Camera(0,0,1000,1000,1);
            RenderedText word=new VisibleWord(source); words.add(word); add(word);
        }
        @Override protected void layout() {}
    }
    private static final class VisibleWord extends RenderedText {
        final String source;
        VisibleWord(String source) { this.source=source; x=10;y=10;width=100;height=8; }
        @Override public String text() { return source; }
        @Override public boolean hasRenderableText() { return source!=null&&!source.isEmpty(); }
        @Override public void draw() {}
    }
}
