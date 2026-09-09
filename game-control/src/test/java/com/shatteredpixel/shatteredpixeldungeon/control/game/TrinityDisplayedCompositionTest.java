package com.shatteredpixel.shatteredpixeldungeon.control.game;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TrinityDisplayedCompositionTest {
    @Test void separateCompleteTrinityHeaderAndEffectDescriptionKeepTheirOwnBoundaries() {
        String displayed="_体之位格：烈焰附魔_ 三位一体会消耗_15充能_以获得该附魔或刻印的效果_40回合_。";
        assertEquals("_Body Form: blazing enchantment._ Trinity will apply this effect for _40 turns_ at the cost of _15 charge._",
                new DisplayedTextEnglish().translate(displayed));
    }
}
