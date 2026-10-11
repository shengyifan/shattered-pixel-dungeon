package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.watabou.noosa.RuntimeObserver;
import com.shatteredpixel.shatteredpixeldungeon.control.game.text.TextProvenance;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GameLogSnapshotTest {
    @Test void sameRunReconstructionDeduplicatesButNewRunAndChangedMessageTonesDoNot(){
        GameController controller=new GameController(null,"menu:test",error->{throw new AssertionError(error);});
        String label=controller.onTextResource("Upgrade","windows.wndupgrade.upgrade","en",new Object[0],"Upgrade");
        List<RuntimeObserver.LogEntry> text=List.of(new RuntimeObserver.LogEntry(label,0xffffff));
        controller.onGameLog("a",text);controller.onGameLog("a",new ArrayList<>(text));
        GameController.DisplayEvent a=DisplayEventTestFixture.next(controller, "game.log");assertEquals("run:a",a.scopeId);assertNull(DisplayEventTestFixture.next(controller, "game.log"));
        controller.onGameLog("b",text);assertEquals("run:b",DisplayEventTestFixture.next(controller, "game.log").scopeId);
        controller.onGameLog("a",text);assertNull(DisplayEventTestFixture.next(controller, "game.log"));
        controller.onGameLog("a",List.of(new RuntimeObserver.LogEntry(label,0xff0000)));
        assertNotNull(DisplayEventTestFixture.next(controller, "game.log"));
        controller.onGameLog(null,text);assertNull(DisplayEventTestFixture.next(controller, "game.log"));
    }

    @Test void queuedSnapshotsAreImmutableAndCarryCallbackTimeWithoutCreatingWorldState(){
        GameController controller=new GameController(null,"menu:test",error->{throw new AssertionError(error);});
        String label=controller.onTextResource("防御","windows.wndupgrade.blocking","zh",new Object[0],"防御");
        List<RuntimeObserver.LogEntry> input=new ArrayList<>();input.add(new RuntimeObserver.LogEntry(label,7));
        Instant before=Instant.now();controller.onGameLog("a",input);Instant after=Instant.now();input.clear();
        GameController.DisplayEvent event=DisplayEventTestFixture.next(controller, "game.log");
        assertEquals("防御",((Map<?,?>)((List<?>)event.originalData().get("entries")).get(0)).get("text"));
        assertThrows(UnsupportedOperationException.class,()->((List<?>)event.originalData().get("entries")).clear());
        Map<String,Object> data=event.data();assertEquals("gameplay_log_snapshot_v1",data.get("format"));
        Map<?,?> line=(Map<?,?>)((List<?>)data.get("entries")).get(0);
        assertTrue(TextProvenance.isToken((Map<?,?>)line.get("text")));
        Map<String,Object> displayed=PublicEnglishProjection.copy(data);
        assertEquals("Blocking",((Map<?,?>)((List<?>)displayed.get("entries")).get(0)).get("text"));
        assertFalse(data.toString().contains("防御"));
        assertThrows(UnsupportedOperationException.class,()->data.clear());
        assertFalse(Instant.parse((String)event.data().get("occurred_at")).isBefore(before));assertFalse(Instant.parse((String)event.data().get("occurred_at")).isAfter(after));
        assertNull(controller.latest());
    }

    @Test void equalGuiTextWithDifferentResourceOriginsProducesDistinctFrozenEvents(){
        GameController controller=new GameController(null,"menu:test",error->{throw new AssertionError(error);});
        String blocking=controller.onTextResource("防御","windows.wndupgrade.blocking","zh",new Object[0],"防御");
        String defense=controller.onTextResource("防御","items.stones.stoneofaugmentation$wndaugment.defense","zh",new Object[0],"防御");
        controller.onGameLog("a",List.of(new RuntimeObserver.LogEntry(blocking,7)));
        controller.onGameLog("a",List.of(new RuntimeObserver.LogEntry(defense,7)));
        assertTrue(PublicEnglishProjection.copy(DisplayEventTestFixture.next(controller, "game.log").data()).toString().contains("Blocking"));
        assertTrue(PublicEnglishProjection.copy(DisplayEventTestFixture.next(controller, "game.log").data()).toString().contains("Defense"));
        assertNull(DisplayEventTestFixture.next(controller, "game.log"));
    }
}
