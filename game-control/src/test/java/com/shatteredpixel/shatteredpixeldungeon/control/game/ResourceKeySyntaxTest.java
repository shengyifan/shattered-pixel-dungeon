package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.game.text.TextProvenance;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResourceKeySyntaxTest {
    @Test void upstreamAmpersandKeyRemainsExactThroughPublicSourceAndEnglishProjection() {
        String key = "items.quest.corpsedust&dustwraith.rankings_desc";
        TextProvenance texts = new TextProvenance(candidate -> key.equals(candidate) ? "Cursed by Corpse Dust" : null);
        String displayed = texts.onTextResource("尸尘诅咒", key, "zh", new Object[0], "尸尘诅咒");
        Map<String,Object> rendered = texts.render(JsonCodec.decode(JsonCodec.encode(texts.capture(null, displayed, false))));
        assertEquals("Cursed by Corpse Dust", rendered.get("text"));
        assertEquals("complete", rendered.get("translation_status"));
        assertEquals(key, ((Map<?,?>) rendered.get("source")).get("key"));
    }

    @Test void addingAmpersandDoesNotAdmitPathsMarkupWhitespaceOrNonAsciiKeys() {
        for (String key : new String[]{"../secret", "items/secret", "<script>", "items.name\nprivate", "items.name private", "items.名称"}) {
            TextProvenance texts = new TextProvenance(candidate -> "untrusted");
            String displayed = texts.onTextResource("value", key, "en", new Object[0], "value");
            Map<String,Object> rendered = texts.render(texts.capture(null, displayed, false));
            assertEquals("partial", rendered.get("translation_status"), key);
            assertEquals("invalid_resource_key", rendered.get("diagnostic"), key);
            assertNull(rendered.get("source"), key);
        }
    }
}
