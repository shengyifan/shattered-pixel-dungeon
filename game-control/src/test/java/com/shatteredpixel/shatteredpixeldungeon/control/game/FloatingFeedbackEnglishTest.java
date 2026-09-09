package com.shatteredpixel.shatteredpixeldungeon.control.game;

import org.junit.jupiter.api.Test;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.jupiter.api.Assertions.*;

class FloatingFeedbackEnglishTest {
    @Test void onlyThePublishedFullyVisibleFloatingTextPresentationResolvesDodged() {
        assertEquals("dodged",PublicEnglishProjection.copy(map("role","text","presentation","floating_text","text","闪避")).get("text"));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copy(map("scene","GameScene","role","text","text","闪避")));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copy(map("role","text","presentation","button","text","闪避")));
    }
    @Test void markerCannotTranslateAnUnknownTailAndNeverReadsAnAttackOutcome() {
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copy(map("role","text","presentation","floating_text","text","闪避未知尾文")));
        assertEquals(PublicEnglishProjection.copy(map("role","text","presentation","floating_text","text","闪避")).get("text"),
                PublicEnglishProjection.copy(map("role","text","presentation","floating_text","text","闪避","hidden_attack_result","unrelated")).get("text"));
    }
    @Test void aMarkedForDeathAnnouncementUsesOnlyItsExistingCompleteFloatingText() {
        assertEquals("marked for death",PublicEnglishProjection.copy(map("role","text","presentation","floating_text","text","夺命印记")).get("text"));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copy(map("role","text","text","夺命印记")));
        assertThrows(DisplayedTextEnglish.PublicTextUnavailableException.class,()->PublicEnglishProjection.copy(map("role","text","presentation","floating_text","text","夺命印记未知尾文")));
        assertEquals("marked for death",PublicEnglishProjection.copy(map("role","text","presentation","floating_text","text","夺命印记","hidden_buff","Something else")).get("text"));
    }
}
