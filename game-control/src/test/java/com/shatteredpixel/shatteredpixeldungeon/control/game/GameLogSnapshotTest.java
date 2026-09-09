package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.watabou.noosa.RuntimeObserver;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GameLogSnapshotTest {
    @Test void sameRunReconstructionDeduplicatesButNewRunAndChangedColorsDoNot(){
        GameController controller=new GameController(null,"menu:test",error->{throw new AssertionError(error);});
        List<RuntimeObserver.LogEntry> text=List.of(new RuntimeObserver.LogEntry("same displayed text",0xffffff));
        controller.onGameLog("a",text);controller.onGameLog("a",new ArrayList<>(text));
        GameController.GameLogSnapshot a=controller.pollGameLog();assertEquals("run:a",a.scopeId);assertNull(controller.pollGameLog());
        controller.onGameLog("b",text);assertEquals("run:b",controller.pollGameLog().scopeId);
        controller.onGameLog("a",text);assertNull(controller.pollGameLog());
        controller.onGameLog("a",List.of(new RuntimeObserver.LogEntry("same displayed text",0xff0000)));
        assertNotNull(controller.pollGameLog());
        controller.onGameLog(null,text);assertNull(controller.pollGameLog());
    }

    @Test void queuedSnapshotsAreImmutableAndCarryCallbackTimeWithoutCreatingWorldState(){
        GameController controller=new GameController(null,"menu:test",error->{throw new AssertionError(error);});
        List<RuntimeObserver.LogEntry> input=new ArrayList<>();input.add(new RuntimeObserver.LogEntry("visible",7));
        Instant before=Instant.now();controller.onGameLog("a",input);Instant after=Instant.now();input.clear();
        GameController.GameLogSnapshot event=controller.pollGameLog();
        assertEquals("visible",event.entries.get(0).text);
        assertThrows(UnsupportedOperationException.class,()->event.entries.clear());
        Map<String,Object> data=event.data();assertEquals("display_snapshot_v1",data.get("format"));
        assertThrows(UnsupportedOperationException.class,()->data.clear());
        assertFalse(Instant.parse(event.occurredAt).isBefore(before));assertFalse(Instant.parse(event.occurredAt).isAfter(after));
        assertNull(controller.latest());
    }
}
