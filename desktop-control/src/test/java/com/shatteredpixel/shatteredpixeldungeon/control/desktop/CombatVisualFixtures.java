package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.*;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.VaultSentry;
import com.shatteredpixel.shatteredpixeldungeon.levels.CavesBossLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.SentryRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.standard.EmptyRoom;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;

import java.lang.reflect.Field;
import java.util.Arrays;

/** Isolated initial conditions only. Subsequent actions and display assertions use public CLI. */
final class CombatVisualFixtures {
    static boolean initialPresentationReady() {
        if (!(com.watabou.noosa.Game.scene() instanceof GameScene)) return false;
        for (com.watabou.noosa.Gizmo child : com.watabou.noosa.Game.scene().childrenSnapshot())
            if (child != null && child.exists && child.visible
                    && child.getClass().getName().equals("com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene$Fader")) return false;
        return true;
    }

    static boolean supports(String name) {
        return ActorItemVisualFixtures.supports(name) || CombatAppearanceFixtures.supports(name) || CombatHudFixtures.supports(name) || RadialVisualFixtures.supports(name) || ScreenVisualFixtures.supports(name) || Arrays.asList("eye", "eye-hidden", "ghoul", "guardian", "necromancer", "spectral",
                "rocks", "checked", "pylon", "sentry", "flow", "arcane-bomb", "challenge", "beacon", "golem", "ring", "beam", "magic",
                "surprise", "wound", "flare", "spell", "dm300", "chains", "ripper", "spire").contains(name);
    }

