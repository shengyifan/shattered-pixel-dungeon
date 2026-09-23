package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndUpgrade;
import com.shatteredpixel.shatteredpixeldungeon.control.game.text.TextProvenance;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.ui.GameLog;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.noosa.Game;
import com.watabou.noosa.Camera;
import com.watabou.noosa.RenderedText;
import com.watabou.noosa.RuntimeObserver;
import com.watabou.noosa.Scene;
import com.watabou.utils.DeviceCompat;
import com.watabou.utils.Random;
import com.watabou.utils.Signal;
import org.junit.jupiter.api.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real GameLog merge/cull/layout and draw hooks with test text blocks that do not call OpenGL. */
class GameLogDisplayTest {
    private Game previousGame;private RuntimeObserver previousObserver;private String previousRun;
    private Object previousEntries,previousPending,previousSceneClass;
    private Signal<String> previousSignal;
    private Scene scene;private GameLog log;
    private final List<List<RuntimeObserver.LogEntry>> snapshots=new ArrayList<>();
    private final List<String> contexts=new ArrayList<>();

    @BeforeAll static void assets(){GameSnapshotterTest.resourceOnlyRuntime();}
    @BeforeEach void setup()throws Exception{
        previousGame=Game.instance;previousObserver=Game.observer;previousRun=Dungeon.runId;previousSignal=GLog.update;
        previousEntries=field(GameLog.class,"entries").get(null);previousPending=field(GameLog.class,"textsToAdd").get(null);
        previousSceneClass=field(Game.class,"sceneClass").get(null);
        field(GameLog.class,"entries").set(null,new ArrayList<>());field(GameLog.class,"textsToAdd").set(null,new ArrayList<>());
        GLog.update=new Signal<>();Dungeon.runId="a";scene=new Scene();new TestGame(scene);
        TextProvenance.INSTANCE.clear();Messages.setup(Languages.ENGLISH);
        Game.observer=new RuntimeObserver(){
            @Override public String onTextResource(String text,String key,String language,Object[] args){return TextProvenance.INSTANCE.onTextResource(text,key,language,args);}
            @Override public String onTextResource(String text,String key,String language,Object[] args,String guiTemplate){return TextProvenance.INSTANCE.onTextResource(text,key,language,args,guiTemplate);}
            @Override public String onTextOperation(String operation,String text,Object...operands){return TextProvenance.INSTANCE.onTextOperation(operation,text,operands);}
            @Override public void onTextBound(Object owner,String text){TextProvenance.INSTANCE.onTextBound(owner,text);}
            @Override public void onTextReleased(Object owner){TextProvenance.INSTANCE.onTextReleased(owner);}
            @Override public void onGameLog(String context,List<LogEntry> values){contexts.add(context);snapshots.add(values);}
        };
        log=new GameLog();scene.add(log);log.setRect(0,100,100,0);
    }
    @AfterEach void restore()throws Exception{
        Game.instance=previousGame;Game.observer=previousObserver;Dungeon.runId=previousRun;GLog.update=previousSignal;
        field(GameLog.class,"entries").set(null,previousEntries);field(GameLog.class,"textsToAdd").set(null,previousPending);
        field(Game.class,"sceneClass").set(null,previousSceneClass);
        TextProvenance.INSTANCE.clear();
    }

    @Test void onlyPostCullMergedEntriesAreNotifiedAndOnlyAfterDraw()throws Exception{
        int limit=SPDSettings.interfaceSize()>0?5:3;
        add("NEVER_DRAWN_TRIMMED_ONE",limit);add("NEVER_DRAWN_TRIMMED_TWO",limit);add("visible earlier",limit);add("start",1);
        GLog.i("first");GLog.i("second");
        assertTrue(snapshots.isEmpty());
        log.update();
        assertTrue(snapshots.isEmpty(),"Update is not proof that a frame was drawn");
        log.draw();
        assertEquals(List.of("visible earlier","start first second"),texts(snapshots.get(0)));
        assertEquals(CharSprite.DEFAULT,snapshots.get(0).get(1).color);
        assertEquals(List.of("a"),contexts);
        assertThrows(UnsupportedOperationException.class,()->snapshots.get(0).clear());
    }

    @Test void drawDoesNotDrainPendingSignalsMutateTextOrAdvanceGameRandom()throws Exception{
        FakeText drawn=add("already displayed",1);
        GLog.i("QUEUED_NOT_YET_DISPLAYED");
        DeviceCompat.log("SYSTEM_ONLY","PRIVATE_CONSOLE_SENTINEL");
        List<?> pending=new ArrayList<>((List<?>)field(GameLog.class,"textsToAdd").get(null));
        byte[] random=Random.exportState();
        log.draw();
        assertEquals(List.of("already displayed"),texts(snapshots.get(0)));
        assertEquals(pending,field(GameLog.class,"textsToAdd").get(null));
        assertEquals("already displayed",drawn.text());
        assertArrayEquals(random,Random.exportState());
    }

