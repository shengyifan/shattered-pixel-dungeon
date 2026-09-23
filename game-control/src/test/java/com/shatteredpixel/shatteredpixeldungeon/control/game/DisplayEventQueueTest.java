package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

/** Queue-only fixtures: no renderer, input action, personal profile, or game save. */
public class DisplayEventQueueTest {
    @Test public void mixedKindsShareOneBoundedNondestructiveFifo() {
        GameController controller = controller();
        GameController.DisplayEvent visual = event("game.visual", 1);
        GameController.DisplayEvent screen = event("game.screen_visual", 2);
        GameController.DisplayEvent log = event("game.log", 3);
        controller.enqueueDisplayEvent(visual);
        controller.enqueueDisplayEvent(screen);
        controller.enqueueDisplayEvent(log);
        controller.enqueueDisplayEvent(visual);

        assertTrue(controller.hasDisplayEvents());
        assertEquals(Collections.singletonList(visual), controller.peekDisplayEvents(1));
        List<GameController.DisplayEvent> first = controller.peekDisplayEvents(2);
        List<GameController.DisplayEvent> repeated = controller.peekDisplayEvents(2);
        assertEquals(Arrays.asList(visual, screen), first);
        assertSame(first.get(0), repeated.get(0));
        assertSame(first.get(1), repeated.get(1));
        assertEquals(Arrays.asList(visual, screen, log, visual), controller.peekDisplayEvents(100));
        assertThrows(UnsupportedOperationException.class, () -> first.clear());
        assertThrows(IllegalArgumentException.class, () -> controller.peekDisplayEvents(0));
        assertThrows(IllegalArgumentException.class, () -> controller.peekDisplayEvents(-1));
        assertEquals(4, controller.peekDisplayEvents(100).size());

        controller.acknowledgeDisplayEvents(first);

        assertEquals(Arrays.asList(log, visual), controller.peekDisplayEvents(100));
        controller.acknowledgeDisplayEvents(controller.peekDisplayEvents(100));
        assertFalse(controller.hasDisplayEvents());
        assertTrue(controller.peekDisplayEvents(1).isEmpty());
    }

