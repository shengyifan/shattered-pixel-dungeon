package com.shatteredpixel.shatteredpixeldungeon.control.game;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Consumes only the actual FIFO head through the production peek/ack contract. */
final class DisplayEventTestFixture {
    private DisplayEventTestFixture() {}
    static GameController.DisplayEvent next(GameController controller, String kind) {
        List<GameController.DisplayEvent> batch = controller.peekDisplayEvents(1);
        if (batch.isEmpty()) return null;
        GameController.DisplayEvent event = batch.get(0);
        assertEquals(kind, event.kind, "The next event must preserve cross-kind FIFO order");
        controller.acknowledgeDisplayEvents(batch);
        return event;
    }
}