    @Test void hiddenInactiveDetachedAndOldRunControlsCannotPublish()throws Exception{
        add("visible",1);
        log.visible=false;log.draw();log.visible=true;
        log.active=false;log.draw();log.active=true;
        scene.visible=false;log.draw();scene.visible=true;
        scene.active=false;log.draw();scene.active=true;
        scene.remove(log);log.draw();scene.add(log);
        Dungeon.runId="b";log.draw();
        assertTrue(snapshots.isEmpty());
        Dungeon.runId="a";log.draw();assertEquals(List.of("a"),contexts);
    }

    @Test void viewportProjectionOmitsOffscreenAndPartlyClippedWordsWithoutCachingCamera()throws Exception{
        log.camera=new Camera(0,0,100,60,1);
        FakeWord top=new FakeWord("OFFSCREEN_TOP_SECRET",0,-20,80,10);
        FakeWord visible=new FakeWord("VISIBLE",0,10,50,10);
        FakeWord edge=new FakeWord("PARTIAL_WORD_SECRET",90,10,30,10);
        FakeWord next=new FakeWord("NEXT",0,30,30,10);
        LaidOutBlock block=new LaidOutBlock("OFFSCREEN_TOP_SECRET VISIBLE PARTIAL_WORD_SECRET NEXT",top,visible,edge,next);
        attach(block);
        RenderedTextBlock.VisibleText fragment=block.visibleTextFragment();
        assertEquals("VISIBLE\nNEXT",fragment.text);assertTrue(fragment.clipped);assertTrue(fragment.visible);
        assertNull(top.camera);assertNull(visible.camera);assertNull(edge.camera);assertNull(block.camera);
        String publicUi=UiDrawFixture.capture(new UiBridge(()->scene)).describeUi().toString();
        assertTrue(publicUi.contains("Partially displayed text"));assertTrue(publicUi.contains("clipped=true"));
        assertFalse(publicUi.contains("VISIBLE"),"Partial source text is not guessed or mixed into English output");
        assertFalse(publicUi.contains("OFFSCREEN_TOP_SECRET"));assertFalse(publicUi.contains("PARTIAL_WORD_SECRET"));
        assertTrue(block.text().contains("OFFSCREEN_TOP_SECRET"));assertNull(visible.camera);
        log.draw();
        assertEquals("VISIBLE\nNEXT",snapshots.get(0).get(0).text);assertTrue(snapshots.get(0).get(0).clipped);
    }

    @Test void fullyOffscreenBlocksDoNotRevealTheirExistenceOrColor()throws Exception{
        log.camera=new Camera(0,0,100,60,1);
        attach(new LaidOutBlock("WHOLE_HIDDEN_ENTRY",new FakeWord("WHOLE_HIDDEN_ENTRY",0,-30,90,10)));
        assertTrue(((List<?>)UiDrawFixture.capture(new UiBridge(()->scene)).describeUi().get("controls")).isEmpty());
        log.draw();assertTrue(snapshots.get(0).isEmpty());
    }

    @Test void nonLogInspectionTextUsesTheSameVisibilityGateWithoutCachingCamera(){
        scene.camera=new Camera(0,0,100,60,1);
        String source=Messages.get(WndUpgrade.class,"title");
        FakeWord word=new FakeWord(source,0,-30,90,10);
        LaidOutBlock detail=new LaidOutBlock(source,word);
        scene.add(detail);
        assertTrue(((List<?>)UiDrawFixture.capture(new UiBridge(()->scene)).describeUi().get("controls")).isEmpty());
        word.y=10;
        assertTrue(UiDrawFixture.capture(new UiBridge(()->scene)).describeUi().toString().contains("Upgrade an Item"));
        assertNull(word.camera);assertNull(detail.camera);
    }

