package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

public class EnglishCorpusProbeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void scansAllLeavesKeepsSceneAndGroupsRepeatedFieldsWithoutChangingInput() {
        Map<String, Object> response = map("id", "sample-request", "result", map("observation", map("ui", map(
                "scene", "HeroSelectScene", "controls", Arrays.asList(
                        map("label", "未收录探针文本甲ABCxyz123", "text", "未收录探针文本乙ABCxyz123"),
                        map("label", "未收录探针文本甲ABCxyz123", "description", "未收录探针文本丙ABCxyz123")))),
                "opaque", "未收录但不是文案字段"));
        String before = JsonCodec.encode(response);
        EnglishCorpusProbe probe = new EnglishCorpusProbe();
        probe.inspect(response, map("profile", "synthetic", "request_id", "sample-request"));
        Map<String, Object> report = probe.report();
        assertEquals(4L, report.get("unavailable_occurrences"));
        assertEquals(3, report.get("unique_issues"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> issues = (List<Map<String, Object>>) report.get("issues");
        assertEquals(2L, issues.get(0).get("frequency"));
        for (Map<String, Object> issue : issues) assertEquals("HeroSelectScene", issue.get("scene"));
        assertEquals(before, JsonCodec.encode(response));
    }

    @Test public void clippedFallbackIsSeparateAndMissingPublicSceneIsNotInvented() {
        EnglishCorpusProbe probe = new EnglishCorpusProbe();
        probe.inspect(map("result", Arrays.asList(map("text", "未收录探针残句ABCxyz123", "clipped", true))), map());
        Map<String, Object> report = probe.report();
        assertEquals(0L, report.get("unavailable_occurrences"));
        assertEquals(1L, report.get("partial_occurrences"));
        Map<?, ?> issue = (Map<?, ?>) ((List<?>) report.get("issues")).get(0);
        assertNull(issue.get("scene"));
        assertEquals(true, issue.get("clipped"));
        assertEquals("partial", issue.get("status"));
    }

    @Test public void publicParentAndActionControlContextResolveBackAndSliderOff() {
        Map<String, Object> ui = map("scene", "GameScene", "modal", true, "controls", Arrays.asList(
                map("id", "slider", "role", "slider", "minimum", 0, "maximum", 2, "value", 0),
                map("id", "off", "role", "text", "parent", "slider", "text", "关闭", "clipped", true),
                map("id", "back", "role", "button", "shortcut_action", "back", "label", "返回")));
        Map<String, Object> response = map("result", map("observation", map("ui", ui), "actions", Arrays.asList(
                map("action", "ui.activate", "control", "back", "label", "返回"))));
        String before = JsonCodec.encode(response);
        EnglishCorpusProbe probe = new EnglishCorpusProbe();
        probe.inspect(response, map());
        assertEquals(0L, probe.report().get("unavailable_occurrences"));
        assertEquals(0L, probe.report().get("partial_occurrences"));
        assertEquals(before, JsonCodec.encode(response));

        Map<?, ?> off = (Map<?, ?>) ((List<?>) ui.get("controls")).get(1);
        @SuppressWarnings("unchecked") Map<String, Object> mutableOff = (Map<String, Object>) off;
        mutableOff.put("parent", "absent-parent");
        probe.inspect(response, map());
        assertEquals(1L, probe.report().get("partial_occurrences"));
        assertEquals(0L, probe.report().get("unavailable_occurrences"));
    }

    @Test public void saveEraseNeedsFullPublicSignatureAndCacheCannotReuseOldContext() {
        List<Object> controls = new ArrayList<>(Arrays.asList(
                map("id", "continue", "role", "button", "text", "继续"),
                map("id", "erase", "role", "button", "text", "删除"),
                map("role", "text", "text", "力量"), map("role", "text", "text", "生命"),
                map("role", "text", "text", "金币收集数"), map("role", "text", "text", "最高层数")));
        Map<String, Object> ui = map("scene", "StartScene", "modal", true, "controls", controls);
        Map<String, Object> response = map("result", map("observation", map("ui", ui), "actions", Arrays.asList(
                map("action", "ui.activate", "control", "erase", "label", "删除"))));
        EnglishCorpusProbe probe = new EnglishCorpusProbe();
        probe.inspect(response, map());
        assertEquals(0L, occurrences(probe, "删除"));
        controls.remove(3); // Removing one public signature item must invalidate this disambiguation.
        probe.inspect(response, map());
        assertEquals(2L, occurrences(probe, "删除"));
    }

    @Test public void actionWithoutCorrespondingPublicUiCannotBorrowAnotherResponsesControls() {
        Map<String, Object> ui = map("scene", "TitleScene", "controls", Arrays.asList(
                map("id", "back", "role", "button", "shortcut_action", "back", "label", "返回")));
        EnglishCorpusProbe probe = new EnglishCorpusProbe();
        probe.inspect(map("result", map("observation", map("ui", ui))), map());
        assertEquals(0L, occurrences(probe, "返回"));
        probe.inspect(map("result", Arrays.asList(map("action", "ui.activate", "control", "back", "label", "返回"))), map());
        assertEquals(1L, occurrences(probe, "返回"));
        Map<?, ?> issue = (Map<?, ?>) ((List<?>) probe.report().get("issues")).get(0);
        assertNull(issue.get("scene"));
    }

    private static long occurrences(EnglishCorpusProbe probe, String original) {
        long count = 0;
        for (Object value : (List<?>) probe.report().get("issues")) {
            Map<?, ?> issue = (Map<?, ?>) value;
            if (original.equals(issue.get("original"))) count += ((Number) issue.get("frequency")).longValue();
        }
        return count;
    }

    @Test public void rejectsFormalProfilesOtherFilesAndSymbolicLinks() throws Exception {
        Path repo = temporary.getRoot().toPath();
        Path profile = repo.resolve("desktop-control/build/fixtures/closed");
        Files.createDirectories(profile);
        Files.writeString(profile.resolve("public-trace.jsonl"), "{}\n");
        Files.writeString(profile.resolve("fixture-result.json"), "{}\n");
        assertEquals(profile.resolve("public-trace.jsonl"), EnglishCorpusProbe.checkedTrace(repo,
                "desktop-control/build/fixtures/closed/public-trace.jsonl"));
        assertThrows(IllegalArgumentException.class, () -> EnglishCorpusProbe.checkedTrace(repo,
                "desktop-control/build/playthroughs/real/public-trace.jsonl"));
        assertThrows(IllegalArgumentException.class, () -> EnglishCorpusProbe.checkedTrace(repo,
                "desktop-control/build/fixtures/closed/game.dat"));
        Files.createSymbolicLink(profile.resolveSibling("linked"), profile);
        assertThrows(java.io.IOException.class, () -> EnglishCorpusProbe.checkedTrace(repo,
                "desktop-control/build/fixtures/linked/public-trace.jsonl"));
    }
}
