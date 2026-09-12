package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.game.text.TextProvenance;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.journal.Notes;
import com.shatteredpixel.shatteredpixeldungeon.messages.Languages;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.utils.DungeonSeed;
import com.watabou.noosa.Game;
import com.watabou.noosa.RuntimeObserver;
import com.watabou.utils.Bundle;
import com.watabou.utils.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Generated seed text and restored note text without a native window, profile, or save file. */
class DungeonSeedSourceTest {
    private RuntimeObserver previous;
    private Languages previousLanguage;
    private TextProvenance texts;

    @BeforeAll static void resources() { GameSnapshotterTest.resourceOnlyRuntime(); }

    @BeforeEach void setup() {
        previous = Game.observer;
        previousLanguage = Messages.selectedLanguage();
        texts = new TextProvenance(key -> null);
        Game.observer = new RuntimeObserver() {
            @Override public String onTextOperation(String operation, String value, Object... operands) {
                return texts.onTextOperation(operation, value, operands);
            }
        };
    }

    @AfterEach void restore() {
        Game.observer = previous;
        Messages.setup(previousLanguage);
        texts.clear();
    }

    @Test void generatedCodeKeepsOriginalEncodingLocaleAndRandomState() {
        byte[] random = Random.exportState();
        long[] seeds = {0, 1, 25, 26, 675, 676, 17575, 17576, 123456789L, DungeonSeed.TOTAL_SEEDS - 1};
        for (Languages language : new Languages[]{Languages.ENGLISH, Languages.CHI_SMPL, Languages.KOREAN}) {
            Messages.setup(language);
            for (long seed : seeds) {
                String code = DungeonSeed.displayCode(seed);
                assertEquals(referenceCode(seed), code);
                assertEquals(DungeonSeed.convertToCode(seed), code);
                assertEquals(seed, DungeonSeed.convertFromCode(code));
                Map<String, Object> rendered = texts.render(texts.capture(null, code, false));
                assertEquals(code, rendered.get("text"));
                assertEquals("complete", rendered.get("translation_status"));
                assertEquals("format", ((Map<?, ?>) rendered.get("source")).get("kind"));
                assertEquals(language, Messages.selectedLanguage());
            }
        }
        assertArrayEquals(random, Random.exportState());
        assertThrows(IllegalArgumentException.class, () -> DungeonSeed.displayCode(-1));
        assertThrows(IllegalArgumentException.class, () -> DungeonSeed.displayCode(DungeonSeed.TOTAL_SEEDS));
    }

    @Test void newlyGeneratedResumeCodeHasSourceAfterRegistryResetAndFrozenHistoryReplays() {
        long seed = 8675309L;
        String first = DungeonSeed.displayCode(seed);
        Map<String, Object> captured = texts.capture(null, first, false);
        Map<String, Object> history = JsonCodec.decode(JsonCodec.encode(captured));
        texts.clear();
        String resumed = DungeonSeed.displayCode(seed);
        assertEquals(first, resumed);
        assertEquals(captured, texts.capture(null, resumed, false));
        texts.clear();
        assertEquals(first, texts.render(history).get("text"));
        assertEquals("complete", texts.render(history).get("translation_status"));
        assertEquals("partial", texts.render(texts.capture(null, new String(first), false)).get("translation_status"));
    }

    @Test void noteGettersKeepUserOriginWhenReadAgainAfterAnInMemoryRestore() {
        String title = "Test route note revised", body = "Check this route.\nKeep _literal_ user text.";
        Notes.CustomRecord original = new Notes.CustomRecord(title, body);
        Bundle bundle = new Bundle();
        original.storeInBundle(bundle);
        texts.clear();
        Notes.CustomRecord restored = new Notes.CustomRecord();
        restored.restoreFromBundle(bundle);
        for (String value : new String[]{restored.title(), restored.desc()}) {
            Map<String, Object> rendered = texts.render(texts.capture(null, value, false));
            assertEquals(value, rendered.get("text"));
            assertEquals("complete", rendered.get("translation_status"));
            assertEquals("user", ((Map<?, ?>) rendered.get("source")).get("origin"));
        }
        assertEquals(title, restored.title());
        assertEquals(body, restored.desc());
    }

    private static String referenceCode(long value) {
        char[] code = "AAA-AAA-AAA".toCharArray();
        for (int i = code.length - 1; i >= 0; i--) {
            if (code[i] == '-') continue;
            code[i] = (char) ('A' + value % 26);
            value /= 26;
        }
        return new String(code);
    }
}