    static void prepare(String name, Hero hero) throws Exception {
        LowFrequencyFixtures.arena(hero);
        // Arena preparation can move the hero away from the original entrance while the
        // camera still eases towards it. Start these draw tests with that native camera
        // already centered, so a deliberately clipped first frame is not a false failure.
        com.watabou.noosa.Camera.main.snapTo(hero.sprite.center());
        if(ActorItemVisualFixtures.supports(name)){ActorItemVisualFixtures.prepare(name,hero);return;}
        if(CombatAppearanceFixtures.supports(name)){CombatAppearanceFixtures.prepare(name,hero);return;}
        if(CombatHudFixtures.supports(name)){CombatHudFixtures.prepare(name,hero);return;}
        if(RadialVisualFixtures.supports(name)){RadialVisualFixtures.prepare(name,hero);return;}
        if(ScreenVisualFixtures.supports(name)){ScreenVisualFixtures.prepare(name,hero);return;}
        int width = Dungeon.level.width();
        switch (name) {
            case "eye": case "eye-hidden": {
                Eye eye = spawn(new Eye(), hero.pos + 2);
                eye.state = eye.HUNTING;
                if (name.endsWith("hidden")) {
                    for (int y = 1; y < Dungeon.level.height()-1; y++) {
                        int cell = y*width + hero.pos%width + 1;
                        Level.set(cell, Terrain.WALL); GameScene.updateMap(cell);
                    }
                    Dungeon.observe();
                    if (Dungeon.level.heroFOV[eye.pos]) throw new IllegalStateException("Hidden fixture is visible");
                }
                break;
            }
            case "ghoul": {
                Ghoul target = spawn(new Ghoul(), hero.pos+1);
                Ghoul host = spawn(new Ghoul(), hero.pos-2);
                target.HP = 1; target.state = target.PASSIVE; host.state = host.PASSIVE;
                set(target, "partnerID", host.id()); set(host, "partnerID", target.id());
                Buff.prolong(host, Paralysis.class, 1000f);
                break;
            }
            case "guardian": {
                CrystalGuardian target = spawn(new CrystalGuardian(), hero.pos+1);
                target.HP = 1;
                break;
            }
            case "necromancer": case "spectral": {
                Necromancer caster = name.equals("spectral") ? new SpectralNecromancer() : new Necromancer();
                spawn(caster, hero.pos+2); caster.state = caster.HUNTING;
                break;
            }
            case "rocks": {
                DelayedRockFall rocks = Buff.prolong(hero, DelayedRockFall.class, 3f);
                rocks.setRockPositions(Arrays.asList(hero.pos-1, hero.pos+1));
                break;
            }
            case "checked": {
                VaultSentry sentry = spawn(new VaultSentry(), hero.pos+2);
                sentry.scanWidth = 70; sentry.scanLength = 5;
                sentry.scanDirs = new int[][]{{hero.pos}};
                break;
            }
            case "pylon": case "flow": {
                Pylon pylon = spawn(new Pylon(), hero.pos+2);
                pylon.activate();
                if (name.equals("flow")) {
                    Buff.prolong(pylon, Paralysis.class, 1000f);
                    for (int offset : new int[]{-1, -width, width})
                        GameScene.add(Blob.seed(hero.pos+offset, 10, CavesBossLevel.PylonEnergy.class));
                }
                break;
            }
            case "sentry": {
                SentryRoom.Sentry sentry = new SentryRoom.Sentry();
                set(sentry, "initialChargeDelay", 6f); set(sentry, "curChargeDelay", 6f);
                EmptyRoom room = new EmptyRoom();
                int x = hero.pos%width, y = hero.pos/width;
                room.set(x-3, y-3, x+3, y+3); set(sentry, "room", room);
                Level.set(hero.pos, Terrain.EMPTY_SP); GameScene.updateMap(hero.pos);
                spawn(sentry, hero.pos+2);
                break;
            }
            case "arcane-bomb": {
                com.shatteredpixel.shatteredpixeldungeon.items.bombs.ArcaneBomb bomb =
                        new com.shatteredpixel.shatteredpixeldungeon.items.bombs.ArcaneBomb();
                Dungeon.level.drop(bomb, hero.pos+2);
                bomb.fuse = new com.shatteredpixel.shatteredpixeldungeon.items.bombs.ArcaneBomb.ArcaneBombFuse().ignite(bomb);
                com.shatteredpixel.shatteredpixeldungeon.actors.Actor.addDelayed(bomb.fuse, 3f);
                break;
            }
            case "challenge": {
                Buff.affect(hero, com.shatteredpixel.shatteredpixeldungeon.items.scrolls.exotic.ScrollOfChallenge.ChallengeArena.class)
                        .setup(hero.pos);
                break;
            }
            case "beacon": {
                com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.mage.WarpBeacon.WarpBeaconTracker tracker =
                        new com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.mage.WarpBeacon.WarpBeaconTracker();
                set(tracker, "pos", hero.pos-1); set(tracker, "depth", Dungeon.depth); set(tracker, "branch", Dungeon.branch);
                tracker.attachTo(hero);
                break;
            }
            case "golem": {
                Golem golem = spawn(new Golem(), hero.pos+2);
                golem.state = golem.PASSIVE;
                // Prepared native animation source; no AI destination is exposed or used as test evidence.
                ((com.shatteredpixel.shatteredpixeldungeon.sprites.GolemSprite)golem.sprite).teleParticles(true);
                break;
            }
            case "ring": {
                com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfBlastWave.BlastWave.blast(hero.pos+1, 1f, 0xFF8800);
                break;
            }
            case "beam": {
                hero.sprite.parent.add(new com.shatteredpixel.shatteredpixeldungeon.effects.Beam.DeathRay(hero.sprite.center(),
                        com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTilemap.raisedTileCenterToWorld(hero.pos+2))
                        .observeDraw(hero.pos, hero.pos+2));
                break;
            }
            case "magic": {
                com.shatteredpixel.shatteredpixeldungeon.effects.MagicMissile.boltFromChar(hero.sprite.parent,
                        com.shatteredpixel.shatteredpixeldungeon.effects.MagicMissile.FIRE, hero.sprite, hero.pos+2, null);
                break;
            }
            case "surprise": com.shatteredpixel.shatteredpixeldungeon.effects.Surprise.hit(hero); break;
            case "wound": com.shatteredpixel.shatteredpixeldungeon.effects.Wound.hit(hero); break;
            case "flare": new com.shatteredpixel.shatteredpixeldungeon.effects.Flare(6, 20).color(0x00AAFF, true).show(hero.sprite, 2f); break;
            case "spell": com.shatteredpixel.shatteredpixeldungeon.effects.SpellSprite.show(hero,
                    com.shatteredpixel.shatteredpixeldungeon.effects.SpellSprite.HASTE); break;
            case "dm300": {
                DM300 machine = spawn(new DM300(), hero.pos+2); machine.state = machine.PASSIVE;
                ((com.shatteredpixel.shatteredpixeldungeon.sprites.DM300Sprite)machine.sprite).charge();
                break;
            }
            case "chains": {
                hero.sprite.parent.add(new com.shatteredpixel.shatteredpixeldungeon.effects.Chains(hero.sprite.center(),
                        com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTilemap.raisedTileCenterToWorld(hero.pos+2),
                        com.shatteredpixel.shatteredpixeldungeon.effects.Effects.Type.ETHEREAL_CHAIN, null));
                break;
            }
            case "ripper": {
                RipperDemon ripper = spawn(new RipperDemon(), hero.pos+2); ripper.state = ripper.PASSIVE;
                ((com.shatteredpixel.shatteredpixeldungeon.sprites.RipperSprite)ripper.sprite).leapPrep(hero.pos);
                break;
            }
            case "spire": {
                CrystalSpire spire = new CrystalSpire(); spire.HP = spire.HT/2;
                spawn(spire, hero.pos+2);
                break;
            }
            default: throw new IllegalArgumentException("Unknown combat visual fixture");
        }
    }

    private static <T extends Mob> T spawn(T mob, int cell) {
        mob.pos = cell; GameScene.add(mob); return mob;
    }

    private static void set(Object object, String name, Object value) throws Exception {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(object, value); return; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
