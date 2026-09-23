package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ToxicImbue;
import com.shatteredpixel.shatteredpixeldungeon.effects.FloatingText;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.ThrowingStone;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.MissileWeapon;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.*;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndInfoBuff;
import com.watabou.utils.PointF;
import java.lang.reflect.Field;
import java.util.Arrays;

/** Native widgets and renderer in isolated fixture scenes, never production controls. */
final class CombatAppearanceFixtures {
    static boolean supports(String name){return Arrays.asList("appearance-floating","appearance-buff","appearance-item","appearance-markup").contains(name);}
    static void prepare(String name,Hero hero)throws Exception {
        switch(name){
            case "appearance-floating": {
                PointF center=hero.sprite.center();
                for(int i=0;i<2;i++){
                    FloatingText text=GameScene.status();
                    text.reset(center.x+(i==0?-12:12),center.y+16,Messages.literal("4"),i==0?0xFF0000:0x00FF00,
                            i==0?FloatingText.PHYS_DMG:FloatingText.HEALING,true);
                    Field cell=FloatingText.class.getDeclaredField("observationCell");cell.setAccessible(true);cell.setInt(text,hero.pos);
                    Field lifetime=FloatingText.class.getDeclaredField("timeLeft");lifetime.setAccessible(true);lifetime.setFloat(text,2f);
                }
                break;
            }
            case "appearance-item": {
                ThrowingStone fresh=new ThrowingStone(),worn=new ThrowingStone();fresh.identify();worn.identify();
                Field durability=MissileWeapon.class.getDeclaredField("durability");durability.setAccessible(true);durability.setFloat(worn,1f);
                Window window=new Window(100,35);
                ItemSlot left=new ItemSlot(fresh),right=new ItemSlot(worn);
                window.add(left);window.add(right);left.setRect(10,5,24,24);right.setRect(60,5,24,24);
                GameScene.show(window);break;
            }
            case "appearance-buff": case "appearance-markup": {
                if(name.equals("appearance-markup")){
                    GameScene.show(new WndInfoBuff(Buff.prolong(hero,
                            com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Degrade.class,10f)));
                    break;
                }
                ToxicImbue buff=Buff.affect(hero,ToxicImbue.class);buff.set(10f);
                {
                    Window window=new Window(140,40);BuffIndicator indicator=new BuffIndicator(hero,false);
                    window.add(indicator);indicator.setRect(5,5,130,25);GameScene.show(window);
                }
                break;
            }
            default:throw new IllegalArgumentException("Unknown appearance fixture");
        }
    }
}
