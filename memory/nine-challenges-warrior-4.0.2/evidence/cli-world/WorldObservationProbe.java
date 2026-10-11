package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.badlogic.gdx.*;
import com.badlogic.gdx.files.FileHandle;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.RitualSiteRoom;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.gltextures.SmartTexture;
import com.watabou.gltextures.TextureCache;
import com.watabou.noosa.Game;
import com.watabou.noosa.Scene;
import com.watabou.noosa.Tilemap;
import java.lang.reflect.Field;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Fire;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.SkeletonKey;
import com.shatteredpixel.shatteredpixeldungeon.levels.*;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.BurningTrap;
import com.shatteredpixel.shatteredpixeldungeon.messages.*;
import com.watabou.utils.GameSettings;
import com.watabou.utils.SparseArray;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;

/** Isolated source audit: in-memory level and resource-only fake preferences; no engine/profile/GL. */
public final class WorldObservationProbe {
    static final class FixtureLevel extends Level {
        FixtureLevel() {
            width = 8; height = 5; length = 40;
            map = new int[length]; Arrays.fill(map, Terrain.EMPTY);
            heroFOV = new boolean[length]; visited = new boolean[length]; mapped = new boolean[length];
            passable = new boolean[length]; solid = new boolean[length]; avoid = new boolean[length];
            mobs=new HashSet<>(); blobs=new HashMap<>(); heaps=new SparseArray<>(); traps=new SparseArray<>(); plants=new SparseArray<>();
        }
        @Override protected boolean build() { return false; }
        @Override protected void createMobs() { }
        @Override protected void createItems() { }
    }
    static Object empty(Class<?> t) {
        if (t == boolean.class) return false; if(t == int.class) return 0;
        if (t == long.class) return 0L; if(t == float.class) return 0f;
        if (t == double.class) return 0d; return null;
    }
    static void resources(Path root) {
        Preferences prefs = (Preferences)Proxy.newProxyInstance(Preferences.class.getClassLoader(), new Class[]{Preferences.class}, (p,m,a)-> {
            if(m.getName().equals("contains")) return false;
            if(m.getName().equals("get")) return new HashMap<>();
            if(m.getName().startsWith("get") && a!=null && a.length==2) return a[1];
            if(m.getReturnType()==Preferences.class) return p;
            return empty(m.getReturnType());
        });
        Path assets=root.resolve("core/src/main/assets");
        Gdx.files=(Files)Proxy.newProxyInstance(Files.class.getClassLoader(),new Class[]{Files.class},(p,m,a)-> {
            if(m.getName().equals("internal")) return new FileHandle(assets.resolve((String)a[0]).toFile());
            if(m.getReturnType()==FileHandle.class) throw new AssertionError("Forbidden non-resource file access: "+m.getName());
            return empty(m.getReturnType());
        });
        Gdx.app=(Application)Proxy.newProxyInstance(Application.class.getClassLoader(),new Class[]{Application.class},(p,m,a)-> {
            if(m.getName().equals("getPreferences")) return prefs;
            if(m.getName().equals("getType")) return Application.ApplicationType.Desktop;
            return empty(m.getReturnType());
        });
        GameSettings.set(prefs); Messages.setup(Languages.ENGLISH);
    }
    static Map<String,Object> capture() { return PlayerObservation.capture(Dungeon.hero,Dungeon.level,"game"); }
    @SuppressWarnings("unchecked") static Map<String,Object> cell(Map<String,Object> snapshot,int pos) {
        for(Object raw:(List<?>)((Map<?,?>)snapshot.get("map")).get("cells")) {
            Map<String,Object> c=(Map<String,Object>)raw;
            if(((Number)c.get("cell")).intValue()==pos) return c;
        }
        return null;
    }
    static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    static final class FixtureGame extends Game {
        FixtureGame(Scene value) { super(Scene.class, Game.platform); scene=value; requestedReset=false; }
    }
    @SuppressWarnings("unchecked") static void customTerrainProbe(FixtureLevel level) throws Exception {
        Class<?> unsafe=Class.forName("sun.misc.Unsafe"); Field u=unsafe.getDeclaredField("theUnsafe"); u.setAccessible(true);
        SmartTexture texture=(SmartTexture)unsafe.getMethod("allocateInstance",Class.class).invoke(u.get(null),SmartTexture.class);
        texture.width=256; texture.height=256;
        Field cache=TextureCache.class.getDeclaredField("all"); cache.setAccessible(true);
        ((Map<Object,SmartTexture>)cache.get(null)).put(Assets.Environment.PRISON_QUEST,texture);
        level.customTiles=new ArrayList<>(); level.customTerrain=new ArrayList<>();
        GameScene scene=new GameScene(); new FixtureGame(scene);
        RitualSiteRoom.RitualMarker marker=new RitualSiteRoom.RitualMarker(); marker.pos(1,0);
        Tilemap visual=marker.create(); scene.add(visual); level.customTiles.add(marker);
        int pos=3+2*8; level.map[pos]=Terrain.CUSTOM_DECO_EMPTY; level.visited[pos]=level.heroFOV[pos]=true;
        check(marker.presentationFrameAt(2,2)>=0,"Custom native presentation must be attached and selected");
        Map<String,Object> observed=cell(capture(),pos);
        check(((Number)observed.get("terrain")).intValue()==Terrain.EMPTY,"Current unreviewed ritual source projects base floor");
        check(!observed.containsKey("description"),"Current default ritual source has no custom description");
        System.out.println(JsonCodec.encode(Map.of("check","ritual_marker_default_gap","selected_native_presentation",true,"native_inspection_name",marker.name(2,2),"native_inspection_description",marker.desc(2,2),"default_cell",observed)));
    }
    public static void main(String[] args) throws Exception {
        resources(Path.of(args[0])); Dungeon.depth=6;
        FixtureLevel level=new FixtureLevel(); Dungeon.level=level;
        Hero hero=new Hero(); hero.pos=9; Dungeon.hero=hero;
        level.visited[9]=level.heroFOV[9]=true;
        level.visited[10]=level.heroFOV[10]=true;
        Heap heap=new Heap(); heap.pos=10; heap.seen=true; heap.items.add(new Food()); level.heaps.put(10,heap);
        heap.type=Heap.Type.HEAP; String freeTitle=heap.title(); Object free=capture().get("visible_entities");
        heap.type=Heap.Type.FOR_SALE; String saleTitle=heap.title(); Object sale=capture().get("visible_entities");
        check(!freeTitle.equals(saleTitle),"Native sale title must differ");
        check(free.equals(sale),"Expected current default heap projection equivalence");
        System.out.println(JsonCodec.encode(Map.of("check","heap_sale_on_demand","native_free_title",freeTitle,"native_sale_title",saleTitle,"free_entities",free,"sale_entities",sale,"projection_equal",true)));
        level.visited[11]=true; level.map[11]=Terrain.SECRET_DOOR;
        level.visited[12]=true; level.map[12]=Terrain.SECRET_TRAP;
        level.visited[13]=true; level.map[13]=Terrain.DOOR;
        level.visited[14]=true; level.map[14]=Terrain.OPEN_DOOR;
        level.map[15]=Terrain.LOCKED_DOOR;
        Map<String,Object> snapshot=capture();
        check(((Number)cell(snapshot,11).get("terrain")).intValue()==Terrain.WALL,"Secret door hidden");
        check(((Number)cell(snapshot,12).get("terrain")).intValue()==Terrain.EMPTY,"Secret trap hidden");
        check(cell(snapshot,15)==null,"Unknown cell omitted");
        check(!cell(snapshot,13).get("terrain").equals(cell(snapshot,14).get("terrain")),"Door states differ");
        System.out.println(JsonCodec.encode(Map.of("check","known_map_and_secret_gates","secret_door",cell(snapshot,11),"secret_trap",cell(snapshot,12),"door",cell(snapshot,13),"open_door",cell(snapshot,14),"unknown_omitted",true)));
        Fire fire=new Fire(); fire.cur=new int[40]; fire.cur[11]=1; fire.cur[10]=1; fire.volume=2; level.blobs.put(Fire.class,fire);
        SkeletonKey.KeyWall wall=new SkeletonKey.KeyWall(); wall.cur=new int[40]; wall.cur[12]=1; wall.volume=1; level.blobs.put(SkeletonKey.KeyWall.class,wall);
        snapshot=capture();
        check(((List<?>)cell(snapshot,11).get("environment")).isEmpty(),"Unseen ordinary fire omitted");
        check(!((List<?>)cell(snapshot,10).get("environment")).isEmpty(),"Visible fire shown");
        check(!((List<?>)cell(snapshot,12).get("environment")).isEmpty(),"Known alwaysVisible keywall shown");
        System.out.println(JsonCodec.encode(Map.of("check","environment_gates","visible_fire",cell(snapshot,10).get("environment"),"unseen_fire",cell(snapshot,11).get("environment"),"known_keywall",cell(snapshot,12).get("environment"))));
        BurningTrap trap=new BurningTrap(); trap.pos=14; trap.visible=true; trap.active=true; level.traps.put(14,trap);
        String active=trap.desc(); trap.active=false; String inactive=trap.desc();
        check(!active.equals(inactive),"Inactive trap description survives");
        System.out.println(JsonCodec.encode(Map.of("check","trap_inactive_description","native_active",active,"native_inactive",inactive,"entities",capture().get("visible_entities"))));
        customTerrainProbe(level);
        System.out.println("PASS: 5 isolated observation checks. No engine launch, actor step, GL context or profile access; custom terrain used synthetic texture metadata and an unstarted scene.");
    }
}