    @Test public void publicAndOriginalPayloadsAreDeeplyFrozenAtConstruction() {
        Map<String, Object> cell = map("cell", 17, "opacity", 0.5);
        List<Object> samples = new ArrayList<>(Arrays.asList(cell, null, false));
        Map<String, Object> diagnostic = map("code", 123, "offset", 0);
        List<Object> diagnostics = new ArrayList<>(Collections.singletonList(diagnostic));
        Map<String, Object> presentation = map("diagnostics", diagnostics);
        Map<String, Object> data = map("samples", samples, "presentation", presentation);
        Map<String, Object> source = map("value", 456);
        List<Object> parts = new ArrayList<>(Arrays.asList(source, null, true));
        Map<String, Object> original = map("parts", parts);
        String publicJson = JsonCodec.encode(data), originalJson = JsonCodec.encode(original);
        GameController.DisplayEvent event = new GameController.DisplayEvent("run:queue", "game.screen_visual", data, original);

        cell.put("cell", 99);
        samples.clear();
        diagnostic.put("code", 999);
        diagnostics.clear();
        presentation.put("added", true);
        data.clear();
        source.put("value", 999);
        parts.clear();
        original.clear();

        assertEquals(publicJson, JsonCodec.encode(event.data()));
        assertEquals(originalJson, JsonCodec.encode(event.originalData()));
        assertThrows(UnsupportedOperationException.class, () -> event.data().put("added", true));
        assertThrows(UnsupportedOperationException.class, () -> event.originalData().put("added", true));
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>) event.data().get("samples")).clear());
        assertThrows(UnsupportedOperationException.class, () -> ((Map<?, ?>) event.data().get("presentation")).clear());
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>) event.originalData().get("parts")).clear());
        assertNull(event("game.visual", 1).originalData());
    }

    @Test public void acknowledgementValidatesTheEntireIdentityPrefixBeforeRemovingAnything() {
        GameController controller = controller();
        GameController.DisplayEvent first = event("game.visual", 1);
        GameController.DisplayEvent second = event("game.screen_visual", 2);
        GameController.DisplayEvent third = event("game.log", 3);
        List<GameController.DisplayEvent> expected = Arrays.asList(first, second, third);
        for (GameController.DisplayEvent event : expected) controller.enqueueDisplayEvent(event);

        assertThrows(IllegalStateException.class, () -> controller.acknowledgeDisplayEvents(Arrays.asList(first, third)));
        assertEquals(expected, controller.peekDisplayEvents(100));
        // Equal data is insufficient: the successful persistence batch must own these occurrences.
        assertThrows(IllegalStateException.class, () -> controller.acknowledgeDisplayEvents(Collections.singletonList(event("game.visual", 1))));
        assertEquals(expected, controller.peekDisplayEvents(100));
        assertThrows(IllegalStateException.class, () -> controller.acknowledgeDisplayEvents(Arrays.asList(first, second, third, first)));
        assertEquals(expected, controller.peekDisplayEvents(100));
        controller.acknowledgeDisplayEvents(Collections.emptyList());
        assertEquals(expected, controller.peekDisplayEvents(100));
        controller.acknowledgeDisplayEvents(Arrays.asList(first, second));
        assertEquals(Collections.singletonList(third), controller.peekDisplayEvents(100));
        assertThrows(IllegalStateException.class, () -> controller.acknowledgeDisplayEvents(Collections.singletonList(first)));
        assertEquals(Collections.singletonList(third), controller.peekDisplayEvents(100));
    }

    @Test public void typedSnapshotAdaptersAlsoFreezeOpaqueMetadataForPersistence() {
        Map<String, Object> diagnostic = map("code", 123);
        Map<String, Object> presentation = map("diagnostics", new ArrayList<>(Collections.singletonList(diagnostic)));
        GameController.BannerSnapshot snapshot = new GameController.BannerSnapshot("queue", "fixture",
                map("presentation", presentation));
        GameController.DisplayEvent event = GameController.DisplayEvent.from(snapshot);
        String before = JsonCodec.encode(event.data());
        GameController controller = controller();
        controller.enqueueDisplayEvent(event);

        diagnostic.put("code", 999);
        presentation.clear();

        assertEquals(before, JsonCodec.encode(controller.peekDisplayEvents(1).get(0).data()));
        assertNull(event.originalData());
        assertSame(snapshot, controller.pollBanner());
        assertFalse(controller.hasDisplayEvents());
    }

    @Test public void installingASignalSchedulesAlreadyQueuedWorkWithoutConsumingIt() {
        GameController controller = controller();
        AtomicInteger signals = new AtomicInteger();
        GameController.DisplayEvent first = event("game.screen_visual", 1);
        GameController.DisplayEvent second = event("game.visual", 2);
        controller.enqueueDisplayEvent(first);
        assertEquals(0, signals.get());

        controller.setDisplaySignal(signals::incrementAndGet);

        assertEquals(1, signals.get());
        assertEquals(Collections.singletonList(first), controller.peekDisplayEvents(100));
        controller.enqueueDisplayEvent(second);
        assertEquals(2, signals.get());
        assertEquals(Arrays.asList(first, second), controller.peekDisplayEvents(100));
        controller.acknowledgeDisplayEvents(Collections.singletonList(first));
        assertEquals(2, signals.get());
        controller.setDisplaySignal(null);
        controller.enqueueDisplayEvent(first);
        assertEquals(2, signals.get());
        AtomicInteger replacementSignals = new AtomicInteger();
        controller.setDisplaySignal(replacementSignals::incrementAndGet);
        assertEquals(1, replacementSignals.get());
        assertEquals(Arrays.asList(second, first), controller.peekDisplayEvents(100));
    }

    @Test public void freezingPreservesTheFinalQueuedCutAndRejectsLaterCapture() {
        GameController controller = controller();
        AtomicInteger signals = new AtomicInteger();
        controller.setDisplaySignal(signals::incrementAndGet);
        assertEquals(0, signals.get());
        GameController.DisplayEvent first = event("game.visual", 1);
        GameController.DisplayEvent second = event("game.screen_visual", 2);
        controller.enqueueDisplayEvent(first);
        controller.enqueueDisplayEvent(second);
        List<GameController.DisplayEvent> finalCut = controller.peekDisplayEvents(100);

        controller.freezeDisplayEvents();
        controller.freezeDisplayEvents();
        controller.enqueueDisplayEvent(event("game.log", 3));
        controller.setDisplaySignal(signals::incrementAndGet);
        controller.enqueueDisplayEvent(event("game.screen_visual", 4));

        assertEquals(2, signals.get());
        assertTrue(controller.hasDisplayEvents());
        assertEquals(finalCut, controller.peekDisplayEvents(100));
        controller.acknowledgeDisplayEvents(Collections.singletonList(first));
        assertEquals(Collections.singletonList(second), controller.peekDisplayEvents(100));
        controller.acknowledgeDisplayEvents(Collections.singletonList(second));
        assertFalse(controller.hasDisplayEvents());
        controller.enqueueDisplayEvent(event("game.visual", 5));
        assertFalse(controller.hasDisplayEvents());
    }

    @Test public void typedPollAndTakeAdaptersConsumeTheSameOccurrencesWithoutDuplicates() {
        GameController controller = controller();
        GameController.VisualSnapshot firstVisual = visual(1);
        GameController.VisualSnapshot secondVisual = visual(2);
        GameController.VisualSnapshot thirdVisual = visual(3);
        GameController.GameLogSnapshot firstLog = log(1);
        GameController.GameLogSnapshot secondLog = log(2);
        GameController.GameLogSnapshot thirdLog = log(3);
        GameController.DisplayEvent screen = event("game.screen_visual", 4);
        GameController.DisplayEvent lastVisual = GameController.DisplayEvent.from(thirdVisual);
        GameController.DisplayEvent lastLog = GameController.DisplayEvent.from(thirdLog);
        controller.enqueueDisplayEvent(GameController.DisplayEvent.from(firstVisual));
        controller.enqueueDisplayEvent(screen);
        controller.enqueueDisplayEvent(GameController.DisplayEvent.from(firstLog));
        controller.enqueueDisplayEvent(GameController.DisplayEvent.from(secondVisual));
        controller.enqueueDisplayEvent(GameController.DisplayEvent.from(secondLog));
        controller.enqueueDisplayEvent(lastVisual);
        controller.enqueueDisplayEvent(lastLog);

        assertSame(firstVisual, controller.pollVisual());
        assertSame(firstLog, controller.pollGameLog());
        assertEquals(Arrays.asList(secondVisual, thirdVisual), controller.takeVisuals());
        assertNull(controller.pollVisual());
        assertTrue(controller.takeVisuals().isEmpty());
        assertEquals(Arrays.asList(secondLog, thirdLog), controller.takeGameLogs());
        assertNull(controller.pollGameLog());
        assertTrue(controller.takeGameLogs().isEmpty());
        assertEquals(Collections.singletonList(screen), controller.peekDisplayEvents(100));
        controller.acknowledgeDisplayEvents(Collections.singletonList(screen));
        assertFalse(controller.hasDisplayEvents());

        // Conversely, unified acknowledgement removes an occurrence from all typed adapters.
        controller.enqueueDisplayEvent(lastVisual);
        controller.enqueueDisplayEvent(lastLog);
        controller.acknowledgeDisplayEvents(controller.peekDisplayEvents(2));
        assertNull(controller.pollVisual());
        assertNull(controller.pollGameLog());
        assertTrue(controller.takeVisuals().isEmpty());
        assertTrue(controller.takeGameLogs().isEmpty());
    }

    private static GameController controller() {
        return new GameController(null, "menu:display-queue-fixture", failure -> { throw new AssertionError(failure); });
    }

    private static GameController.DisplayEvent event(String kind, int sequence) {
        return new GameController.DisplayEvent("run:queue", kind, map("sequence", sequence), null);
    }

    private static GameController.VisualSnapshot visual(int generation) {
        return new GameController.VisualSnapshot("queue", new Object(), 1, "map:queue", generation,
                "2026-09-23T00:00:00Z", Collections.emptyList());
    }

    private static GameController.GameLogSnapshot log(int sequence) {
        return new GameController.GameLogSnapshot("run:queue", "2026-09-23T00:00:0" + sequence + "Z", Collections.emptyList());
    }
}
