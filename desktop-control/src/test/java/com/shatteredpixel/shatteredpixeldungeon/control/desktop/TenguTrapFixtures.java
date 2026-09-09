package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Tengu;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfMagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.PrisonBossLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.TenguDartTrap;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.Game;
import com.watabou.utils.Rect;

import java.lang.reflect.Field;

/** Initial states only. Original Tengu.damage/jump owns every measured replacement pattern. */
final class TenguTrapFixtures {
    static void prepare(boolean hidden, Hero hero) {
        if (hidden) {
            int width= Dungeon.level.width();
            for(int y=1;y<Dungeon.level.height()-1;y++) {
                int cell=y*width+hero.pos%width+1;
                Level.set(cell,Terrain.WALL); GameScene.updateMap(cell);
            }
            int cell=hero.pos+3;
            Level.set(cell,Terrain.SECRET_TRAP);
            Dungeon.level.setTrap(new TenguDartTrap().hide(),cell);
            Dungeon.observe();
            if(Dungeon.level.heroFOV[cell])throw new IllegalStateException("Hidden pattern was visible");
            PrisonBossLevel.FadingTraps pattern=new PrisonBossLevel.FadingTraps();
            pattern.setCoveringArea(new Rect(cell%width,cell/width,cell%width+1,cell/width+1));
            GameScene.add(pattern,false); Dungeon.level.customTiles.add(pattern);
            return;
        }
        Game.switchScene(GameScene.class,new Game.SceneChangeCallback() {
            @Override public void beforeCreate() {
                try {
                    Dungeon.depth=10; Dungeon.branch=0;
                    PrisonBossLevel level=(PrisonBossLevel)Dungeon.newLevel();
                    set(level,"state",PrisonBossLevel.State.FIGHT_START);
                    Tengu boss=(Tengu)get(level,"tengu");
                    boss.state=boss.HUNTING; boss.pos=12+29*level.width();
                    level.mobs.clear(); level.mobs.add(boss);
                    level.heaps.clear(); level.traps.clear(); level.plants.clear(); level.blobs.clear(); level.locked=true;
                    WandOfMagicMissile wand=new WandOfMagicMissile(); wand.level(20);wand.identify(false);wand.collect();
                    hero.ready=true;hero.curAction=hero.lastAction=null;
                    Dungeon.switchLevel(level,8+25*level.width());
                    Item.updateQuickslot();
                } catch(Exception error) {throw new IllegalStateException("Tengu trap fixture setup failed",error);}
            }
            @Override public void afterCreate() { }
        });
    }
    private static Object get(Object target,String name)throws Exception {Field f=PrisonBossLevel.class.getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void set(Object target,String name,Object value)throws Exception {Field f=PrisonBossLevel.class.getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
}
