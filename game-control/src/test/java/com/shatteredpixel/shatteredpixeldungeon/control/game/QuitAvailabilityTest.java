package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.ProtocolException;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.TitleScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.InventoryPane;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndBag;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;
import com.watabou.noosa.Scene;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real advertised action list and both guards without opening a renderer or saving files. */
class QuitAvailabilityTest {
    private Game previousGame;
    private Hero previousHero;
    private Object previousGameScene,previousSelector,previousSceneClass;
    private GameController controller;

    @BeforeAll static void assets(){GameSnapshotterTest.resourceOnlyRuntime();}
    @BeforeEach void setup()throws Exception{
        previousGame=Game.instance;previousHero=Dungeon.hero;
        previousGameScene=field(GameScene.class,"scene").get(null);
        previousSelector=field(GameScene.class,"cellSelector").get(null);
        previousSceneClass=field(Game.class,"sceneClass").get(null);
        Dungeon.hero=new Hero();Dungeon.hero.ready=true;
        field(GameScene.class,"scene").set(null,null);
        field(GameScene.class,"cellSelector").set(null,null);
        controller=new GameController(null,"menu:test",failure->{throw new AssertionError(failure);});
    }
    @AfterEach void restore()throws Exception{
        Game.instance=previousGame;Dungeon.hero=previousHero;
        field(GameScene.class,"scene").set(null,previousGameScene);
        field(GameScene.class,"cellSelector").set(null,previousSelector);
        field(Game.class,"sceneClass").set(null,previousSceneClass);
    }

    @Test void mainMenuAdvertisesAndPermitsNormalQuitDespiteRetainedGameWindow()throws Throwable{
        GameScene retained=new GameScene();retained.add(window());
        field(GameScene.class,"scene").set(null,retained);
        new TestGame(new TitleScene());
        assertAdvertised(true);invoke("validateAction");
        invoke("perform");assertTrue(controller.exiting());
        assertEquals(1,retained.childrenSnapshot().size());
    }

    @Test void ordinaryGameAdvertisesQuitAndPassesTheSamePreflight()throws Throwable{
        gameScene();
        assertAdvertised(true);invoke("validateAction");
        assertFalse(controller.exiting());assertNull(controller.pollSave());
    }

    @Test void modalChoiceIsNotAdvertisedAndNeitherGuardClosesIt()throws Throwable{
        GameScene scene=gameScene();Window window=window();scene.add(window);
        assertBlocked();
        assertSame(scene,window.parent);assertEquals(1,scene.childrenSnapshot().size());
    }

    @Test void itemSelectionIsNotAdvertisedAndNeitherGuardSelectsOrCancels()throws Throwable{
        GameScene scene=gameScene();InventoryPane pane=allocate(InventoryPane.class);initializeGroup(pane);
        AtomicInteger selections=new AtomicInteger();
        WndBag.ItemSelector selector=new WndBag.ItemSelector(){
            @Override public String textPrompt(){return "Choose an item";}
            @Override public boolean itemSelectable(Item item){return true;}
            @Override public void onSelect(Item item){selections.incrementAndGet();}
        };
        field(InventoryPane.class,"selector").set(pane,selector);
        field(GameScene.class,"inventory").set(scene,pane);scene.add(pane);
        assertBlocked();assertSame(selector,pane.getSelector());assertEquals(0,selections.get());
    }

    @Test void targetSelectionIsNotAdvertisedAndNeitherGuardChoosesACell()throws Throwable{
        GameScene scene=gameScene();CellSelector cells=allocate(CellSelector.class);initializeGizmo(cells);cells.enabled=true;
        AtomicInteger selections=new AtomicInteger();
        CellSelector.Listener target=new CellSelector.Listener(){
            @Override public void onSelect(Integer cell){selections.incrementAndGet();}
            @Override public String prompt(){return "Choose a target";}
        };
        cells.listener=target;field(GameScene.class,"cellSelector").set(null,cells);scene.add(cells);
        assertBlocked();assertSame(target,cells.listener);assertEquals(0,selections.get());
    }

    private GameScene gameScene()throws Exception{
        GameScene scene=new GameScene();field(GameScene.class,"scene").set(null,scene);new TestGame(scene);return scene;
    }
    private void assertBlocked()throws Throwable{
        assertAdvertised(false);
        ProtocolException validation=assertThrows(ProtocolException.class,()->invoke("validateAction"));
        ProtocolException execution=assertThrows(ProtocolException.class,()->invoke("perform"));
        assertEquals("ACTION_UNAVAILABLE",validation.code);assertEquals(validation.code,execution.code);
        assertFalse(controller.exiting());assertNull(controller.pollSave());
    }
    private void assertAdvertised(boolean expected)throws Throwable{
        Method capture=GameController.class.getDeclaredMethod("capture",boolean.class);capture.setAccessible(true);
        GameController.State state=(GameController.State)call(capture,controller,false);
        assertEquals(expected,state.actions.stream().anyMatch(action->"app.quit".equals(action.get("action"))));
    }
    private void invoke(String name)throws Throwable{
        Method method=GameController.class.getDeclaredMethod(name,Map.class);method.setAccessible(true);call(method,controller,Map.of("action","app.quit"));
    }
    private static Object call(Method method,Object target,Object...args)throws Throwable{
        try{return method.invoke(target,args);}catch(InvocationTargetException error){throw error.getCause();}
    }
    private static Window window()throws Exception{Window window=allocate(Window.class);initializeGroup(window);return window;}
    private static void initializeGizmo(Gizmo gizmo){gizmo.exists=true;gizmo.alive=true;gizmo.visible=true;gizmo.active=true;}
    private static void initializeGroup(Group group)throws Exception{initializeGizmo(group);field(Group.class,"members").set(group,new ArrayList<Gizmo>());}
    private static <T>T allocate(Class<T> type)throws Exception{
        Field singleton=field(sun.misc.Unsafe.class,"theUnsafe");return type.cast(((sun.misc.Unsafe)singleton.get(null)).allocateInstance(type));
    }
    private static Field field(Class<?> type,String name)throws Exception{Field field=type.getDeclaredField(name);field.setAccessible(true);return field;}
    private static final class TestGame extends Game{
        TestGame(Scene current){super(Scene.class,Game.platform);scene=current;requestedReset=false;}
    }
}