    @Test void invisiblePrefixLengthEntryCountAndColorsDoNotChangePublicShapeOrIds()throws Exception{
        log.camera=new Camera(0,0,100,60,1);
        attach(new LaidOutBlock("HIDDEN_A VISIBLE",new FakeWord("HIDDEN_A",0,-50,30,10),new FakeWord("VISIBLE",0,10,50,10)));
        Map<String,Object> first=UiDrawFixture.capture(new UiBridge(()->scene)).describeUi();log.draw();
        log.clear();
        for(int i=0;i<5;i++){
            LaidOutBlock hidden=new LaidOutBlock("HIDDEN_ENTRY_"+i,new FakeWord("HIDDEN_ENTRY_"+i,0,-100-i*20,80,10));
            attach(hidden);
            @SuppressWarnings("unchecked") Map<RenderedTextBlock,Integer> colors=(Map<RenderedTextBlock,Integer>)field(GameLog.class,"entryColors").get(log);
            colors.put(hidden,0xff0000+i);
        }
        attach(new LaidOutBlock("MUCH_LONGER_HIDDEN_PREFIX ANOTHER_HIDDEN_WORD VISIBLE",
                new FakeWord("MUCH_LONGER_HIDDEN_PREFIX",0,-80,90,10),new FakeWord("ANOTHER_HIDDEN_WORD",0,-50,90,10),new FakeWord("VISIBLE",0,10,50,10)));
        Map<String,Object> second=UiDrawFixture.capture(new UiBridge(()->scene)).describeUi();log.draw();
        // Fresh bridges assign the same visible ID as well as the same count: hidden glyphs/entries
        // must not reserve IDs or leave observable holes in the public control sequence.
        assertEquals(first,second);assertEquals(1,((List<?>)second.get("controls")).size());
        assertEquals(snapshots.get(0),snapshots.get(1));assertEquals(1,snapshots.get(1).size());
        assertFalse(second.toString().contains("HIDDEN"));
    }

    @Test void logDisplayChangesDoNotExpireIntentButOtherUiTextRemainsProtected()throws Exception{
        FakeText block=add(Messages.get(WndUpgrade.class,"title"),1);UiBridge bridge=new UiBridge(()->scene);
        String before=bridge.intentSignature();block.text(Messages.get(WndUpgrade.class,"desc"));
        assertEquals(before,bridge.intentSignature());assertTrue(UiDrawFixture.capture(bridge).describeUi().toString().contains("Upgrading an item permanently improves it:"));
        FakeText prompt=new FakeText(Messages.get(WndUpgrade.class,"upgrade"),1);scene.add(prompt);
        String promptBefore=bridge.intentSignature();prompt.text(Messages.get(WndUpgrade.class,"back"));
        assertNotEquals(promptBefore,bridge.intentSignature());
    }

    @SuppressWarnings("unchecked") private void attach(RenderedTextBlock block)throws Exception{
        log.add(block);((Map<RenderedTextBlock,Integer>)field(GameLog.class,"entryColors").get(log)).put(block,CharSprite.DEFAULT);
    }

    @SuppressWarnings("unchecked") private FakeText add(String text,int lines)throws Exception{
        FakeText block=new FakeText(text,lines);log.add(block);
        ((Map<RenderedTextBlock,Integer>)field(GameLog.class,"entryColors").get(log)).put(block,CharSprite.DEFAULT);
        Class<?> entry=Class.forName(GameLog.class.getName()+"$Entry");Constructor<?> constructor=entry.getDeclaredConstructor(String.class,int.class);constructor.setAccessible(true);
        ((List<Object>)field(GameLog.class,"entries").get(null)).add(constructor.newInstance(text,CharSprite.DEFAULT));
        field(GameLog.class,"lastEntry").set(log,block);field(GameLog.class,"lastColor").setInt(log,CharSprite.DEFAULT);return block;
    }
    private static List<String> texts(List<RuntimeObserver.LogEntry> entries){List<String> result=new ArrayList<>();for(RuntimeObserver.LogEntry e:entries)result.add(e.text);return result;}
    private static Field field(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static final class FakeText extends RenderedTextBlock{
        FakeText(String value,int lines){super(6);text=value;nLines=lines;height=6*lines;Game.observer.onTextBound(this,value);}
        @Override public void text(String value){text=value;Game.observer.onTextBound(this,value);}
        @Override public void maxWidth(int width){}
        @Override public synchronized void setHightlighting(boolean enabled){}
        @Override public synchronized VisibleText visibleTextFragment(){return new VisibleText(text,false,true);}
        @Override protected void layout(){}
        @Override public void draw(){}
    }
    private static final class FakeWord extends RenderedText{
        final String label;
        FakeWord(String label,float x,float y,float width,float height){super();this.label=label;this.x=x;this.y=y;this.width=width;this.height=height;}
        @Override public String text(){return label;}
        @Override public boolean hasRenderableText(){return label!=null&&!label.isEmpty();}
        @Override public void draw(){}
    }
    private static final class LaidOutBlock extends RenderedTextBlock{
        LaidOutBlock(String source,RenderedText...tokens){super(6);text=source;Game.observer.onTextBound(this,source);for(RenderedText token:tokens){words.add(token);add(token);}}
        @Override protected void layout(){}
    }
    private static final class TestGame extends Game{TestGame(Scene current){super(Scene.class,Game.platform);scene=current;requestedReset=false;}}
}
