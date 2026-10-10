/* Isolated research fixture. No files, profiles, game scenes or private audit data are opened. */
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.Ratmogrify;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.*;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.*;
import com.shatteredpixel.shatteredpixeldungeon.items.BrokenSeal;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.HeavyBoomerang;
import com.watabou.utils.Bundle;
import com.watabou.utils.Bundlable;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

public class ReloadSerializationProbe {
    static int checks;
    static Field field(Class<?> type, String key) throws Exception {
        for (Class<?> c=type; c!=null; c=c.getSuperclass()) {
            try { Field f=c.getDeclaredField(key); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(type.getName()+"."+key);
    }
    static Object get(Object o,String key) throws Exception {return field(o.getClass(),key).get(o);}
    static void set(Object o,String key,Object value) throws Exception {field(o.getClass(),key).set(o,value);}
    static <T extends Bundlable> T roundTrip(T original) throws Exception {
        Bundle root=new Bundle(); root.put("fixture",original);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        if (!Bundle.write(root,bytes,true)) throw new AssertionError("Bundle write failed");
        @SuppressWarnings("unchecked") T restored=(T)Bundle.read(new ByteArrayInputStream(bytes.toByteArray())).get("fixture");
        if (restored==null || restored==original) throw new AssertionError("Not a fresh restored object");
        return restored;
    }
    static void check(String label,Object expected,Object actual) {
        if (!Objects.equals(expected,actual)) throw new AssertionError(label+": expected="+expected+" actual="+actual);
        checks++; System.out.println("PASS "+label+" => "+actual);
    }
    static void preserved(Bundlable original,String key,Object value) throws Exception {
        set(original,key,value); check(original.getClass().getSimpleName()+"."+key+" preserved",value,get(roundTrip(original),key));
    }
    public static class DamageOnlyChar extends com.shatteredpixel.shatteredpixeldungeon.actors.Char {
        public void damage(int amount, Object source) { HP-=amount; }
        protected boolean act() { return true; }
    }
    static int corruptionTicks(boolean reloadEachTick) throws Exception {
        DamageOnlyChar target=new DamageOnlyChar(); target.HT=target.HP=20;
        Corruption corruption=new Corruption();corruption.target=target;
        for (int i=0;i<20;i++) {
            corruption.act();
            if (reloadEachTick) {corruption=roundTrip(corruption);corruption.target=target;}
        }
        return 20-target.HP;
    }
    public static void main(String[] args) throws Exception {
        Actor.clear(); Dungeon.challenges=511; Dungeon.hero=new Hero();
        Ratmogrify.TransmogRat rat=new Ratmogrify.TransmogRat(); rat.setup(new Gnoll());
        set(rat,"timeLeft",1.25f);
        Ratmogrify.TransmogRat restoredRat=roundTrip(rat);
        check("TransmogRat non-allied timer resets from 1.25",6f,get(restoredRat,"timeLeft"));
        check("TransmogRat original class retained",Gnoll.class,restoredRat.getOriginal().getClass());
        HeavyBoomerang.CircleBack back=new HeavyBoomerang.CircleBack();
        back.setup(new HeavyBoomerang(),42,55,7,0); set(back,"left",4);
        HeavyBoomerang.CircleBack restoredBack=roundTrip(back);
        check("CircleBack left resets from 4",0,get(restoredBack,"left"));
        check("CircleBack return position preserved",55,restoredBack.returnPos());
        DM201 dm=new DM201(); set(dm,"threatened",true);
        check("DM201 threatened resets from true",false,get(roundTrip(dm),"threatened"));
        GreatCrab crab=new GreatCrab(); set(crab,"moving",2);
        check("GreatCrab moving resets from 2",0,get(roundTrip(crab),"moving"));
        Rat mob=new Rat(); mob.state=mob.HUNTING; mob.HP=3; mob.pos=42;
        set(mob,"target",55);set(mob,"enemySeen",true);set(mob,"enemy",Dungeon.hero);set(mob,"alerted",true);set(mob,"time",2.25f);
        Rat restored=roundTrip(mob);
        check("Mob hunting state preserved",true,restored.state==restored.HUNTING);
        check("Mob HP preserved",3,restored.HP); check("Mob position preserved",42,restored.pos);
        check("Mob target preserved",55,get(restored,"target")); check("Mob enemySeen preserved",true,get(restored,"enemySeen"));
        check("Mob enemyID preserved",Dungeon.hero.id(),get(restored,"enemyID"));
        check("Mob alerted transient reset",false,get(restored,"alerted"));
        check("Actor time preserved",2.25f,get(restored,"time"));
        preserved(new MobSpawner(),"time",17.5f);
        preserved(new ChampionEnemy.Growing(),"multiplier",1.75f);
        Goo goo=new Goo(); preserved(goo,"pumpedUp",2);preserved(goo,"healInc",3);
        preserved(new GnollTrickster(),"combo",4);
        preserved(new Eye(),"beamCharged",true);preserved(new Eye(),"beamTarget",55);
        preserved(new Eye(),"beamCooldown",4);
        preserved(new DM300(),"turnsSinceLastAbility",4);
        preserved(new DM300(),"abilityCooldown",7);
        preserved(new Tengu(),"abilityCooldown",3);
        preserved(new Tengu.BombAbility(),"timer",1);
        BrokenSeal.WarriorShield shield=new BrokenSeal.WarriorShield();shield.setShield(7);
        set(shield,"initialShield",10);set(shield,"turnsSinceEnemies",2.5f);set(shield,"cooldown",32);
        BrokenSeal.WarriorShield restoredShield=roundTrip(shield);
        check("WarriorShield shielding preserved",7,restoredShield.shielding());
        check("WarriorShield cooldown preserved",32,get(restoredShield,"cooldown"));
        check("WarriorShield initialShield preserved",10,get(restoredShield,"initialShield"));
        check("WarriorShield turnsSinceEnemies preserved",2.5f,get(restoredShield,"turnsSinceEnemies"));
        Berserk berserk=new Berserk();
        // Keep NORMAL power below 1, avoiding UI action registration in this headless fixture.
        preserved(berserk,"power",0.75f); preserved(berserk,"turnRecovery",55);preserved(berserk,"levelRecovery",1.5f);
        Corruption corruption=new Corruption();set(corruption,"partialDamage",0.75f);
        check("Corruption fractional damage truncated from 0.75",0f,get(roundTrip(corruption),"partialDamage"));
        check("Corruption HT20 after 20 ticks without reload damage",4,corruptionTicks(false));
        check("Corruption HT20 after 20 ticks reloading each tick damage",0,corruptionTicks(true));
        System.out.println("PASS TOTAL="+checks+" challenges="+Dungeon.challenges+" (synthetic in-memory fixtures; no live gameplay)");
    }
}
