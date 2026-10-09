package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
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
import java.util.Map;

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

    @Test void restoredNativeMarkerRetainsExplicitUnknownLifecycleAfterFadeWithoutReconstructingAnAttack() throws Exception {
        try (PendingWarningTest.Fixture f = new PendingWarningTest.Fixture()) {
            TargetedCell saved = new TargetedCell(); saved.reset(22, Actor.now() + 2f);
            Bundle bundle = new Bundle(); saved.storeInBundle(bundle);
            PendingWarning.clear(); TargetedCell.cells.clear();
            TargetedCell restored = new TargetedCell(); restored.restoreFromBundle(bundle);
            TargetedCell.cells.put(restored.pos, restored); // Native Level.restoreFromBundle registers before scene reset.
            restored.reset(restored.pos, restored.time); f.targetedCells.add(restored);
            f.level.heroFOV[22] = false;
            assertTrue(f.frame().isEmpty());
            f.level.heroFOV[22] = true;
            List<VisualCue> unknown = List.of(unknownWarning(22));
            assertEquals(unknown, f.frame());
            GameController.State displayed = f.capture();
            f.fadeMarkers();
            assertEquals(unknown, f.frame(), "Image expiry does not establish an unbound attack's completion");
            assertEquals(displayed.version, f.capture().version, "A restored marker fade cannot stale intent");
            f.level.heroFOV[22] = false;
            assertTrue(f.frame().isEmpty());
            f.level.heroFOV[22] = true;
            assertEquals(unknown, f.frame());
            f.replaceScene();
            assertEquals(unknown, f.frame(), "Same-level scene rebuilding preserves observed uncertainty");
            Dungeon.branch++;
            assertTrue(f.frame().isEmpty(), "Scope changes end process-local uncertainty");
        }
    }

    @Test void hiddenRestoredMarkersNeverBecomeUnknownWarningsAfterTheyFade() throws Exception {
        try (PendingWarningTest.Fixture f = new PendingWarningTest.Fixture()) {
            TargetedCell marker = GameScene.targetedCell(23, -1f);
            f.level.heroFOV[23] = false;
            assertTrue(f.frame().isEmpty());
            f.fadeMarkers();
            assertFalse(marker.alive);
            f.level.heroFOV[23] = true;
            assertTrue(f.frame().isEmpty(), "Unknown lifecycle does not authorize unseen saved targets");
        }
    }

    @Test void trackedOverlapCannotResolveAnUnboundWarningOrDiscloseOwnerMultiplicity() throws Exception {
        try (PendingWarningTest.Fixture f = new PendingWarningTest.Fixture()) {
            GameScene.targetedCell(22, 1f);
            assertEquals(List.of(unknownWarning(22)), f.frame());
            PendingWarning tracked = PendingWarning.begin(new PendingWarningTest.Owner());
            GameScene.targetedCell(22, 2f, tracked);
            assertEquals(List.of(unknownWarning(22)), f.frame());
            f.fadeMarkers();
            tracked.complete();
            assertEquals(List.of(unknownWarning(22)), f.frame(),
                    "Another attack's completion cannot prove the unbound source has ended");
            PendingWarning.endRun();
            assertTrue(f.frame().isEmpty());
        }
    }

    @Test void completedTailStaysClearButNewNativeUnownedPresentationAtSameCellIsUnknown() throws Exception {
        try (PendingWarningTest.Fixture f = new PendingWarningTest.Fixture()) {
            PendingWarning tracked = PendingWarning.begin(new PendingWarningTest.Owner());
            TargetedCell marker = GameScene.targetedCell(22, 2f, tracked);
            assertEquals(List.of(new VisualCue("red_target", 22)), f.frame());
            tracked.complete();
            assertTrue(marker.alive);
            assertTrue(f.frame().isEmpty(), "A completed source's image tail is not a new unknown attack");
            f.replaceScene();
            assertTrue(f.frame().isEmpty(), "Reattaching native images cannot reinterpret a completed source");
            assertSame(marker, GameScene.targetedCell(22, 3f));
            assertEquals(List.of(unknownWarning(22)), f.frame(),
                    "A real new unbound warning at the same cell must not inherit completed ownership");
        }
    }

    @Test void unownedNativePresentationOverAnActiveTrackedOwnerRetainsIndependentUncertainty() throws Exception {
        try (PendingWarningTest.Fixture f = new PendingWarningTest.Fixture()) {
            PendingWarning tracked = PendingWarning.begin(new PendingWarningTest.Owner());
            TargetedCell marker = GameScene.targetedCell(22, 2f, tracked);
            assertEquals(List.of(new VisualCue("red_target", 22)), f.frame());
            assertSame(marker, GameScene.targetedCell(22, 3f));
            assertEquals(List.of(unknownWarning(22)), f.frame());
            tracked.complete();
            f.fadeMarkers();
            assertEquals(List.of(unknownWarning(22)), f.frame(),
                    "The still-active older owner cannot claim an unbound new presentation");
        }
    }

    @Test void unownedAndTrackedPresentationsBeforeTheFirstFramePreserveBothSourceOrders() throws Exception {
        for (boolean unownedFirst : List.of(false, true)) {
            try (PendingWarningTest.Fixture f = new PendingWarningTest.Fixture()) {
                PendingWarning tracked = PendingWarning.begin(new PendingWarningTest.Owner());
                if (unownedFirst) GameScene.targetedCell(22, 1f);
                GameScene.targetedCell(22, 2f, tracked);
                if (!unownedFirst) GameScene.targetedCell(22, 1f);
                assertEquals(List.of(unknownWarning(22)), f.frame());
                tracked.complete();
                f.fadeMarkers();
                assertEquals(List.of(unknownWarning(22)), f.frame(),
                        "A tracked refresh before observation cannot consume earlier unknown source ancestry");
            }
        }
    }

    @Test void trackedRefreshBeforeRestoredMarkersFirstFrameCannotInventSavedOwnership() throws Exception {
        try (PendingWarningTest.Fixture f = new PendingWarningTest.Fixture()) {
            TargetedCell original = new TargetedCell();
            original.reset(22, Actor.now() + 2f);
            Bundle saved = new Bundle();
            original.storeInBundle(saved);
            PendingWarning.clear();
            TargetedCell.cells.clear();
            TargetedCell restored = new TargetedCell();
            restored.restoreFromBundle(saved);
            TargetedCell.cells.put(restored.pos, restored);
            restored.reset(restored.pos, restored.time);
            f.targetedCells.add(restored);
            PendingWarning tracked = PendingWarning.begin(new PendingWarningTest.Owner());
            assertSame(restored, GameScene.targetedCell(22, 3f, tracked));
            assertEquals(List.of(unknownWarning(22)), f.frame());
            tracked.complete();
            f.fadeMarkers();
            assertEquals(List.of(unknownWarning(22)), f.frame());
        }
    }

    @Test void unknownLifecycleSurvivesFrozenEventsAndEveryObservationView() throws Exception {
        try (PendingWarningTest.Fixture f = new PendingWarningTest.Fixture()) {
            GameScene.targetedCell(22, -1f);
            f.frame();
            GameController.VisualSnapshot event = f.controller.pollVisual();
            assertNotNull(event);
            Map<?, ?> savedCue = (Map<?, ?>) ((List<?>) event.data().get("cues")).get(0);
            assertEquals(Map.of("lifecycle", "unknown"), savedCue.get("appearance"));
            GameController.State frame = f.capture();
            for (boolean sources : List.of(false, true)) {
                for (boolean full : List.of(false, true)) {
                    Map<?, ?> wire = (Map<?, ?>) CompactProtocol.project(frame.publicState, sources, full);
                    Map<?, ?> cues = (Map<?, ?>) wire.get("cues");
                    Map<?, ?> cue = (Map<?, ?>) ((List<?>) cues.get("cues")).get(0);
                    assertEquals(Map.of("lifecycle", "unknown"), cue.get("appearance"));
                }
            }
            PendingWarning.endRun();
            assertTrue(f.frame().isEmpty());
            assertEquals(Map.of("lifecycle", "unknown"), savedCue.get("appearance"),
                    "Current clearing cannot rewrite the frozen event");
        }
    }

    private static VisualCue unknownWarning(int cell) {
        return new VisualCue("red_target", cell, null, null, null, null, Map.of("lifecycle", "unknown"));
    }
}
