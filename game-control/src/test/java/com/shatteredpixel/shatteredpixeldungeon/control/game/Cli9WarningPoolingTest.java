package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.effects.TargetedCell;
import com.shatteredpixel.shatteredpixeldungeon.effects.TargetedCell.PendingWarning;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.Game;
import com.watabou.noosa.RuntimeObserver;
import com.watabou.noosa.VisualCue;
import com.watabou.utils.Bundle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.shatteredpixel.shatteredpixeldungeon.control.game.PendingWarningTest.get;
import static com.shatteredpixel.shatteredpixeldungeon.control.game.PendingWarningTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Native v4 pooling/time is independent of process-local, publicly observed attack episodes. */
class Cli9WarningPoolingTest {
    @BeforeAll static void resources() { GameSnapshotterTest.resourceOnlyRuntime(); }

    @Test void oneNativePooledMarkerRetainsIndependentOverlappingAttackOwners() throws Exception {
        try (PendingWarningTest.Fixture f = new PendingWarningTest.Fixture()) {
            PendingWarning first = PendingWarning.begin(new PendingWarningTest.Owner());
            PendingWarning second = PendingWarning.begin(new PendingWarningTest.Owner());
            TargetedCell a = GameScene.targetedCell(22, 2f, first);
            TargetedCell b = GameScene.targetedCell(22, 4f, second);
            assertSame(a, b, "Native per-cell pooling must remain intact");
            assertEquals(1, f.markers().size());
            assertEquals(Actor.now() + 4f, b.time);
            assertEquals(List.of(new VisualCue("red_target", 22)), f.frame());
            GameController.State sharedCell = f.capture();
            first.complete();
            assertEquals(List.of(new VisualCue("red_target", 22)), f.frame());
            assertEquals(sharedCell.version, f.capture().version, "Private owner count changes cannot stale an unchanged public cell");
            f.fadeMarkers();
            assertFalse(b.alive);
            assertEquals(List.of(new VisualCue("red_target", 22)), f.frame());
            second.complete();
            assertTrue(f.frame().isEmpty());
        }
    }

    @Test void markerRebindingCannotAssignAnOlderAttackNewUnobservedCells() throws Exception {
        try (PendingWarningTest.Fixture f = new PendingWarningTest.Fixture()) {
            PendingWarning first = PendingWarning.begin(new PendingWarningTest.Owner());
            PendingWarning second = PendingWarning.begin(new PendingWarningTest.Owner());
            TargetedCell marker = GameScene.targetedCell(22, 1f, first);
            assertEquals(List.of(new VisualCue("red_target", 22)), f.frame());
            marker.reset(23, Actor.now() + 1f);
            marker.observeWarning(second);
            assertEquals(List.of(new VisualCue("red_target", 22), new VisualCue("red_target", 23)), f.frame());
            second.complete();
            assertEquals(List.of(new VisualCue("red_target", 22)), f.frame(),
                    "A pooled marker's new cell must not be remembered by its old owner");
            first.complete();
            assertTrue(f.frame().isEmpty(), "Completed owners cannot fall back to ownerless marker evidence");
        }
    }

    @Test void nativeGameTimeHoldAndFixTimeRemainIndependentOfObservation() throws Exception {
        try (PendingWarningTest.Fixture f = new PendingWarningTest.Fixture()) {
            Object oldNow = get(null, Actor.class, "now");
            try {
                set(null, Actor.class, "now", 10f);
                PendingWarning warning = PendingWarning.begin(new PendingWarningTest.Owner());
                TargetedCell marker = GameScene.targetedCell(22, 2f, warning);
                Game.elapsed = 100f;
                marker.update();
                assertTrue(marker.alive, "Wall-clock delay alone cannot expire a native game-time marker");
                assertEquals(List.of(new VisualCue("red_target", 22)), f.frame());
                TargetedCell.fixTime(5f);
                set(null, Actor.class, "now", 5f);
                assertEquals(7f, marker.time);
                marker.update();
                assertTrue(marker.alive, "Native clock normalization keeps the same remaining delay");
                set(null, Actor.class, "now", 8f);
                marker.update();
                assertFalse(marker.alive);
                assertTrue(warning.isPending());
                assertEquals(List.of(new VisualCue("red_target", 22)), f.frame());
                warning.complete();
                assertTrue(f.frame().isEmpty());
            } finally { set(null, Actor.class, "now", oldNow); }
        }
    }

    @Test void observerAndOwnerHooksDoNotChangeNativeMarkerStateOrBundle() throws Exception {
        try (PendingWarningTest.Fixture f = new PendingWarningTest.Fixture()) {
            RuntimeObserver observer = Game.observer;
            try {
                Game.observer = RuntimeObserver.NONE;
                TargetedCell ordinary = GameScene.targetedCell(22, 2f);
                Game.elapsed = .3f;
                ordinary.update();
                Bundle plain = new Bundle(); ordinary.storeInBundle(plain);
                float plainAlpha = ordinary.alpha(), plainScale = ordinary.scale.x;
                float plainTime = ordinary.time;
                Game.observer = observer;
                PendingWarning owner = PendingWarning.begin(new PendingWarningTest.Owner());
                TargetedCell observed = GameScene.targetedCell(22, 2f, owner);
                observed.update();
                Bundle annotated = new Bundle(); observed.storeInBundle(annotated);
                assertSame(ordinary, observed);
                assertEquals(plainAlpha, observed.alpha());
                assertEquals(plainScale, observed.scale.x);
                assertEquals(plainTime, observed.time);
                assertEquals(plain.getInt("pos"), annotated.getInt("pos"));
                assertEquals(plain.getFloat("time"), annotated.getFloat("time"));
                assertFalse(annotated.contains("warningOwners"));
                assertFalse(annotated.contains("observedCells"));
                assertTrue(f.frame().stream().allMatch(c -> c.kind.equals("red_target")));
            } finally { Game.observer = observer; }
        }
    }

    @Test void restoredNativeMarkerOnlyPublishesCurrentDisplayAndNeverReconstructsAnAttack() throws Exception {
        try (PendingWarningTest.Fixture f = new PendingWarningTest.Fixture()) {
            TargetedCell saved = new TargetedCell(); saved.reset(22, Actor.now() + 2f);
            Bundle bundle = new Bundle(); saved.storeInBundle(bundle);
            PendingWarning.clear(); TargetedCell.cells.clear();
            TargetedCell restored = new TargetedCell(); restored.restoreFromBundle(bundle);
            restored.reset(restored.pos, restored.time); f.targetedCells.add(restored);
            f.level.heroFOV[22] = false;
            assertTrue(f.frame().isEmpty());
            f.level.heroFOV[22] = true;
            assertEquals(List.of(new VisualCue("red_target", 22)), f.frame());
            f.fadeMarkers();
            assertTrue(f.frame().isEmpty(), "Restored display has no private historical attack ownership");
        }
    }
}
