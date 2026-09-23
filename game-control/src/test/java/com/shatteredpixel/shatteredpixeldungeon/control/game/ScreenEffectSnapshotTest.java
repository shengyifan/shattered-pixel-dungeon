package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.watabou.noosa.ScreenEffect;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.jupiter.api.Assertions.*;

/** Screen shake and transition pixels do not enter CLI 8 state or persistence. */
class ScreenEffectSnapshotTest {
    @Test void screenSamplesAndEpisodeChangesNeverCreateHistory(){
        GameController c=new GameController(null,"menu:screen",error->{throw new AssertionError(error);});
        for(int i=0;i<5;i++){
            c.onScreenEffects("run",new Object(),1,List.of(new ScreenEffect("screen_overlay",new Object(),
                    map("color",0,"opacity",.1*i,"screen_rect",List.of(0,0,100,100)))));
            c.onScreenEffects("run",new Object(),1,List.of(new ScreenEffect("camera_displacement",new Object(),
                    map("offset_pixels",List.of(i,-i)))));
            c.onScreenEffects("run",new Object(),1,List.of());
        }
        assertFalse(c.hasDisplayEvents());assertNull(c.latest());
    }

    @Test void retiredEventKindsAreRejectedBeforeQueueing(){
        for(String kind:List.of("game.screen_visual","game.visual_metrics"))
            assertThrows(IllegalArgumentException.class,()->new GameController.DisplayEvent("run:test",kind,map("count",1),null));
    }

    @Test void semanticEventsRetainFifoScopeAndFinalCut(){
        GameController c=new GameController(null,"menu:screen",error->{throw new AssertionError(error);});
        GameController.DisplayEvent first=new GameController.DisplayEvent("run:first","game.banner",map("kind","boss_slain"),null);
        GameController.DisplayEvent second=new GameController.DisplayEvent("run:second","game.floating_text",map("text","3","cell",4),null);
        c.enqueueDisplayEvent(first);c.enqueueDisplayEvent(second);
        List<GameController.DisplayEvent> cut=c.peekDisplayEvents(8);
        assertEquals(List.of(first,second),cut);assertEquals("run:first",cut.get(0).scopeId);
        c.freezeDisplayEvents();c.enqueueDisplayEvent(first);assertEquals(cut,c.peekDisplayEvents(8));
        c.acknowledgeDisplayEvents(cut);assertFalse(c.hasDisplayEvents());
    }
}
