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
        String label=controller.onTextResource("Upgrade","windows.wndupgrade.upgrade","en",new Object[0]);
        List<RuntimeObserver.LogEntry> text=List.of(new RuntimeObserver.LogEntry(label,0xffffff));
        controller.onGameLog("a",text);controller.onGameLog("a",new ArrayList<>(text));
        GameController.GameLogSnapshot a=controller.pollGameLog();assertEquals("run:a",a.scopeId);assertNull(controller.pollGameLog());
        controller.onGameLog("b",text);assertEquals("run:b",controller.pollGameLog().scopeId);
        controller.onGameLog("a",text);assertNull(controller.pollGameLog());
        controller.onGameLog("a",List.of(new RuntimeObserver.LogEntry(label,0xff0000)));
        assertNotNull(controller.pollGameLog());
        controller.onGameLog(null,text);assertNull(controller.pollGameLog());
    }

    @Test void queuedSnapshotsAreImmutableAndCarryCallbackTimeWithoutCreatingWorldState(){
        GameController controller=new GameController(null,"menu:test",error->{throw new AssertionError(error);});
        String label=controller.onTextResource("防御","windows.wndupgrade.blocking","zh",new Object[0]);
        List<RuntimeObserver.LogEntry> input=new ArrayList<>();input.add(new RuntimeObserver.LogEntry(label,7));
        Instant before=Instant.now();controller.onGameLog("a",input);Instant after=Instant.now();input.clear();
        GameController.GameLogSnapshot event=controller.pollGameLog();
        assertEquals("防御",event.entries.get(0).text);
        assertThrows(UnsupportedOperationException.class,()->event.entries.clear());
        Map<String,Object> data=event.data();assertEquals("gameplay_log_snapshot_v1",data.get("format"));
        Map<?,?> line=(Map<?,?>)((List<?>)data.get("entries")).get(0);
        assertTrue(TextProvenance.isToken((Map<?,?>)line.get("text")));
        Map<String,Object> displayed=PublicEnglishProjection.copy(data);
        assertEquals("Blocking",((Map<?,?>)((List<?>)displayed.get("entries")).get(0)).get("text"));
        assertFalse(data.toString().contains("防御"));
        assertThrows(UnsupportedOperationException.class,()->data.clear());
        assertFalse(Instant.parse(event.occurredAt).isBefore(before));assertFalse(Instant.parse(event.occurredAt).isAfter(after));
        assertNull(controller.latest());
    }

    @Test void equalGuiTextWithDifferentResourceOriginsProducesDistinctFrozenEvents(){
        GameController controller=new GameController(null,"menu:test",error->{throw new AssertionError(error);});
        String blocking=controller.onTextResource("防御","windows.wndupgrade.blocking","zh",new Object[0]);
        String defense=controller.onTextResource("防御","items.stones.stoneofaugmentation$wndaugment.defense","zh",new Object[0]);
        controller.onGameLog("a",List.of(new RuntimeObserver.LogEntry(blocking,7)));
        controller.onGameLog("a",List.of(new RuntimeObserver.LogEntry(defense,7)));
        assertTrue(PublicEnglishProjection.copy(controller.pollGameLog().data()).toString().contains("Blocking"));
        assertTrue(PublicEnglishProjection.copy(controller.pollGameLog().data()).toString().contains("Defense"));
        assertNull(controller.pollGameLog());
    }
}
