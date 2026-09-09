package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.control.game.GameController;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfFireblast;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfMagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Spear;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndInfoItem;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import java.util.Arrays;
import java.util.Map;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** Test-only legal initial states; original windows/actions are opened solely through public CLI. */
final class InspectedItemFixtures {
    private static String scenario;
    private static Spear subject;
    private static boolean identifiedAfterBody;
    static boolean supports(String name){return Arrays.asList("known","unknown","old-body","floor-sale","containers-a","containers-b").contains(name);}
    static void prepare(String name,Hero hero) {
        scenario=name;subject=new Spear();subject.level(7);subject.levelKnown=name.equals("known");subject.cursedKnown=false;
        hero.belongings.weapon=subject;subject.activate(hero);
        for(Heap heap:Dungeon.level.heaps.valueList())heap.destroy();
        if(name.equals("floor-sale")) {
            add(new Spear().identify(false),hero.pos+1,Heap.Type.HEAP);
            Spear sale=new Spear();sale.level(3);add(sale,hero.pos-1,Heap.Type.FOR_SALE);
        } else if(name.startsWith("containers-")) {
            Heap.Type[] types={Heap.Type.CHEST,Heap.Type.LOCKED_CHEST,Heap.Type.CRYSTAL_CHEST,Heap.Type.TOMB,Heap.Type.SKELETON,Heap.Type.REMAINS};
            int width=Dungeon.level.width();int[] offsets={1,-1,width,-width,width+1,width-1};
            for(int i=0;i<types.length;i++) {
                Item hidden=name.endsWith("a")?new WandOfMagicMissile():new WandOfFireblast();
                hidden.level(name.endsWith("a")?2:9);hidden.cursed=name.endsWith("b");
                add(hidden,hero.pos+offsets[i],types[i]);
            }
        }
    }
    private static void add(Item item,int cell,Heap.Type type) {
        Heap heap=Dungeon.level.drop(item,cell);heap.type=type;heap.sprite.view(heap);GameScene.updateMap(cell);
    }
    static void observed(GameController.State state) {
        if(!"old-body".equals(scenario)||identifiedAfterBody||state==null||Dungeon.hero==null
                ||!Dungeon.hero.ready||!Actor.isYielded()||!(Game.scene() instanceof GameScene))return;
        for(Gizmo child:Game.scene().childrenSnapshot())if(child instanceof WndInfoItem && child.exists && child.visible) {
            WndInfoItem window=(WndInfoItem)child;
            if(window.inspectedItem()==subject&&Boolean.FALSE.equals(window.inspectedLevelKnown())) {
                // The completed public snapshot already owns the old cached body. Test-only
                // mutation uses original identify, without rebuilding or editing that window.
                subject.identify(false);identifiedAfterBody=true;return;
            }
        }
    }
    static Map<String,Object> assertions(){return map("scenario",scenario,"current_level_known",subject==null?null:subject.levelKnown,
            "current_curse_known",subject==null?null:subject.cursedKnown,"identified_after_body",identifiedAfterBody);}
}
