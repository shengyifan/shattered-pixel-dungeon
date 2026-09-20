package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.control.game.PublicEnglishProjection;
import com.shatteredpixel.shatteredpixeldungeon.control.game.CompactProtocol;
import com.shatteredpixel.shatteredpixeldungeon.control.game.text.TextProvenance;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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

    @Test public void frozenOriginsTranslateLabelsWhileClippedContentAlwaysStaysPartial() {
        String back=TextProvenance.INSTANCE.onTextResource("返回","windows.wndupgrade.back","zh",new Object[0]);
        String off=TextProvenance.INSTANCE.onTextResource("关闭","windows.wndsettings$displaytab.off","zh",new Object[0]);
        Map<String,Object> response=PublicEnglishProjection.copy(map("result",List.of(
                map("role","button","label",back),map("role","text","text",off,"clipped",true))));
        String before=JsonCodec.encode(response);
        EnglishCorpusProbe probe=new EnglishCorpusProbe();probe.inspect(response,map());
        assertEquals(0L,probe.report().get("unavailable_occurrences"));
        assertEquals(1L,probe.report().get("partial_occurrences"));
        assertEquals(before,JsonCodec.encode(response));
    }

    @Test public void cacheCannotReuseAFormerOriginForTheSameSurfaceText() {
        String erase=TextProvenance.INSTANCE.onTextResource("删除","windows.wndgameinprogress.erase","zh",new Object[0]);
        Map<String,Object> complete=PublicEnglishProjection.copy(map("role","button","text",erase));
        String displayed=(String)complete.get("text");
        EnglishCorpusProbe probe=new EnglishCorpusProbe();
        probe.inspect(map("result",complete),map());
        assertEquals(0L,occurrences(probe,displayed));
        probe.inspect(map("result",map("role","button","text",new String(displayed))),map());
        assertEquals(1L,occurrences(probe,displayed));
    }

    @Test public void actionTextWithoutItsOwnSourceCannotBorrowAnotherResponsesControls() {
        String back=TextProvenance.INSTANCE.onTextResource("返回","windows.wndupgrade.back","zh",new Object[0]);
        Map<String,Object> action=PublicEnglishProjection.copy(map("action","ui.activate","control","back","label",back));
        EnglishCorpusProbe probe=new EnglishCorpusProbe();
        probe.inspect(map("result",action),map());
        assertEquals(0L,probe.report().get("unavailable_occurrences"));
        probe.inspect(map("result",map("action","ui.activate","control","back","label","返回")),map());
        assertEquals(1L,occurrences(probe,"返回"));
        Map<?,?> issue=(Map<?,?>)((List<?>)probe.report().get("issues")).get(0);
        assertNull(issue.get("scene"));
    }

    @Test public void neighboringPartialFieldsDoNotContaminateACompleteFieldDiagnostic() {
        String label=TextProvenance.INSTANCE.onTextResource("返回","windows.wndupgrade.back","zh",new Object[0]);
        Map<String,Object> rendered=PublicEnglishProjection.copy(map("label",label,"description","无来源的新说明"));
        EnglishCorpusProbe probe=new EnglishCorpusProbe();probe.inspect(map("result",rendered),map());
        assertEquals(1L,probe.report().get("unavailable_occurrences"));
        Map<?,?> issue=(Map<?,?>)((List<?>)probe.report().get("issues")).get(0);
        assertEquals("description",issue.get("field"));
    }

    @Test public void realV7OutputPreservesCompactPathsAndRecordedPartialDiagnosticsWithSourcesOptional() {
        String back=TextProvenance.INSTANCE.onTextResource("返回","windows.wndupgrade.back","zh",new Object[0]);
        String note=TextProvenance.INSTANCE.onTextOperation("user","用户自定义文字","用户自定义文字");
        Map<String,Object> rendered=PublicEnglishProjection.copy(map("scope_id","run:fixture","state_version","rev:1",
                "phase","player_ready","observation",map("ui",map("scene","HeroSelectScene","controls",List.of(
                        map("id","good","role","button","text",back),
                        map("id","bad","role","button","description","缺失来源的说明"),
                        map("id","cut","role","text","text","缺失来源的残句","clipped",true),
                        map("id","note","role","text","text",note))))));
        for(boolean sources:List.of(false,true)) {
            Map<String,Object> response=CompactProtocol.success("wire","run:fixture","completed",rendered,true,sources);
            String before=JsonCodec.encode(response);
            EnglishCorpusProbe probe=new EnglishCorpusProbe();probe.inspect(response,map("request_id","wire"));
            assertEquals(1L,probe.report().get("protocol_7_frames"));
            assertEquals(1L,probe.report().get("unavailable_occurrences"));
            assertEquals(1L,probe.report().get("partial_occurrences"));
            assertEquals(0L,occurrences(probe,"用户自定义文字"));
            List<?> issues=(List<?>)probe.report().get("issues");
            for(Object value:issues) {
                Map<?,?> issue=(Map<?,?>)value;
                assertEquals("HeroSelectScene",issue.get("scene"));
                assertTrue(issue.get("field_path").toString().startsWith("/response/data/ui/nodes/*/"));
                Map<?,?> sample=(Map<?,?>)((List<?>)issue.get("samples")).get(0);
                assertEquals(true,sample.get("complete_public_ui_available"));
                assertNotNull(((Map<?,?>)sample.get("node_metadata")).get("id"));
            }
            assertTrue(issues.stream().anyMatch(value->"desc".equals(((Map<?,?>)value).get("field"))));
            assertEquals(before,JsonCodec.encode(response));
        }
    }

    @Test public void v7DefaultsDoNotInventMissingSourcesAndAggregateDiagnosticsRetainWirePointers() {
        Map<String,Object> node=map("id","same","role","text","text","Already rendered text");
        EnglishCorpusProbe probe=new EnglishCorpusProbe();
        probe.inspect(map("v",7,"data",map("ui",map("scene","TitleScene","nodes",List.of(node)))),map());
        assertEquals(0L,probe.report().get("unavailable_occurrences"));
        Map<String,Object> partial=map("v",7,"data",map("ui",map("scene","TitleScene","nodes",List.of(node))),
                "pres",map("st","partial","diag",List.of(map("field","$.data.ui.nodes[0].text","code","source_missing"))),
                "raw",map("description","opaque request text"),"reply",map("description","opaque historical reply text"));
        String before=JsonCodec.encode(partial);
        probe.inspect(partial,map());
        assertEquals(1L,probe.report().get("unavailable_occurrences"));
        Map<?,?> issue=(Map<?,?>)((List<?>)probe.report().get("issues")).get(0);
        Map<?,?> sample=(Map<?,?>)((List<?>)issue.get("samples")).get(0);
        assertEquals("/response/data/ui/nodes/0/text",sample.get("json_pointer"));
        assertEquals("source_missing",issue.get("reason"));
        assertEquals(before,JsonCodec.encode(partial));
    }

    @Test public void v7StructureTablesExposeTheSamePublicTextWithoutMutatingWire() {
        Map<String,Object> expanded=map("v",7,"data",map("acts",List.of(map("op","click","ctl","c1")),"ui",map("scene","TitleScene","nodes",List.of(
                map("id","c1","role","button","label","Visible choice","ops",List.of(map("op","click")))))));
        Map<String,Object> compressed=map("v",7,"data",map(
                "act_templates",List.of(map("common",map("op","click"),"fields",List.of("ctl"))),
                "acts",List.of(List.of(0,"c1")),"ui",map("scene","TitleScene",
                "node_templates",List.of(map("common",map("role","button"),"fields",List.of("id","label","ops"))),
                "nodes",List.of(List.of(0,"c1","Visible choice",List.of(0))))));
        String bytes=JsonCodec.encode(compressed);
        EnglishCorpusProbe expected=new EnglishCorpusProbe(),actual=new EnglishCorpusProbe();
        expected.inspect(expanded,map()); actual.inspect(compressed,map());
        assertEquals(expected.report(),actual.report());
        assertEquals(bytes,JsonCodec.encode(compressed));
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
