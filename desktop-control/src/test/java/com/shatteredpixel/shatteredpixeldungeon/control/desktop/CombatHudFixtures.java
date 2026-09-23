package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.GuidingLight;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Goo;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.HolyTome;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfMagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Dagger;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MeleeWeapon;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Shortsword;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.ThrowingStone;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.MissileSprite;
import com.shatteredpixel.shatteredpixeldungeon.ui.*;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndClericSpells;
import java.util.Arrays;

/** Explicit isolated native HUD initial conditions; observations still use the normal rendered CLI. */
final class CombatHudFixtures {
    static boolean supports(String name) {
        return Arrays.asList("appearance-freecast","appearance-paidcast","appearance-quicktarget",
                "appearance-bosswarning","appearance-actionicons","appearance-projectile","appearance-attackportrait",
                "appearance-quickslot-preview","appearance-banner-boss").contains(name);
    }
    static void prepare(String name,Hero hero) {
        switch(name) {
            case "appearance-freecast": case "appearance-paidcast": {
                hero.subClass=HeroSubClass.PRIEST;
                HolyTome tome=new HolyTome();tome.identify();hero.belongings.artifact=tome;tome.activate(hero);
                if(name.equals("appearance-paidcast"))Buff.prolong(hero,GuidingLight.GuidingLightPriestCooldown.class,50f);
                GameScene.show(new WndClericSpells(tome,hero,false));break;
            }
            case "appearance-quicktarget": case "appearance-attackportrait": {
                Rat target=new Rat();target.pos=hero.pos+(name.equals("appearance-attackportrait")?1:2);GameScene.add(target);Buff.prolong(target,Paralysis.class,1000f);
                WandOfMagicMissile wand=new WandOfMagicMissile();wand.identify();hero.belongings.backpack.items.add(wand);
                Dungeon.quickslot.setSlot(0,wand);QuickSlotButton.refresh();Dungeon.observe();
                hero.checkVisibleMobs();AttackIndicator.target(target);AttackIndicator.updateState();
                if(name.equals("appearance-quicktarget"))QuickSlotButton.useTargeting(0);break;
            }
            case "appearance-bosswarning": {
                Goo boss=new Goo();boss.pos=hero.pos+2;GameScene.add(boss);Buff.prolong(boss,Paralysis.class,1000f);
                BossHealthBar.assignBoss(boss);BossHealthBar.bleed(true);break;
            }
            case "appearance-actionicons": {
                hero.subClass=HeroSubClass.CHAMPION;hero.belongings.weapon=new Shortsword();hero.belongings.weapon.identify();
                hero.belongings.secondWep=new Dagger();hero.belongings.secondWep.identify();
                ActionIndicator.setAction(Buff.affect(hero,MeleeWeapon.Charger.class));break;
            }
            case "appearance-projectile": {
                MissileSprite missile=(MissileSprite)hero.sprite.parent.recycle(MissileSprite.class);
                missile.reset(hero.sprite,hero.pos+2,new ThrowingStone(),null);break;
            }
            case "appearance-quickslot-preview": {
                Dagger dagger=new Dagger();dagger.identify();hero.belongings.backpack.items.add(dagger);
                ThrowingStone stone=new ThrowingStone();stone.identify();hero.belongings.backpack.items.add(stone);
                Dungeon.quickslot.setSlot(3,dagger);Dungeon.quickslot.setSlot(5,stone);
                Window window=new Window(80,35);Toolbar.SlotSwapTool swap=new Toolbar.SlotSwapTool(128,0,21,23);
                window.add(swap);swap.setRect(15,5,21,23);GameScene.show(window);break;
            }
            case "appearance-banner-boss": GameScene.bossSlain();break;
            default:throw new IllegalArgumentException("Unknown native HUD appearance fixture");
        }
    }
}
