package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.quest.vault.VaultBossElemental;
import com.shatteredpixel.shatteredpixeldungeon.effects.TargetedCell;
import com.shatteredpixel.shatteredpixeldungeon.effects.TargetedCell.PendingWarning;
import com.watabou.noosa.Game;
import com.watabou.noosa.VisualCue;
import com.watabou.utils.Bundle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static com.shatteredpixel.shatteredpixeldungeon.control.game.PendingWarningTest.get;
import static com.shatteredpixel.shatteredpixeldungeon.control.game.PendingWarningTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Native Buff attachment and native actor/marker Bundle round-trip, never personal save data. */
class RestoredWarningLifecycleTest {
    @BeforeAll static void resources() { GameSnapshotterTest.resourceOnlyRuntime(); }

    @Test void realParalysedVaultBossRestorationCannotTurnMarkerExpiryIntoAttackCompletion() throws Exception {
        try (WarningProducerATest.Harness h = new WarningProducerATest.Harness()) {
            VaultBossElemental original = h.place(new VaultBossElemental(), 33);
            h.place(Dungeon.hero, 55);
            set(original, Mob.class, "enemy", Dungeon.hero);
            original.state = original.HUNTING;
            // Native gameplay reaches this setup in SHOCK form; avoid renderer-dependent form-change VFX.
            set(original, VaultBossElemental.class, "form", VaultBossElemental.ElementalForm.SHOCK);
            original.setupLightningBolt(Dungeon.hero);
            assertFalse(original.isImmune(Paralysis.class));
            Set<Integer> originallyDisplayed = h.redCells();
            assertFalse(originallyDisplayed.isEmpty());

            // Suppress only a headless fixture's floating announcement, using native buff attachment.
            original.sprite.alive = false;
            Paralysis paralysis = Buff.prolong(original, Paralysis.class, 20f);
            original.sprite.alive = true;
            assertSame(paralysis, original.buff(Paralysis.class));
            assertTrue(original.paralysed > 0);

            Bundle actor = new Bundle();
            original.storeInBundle(actor);
            List<Bundle> markerBundles = new ArrayList<>();
            for (TargetedCell marker : h.f.markers()) {
                Bundle saved = new Bundle();
                marker.storeInBundle(saved);
                markerBundles.add(saved);
                assertFalse(saved.contains("warningOwners"));
                assertFalse(saved.contains("observedCells"));
                marker.killAndErase();
            }
            PendingWarning.clear();
            TargetedCell.cells.clear();
            VaultBossElemental restored = new VaultBossElemental();
            restored.restoreFromBundle(actor);
            assertEquals(VaultBossElemental.ElementalForm.SHOCK, restored.curForm());
            h.place(restored, 33);
            set(restored, Mob.class, "enemy", Dungeon.hero);
            restored.state = restored.HUNTING;
            assertNotNull(restored.buff(Paralysis.class));
            assertTrue(restored.paralysed > 0);
            assertNull(get(restored, VaultBossElemental.class, "specialWarning"),
                    "Restoration cannot fabricate historical source ownership");
            assertTrue(h.redCells().isEmpty(), "Saved attack targets alone must not publish any warning cells");

            for (Bundle saved : markerBundles) {
                TargetedCell marker = new TargetedCell();
                marker.restoreFromBundle(saved);
                TargetedCell.cells.put(marker.pos, marker); // Native Level restore registration order.
                marker.reset(marker.pos, marker.time);
                h.f.targetedCells.add(marker);
            }
            int neverReobserved = originallyDisplayed.iterator().next();
            h.f.level.heroFOV[neverReobserved] = false;
            List<VisualCue> reobserved = redWarnings(h);
            Set<Integer> expected = new TreeSet<>(originallyDisplayed);
            expected.remove(neverReobserved);
            assertEquals(expected, h.redCells());
            assertTrue(reobserved.stream().allMatch(cue ->
                    cue.appearance.equals(Map.of("lifecycle", "unknown"))));
            Method act = VaultBossElemental.class.getDeclaredMethod("act");
            act.setAccessible(true);
            act.invoke(restored);
            assertEquals(55, get(restored, VaultBossElemental.class, "spTargetCell"),
                    "The genuine paralysis buff leaves the native attack pending");
            set(null, Actor.class, "now", 10f);
            GameController.State beforeFade = h.f.capture();
            Game.elapsed = 2.3f;
            for (TargetedCell marker : h.f.markers()) marker.update();
            assertTrue(h.f.markers().stream().noneMatch(marker -> marker.alive));
            assertEquals(reobserved, redWarnings(h));
            assertEquals(beforeFade.version, h.f.capture().version,
                    "Native marker fade does not erase the unresolved public warning or stale intent");
            h.f.level.heroFOV[neverReobserved] = true;
            assertEquals(expected, h.redCells(), "Saved hidden markers are never reconstructed later");
        }
    }

    private static List<VisualCue> redWarnings(WarningProducerATest.Harness h) throws Exception {
        return h.f.frame().stream().filter(cue -> cue.kind.equals("red_target")).collect(Collectors.toList());
    }
}
