package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.ControlRequest;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import org.junit.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

public class StableControllerTest {
    @Test public void requestCountersStayDecimalAcrossDigitAndFormerRadixBoundaries() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        for(int number=1;number<=100;number++) {
            child.answer(q -> success(q,"s1","r1","completed",map()));
            Map<String,Object> result=controller.accept(map("op","state"));
            assertEquals("t3."+number,result.get("id"));
            assertEquals("t3."+number,child.sent.get(number).get("id"));
            assertTrue(result.get("id").toString().matches("t3\\.[0-9]+"));
        }
        for(int number:Arrays.asList(9,10,35,36,99,100))
            assertEquals("t3."+number,child.sent.get(number).get("id"));
    }

    @Test public void decimalIdsRemainConsumedByLocalAndServerRejection() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        for(int number=1;number<=8;number++) {
            child.answer(q -> success(q,"s1","r1","completed",map()));
            controller.accept(map("op","state"));
        }
        Map<String,Object> invalid=controller.accept(map("op","wait","rev","r1","surprise",true));
        assertEquals("INVALID_INTENT",invalid.get("err"));
        assertEquals("t3.9",object(invalid.get("request")).get("id"));
        assertEquals(9,child.sent.size());
        child.answer(q -> failure(q,"STALE_STATE"));
        Map<String,Object> rejected=controller.accept(map("op","wait","rev","r1"));
        assertEquals("t3.10",rejected.get("id"));assertEquals("STALE_STATE",rejected.get("err"));
        child.answer(q -> receipt(q,"wait","REJECTED",null));
        assertEquals("ACTION_REJECTED",controller.accept(map("op","settle")).get("err"));
        assertEquals("t3.11",child.sent.get(10).get("id"));assertEquals("t3.10",child.sent.get(10).get("rid"));
        child.answer(q -> success(q,"s1","r1","completed",map()));
        assertEquals("t3.12",controller.accept(map("op","state")).get("id"));
    }

    @Test public void aNewControllerUsesItsNewOpaquePrefixAndRestartsDecimalCounter() throws Exception {
        Set<Object> ids=new HashSet<>();
        for(String prefix:Arrays.asList("tz","t10")) {
            Fake child=new Fake();StableController controller=boot(child,prefix);
            child.answer(q -> success(q,"s1","r1","completed",map()));
            Object id=controller.accept(map("op","state")).get("id");
            assertEquals(prefix+".1",id);assertTrue(ids.add(id));
        }
    }

    @Test public void handshakeUsesUniqueRandomIdThenPersistentPrefixAndDisplayedScope() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        assertTrue(child.sent.get(0).get("id").toString().matches("h[0-9a-f]{32}"));
        child.answer(q -> success(q, "s2", "r2", "completed", map("phase", "ready")));
        Map<String,Object> response = controller.accept(map("op", "move", "rev", "r1", "dir", "N"));
        assertEquals("r2", response.get("rev"));
        assertEquals("t3.1", child.sent.get(1).get("id"));
        assertEquals("s1", child.sent.get(1).get("s"));
        assertEquals("r1", child.sent.get(1).get("rev"));
        child.answer(q -> success(q, "s2", "r3", "completed", map("phase", "ready")));
        controller.accept(map("op", "wait", "rev", "r2"));
        assertEquals("s2", child.sent.get(2).get("s"));
        assertEquals("t3.2", child.sent.get(2).get("id"));
    }

    @Test public void actionRequiresExactDisplayedRevisionAndCannotOverrideEnvelope() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        assertEquals("UNOBSERVED_REVISION", controller.accept(map("op", "wait")).get("err"));
        assertEquals("UNOBSERVED_REVISION", controller.accept(map("op", "wait", "rev", "r999")).get("err"));
        assertEquals("REVISION_SCOPE_MISMATCH", controller.accept(map("op", "wait", "rev", "r1", "s", "s9")).get("err"));
        assertEquals("INVALID_INTENT", controller.accept(map("op", "wait", "rev", "r1", "id", "mine")).get("err"));
        assertEquals(1, child.sent.size());
    }

    @Test public void locallyRejectedAllocatedIdIsNotReused() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        Map<String,Object> invalid = controller.accept(map("op", "wait", "rev", "r1", "surprise", true));
        assertEquals("INVALID_INTENT", invalid.get("err"));
        assertEquals("t3.1", object(invalid.get("request")).get("id"));
        child.answer(q -> success(q, "s1", "r2", "completed", map()));
        controller.accept(map("op", "wait", "rev", "r1"));
        assertEquals("t3.2", child.sent.get(1).get("id"));
    }

    @Test public void packedTemplateIndexCannotBecomeAGameControlOrLeavePendingWork() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        Map<String,Object> invalid = controller.accept(map("op", "click", "rev", "r1", "ctl", 2));
        assertEquals("INVALID_INTENT", invalid.get("err"));
        assertTrue(invalid.get("message").toString().contains("template index"));
        assertEquals(1, child.sent.size());
        child.answer(q -> success(q, "s1", "r2", "awaiting_input", map("phase", "awaiting_input")));
        assertEquals("awaiting_input", controller.accept(map("op", "click", "rev", "r1", "ctl", "c1")).get("st"));
        assertEquals("t3.2", child.sent.get(1).get("id"));
        assertEquals("c1", child.sent.get(1).get("ctl"));
    }

    @Test public void malformedBindingsAreRejectedLocallyWithoutCoercion() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        for (Map<String,Object> intent : Arrays.asList(
                map("op", "select", "rev", "r1"),
                map("op", "item", "rev", "r1", "loc", 0),
                map("op", "move", "rev", "r1"),
                map("op", "move", "rev", "r1", "cell", 505),
                map("op", "cell", "rev", "r1", "cell", "505"),
                map("op", "cell", "rev", "r1", "cell", 505.0),
                map("op", "cell", "rev", "r1", "cell", -1),
                map("op", "cell", "rev", "r1", "cell", 2147483648L))) {
            assertEquals("INVALID_INTENT", controller.accept(intent).get("err"));
        }
        assertEquals(1, child.sent.size());
        child.answer(q -> success(q, "s1", "r2", "completed", map()));
        assertEquals("completed", controller.accept(map("op", "cell", "rev", "r1", "cell", 0)).get("st"));
        assertEquals(0L, child.sent.get(1).get("cell"));
    }

    @Test public void invalidHandshakeDoesNotAcceptGameIntents() throws Exception {
        Fake child = new Fake();
        child.answer(q -> success(q, "s1", "r1", "completed", map("request_prefix", "uuid-unregistered")));
        StableController controller = new StableController(child, 1);
        assertEquals("INVALID_HANDSHAKE", controller.handshake().get("err"));
        assertEquals("HANDSHAKE_REQUIRED", controller.accept(map("op", "state")).get("err"));
        assertEquals(1, child.sent.size());
    }

    @Test public void oldDisplayedRevisionIsNeverSilentlyReplacedByNewObservation() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        child.answer(q -> success(q, "s1", "r2", "completed", map()));
        controller.accept(map("op", "state"));
        child.answer(q -> failure(q, "STALE_STATE"));
        assertEquals("STALE_STATE", controller.accept(map("op", "wait", "rev", "r1")).get("err"));
        assertEquals("r1", child.sent.get(2).get("rev"));
        assertEquals(3, child.sent.size());
    }

    @Test public void historicalFramesCannotInstallLiveScopeOrRevision() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        child.answer(q -> success(q, "s9", "r9", "completed", map("reply", map("rev", "r8"))));
        controller.accept(map("op", "req", "rid", "old", "s", "s9"));
        assertEquals("UNOBSERVED_REVISION", controller.accept(map("op", "wait", "rev", "r9")).get("err"));
        child.answer(q -> success(q, "s1", "r2", "completed", map()));
        controller.accept(map("op", "state"));
        assertEquals("s1", child.sent.get(2).get("s"));
    }

    @Test public void controllerPreservesOpaqueHistorySourcesAndCompressedUiWithoutExpansion() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        Map<String,Object> payload = map("raw", map("text", "{\"id\":\"long-original-id\"}\n"),
                "reply", map("s", "old-scope", "text", "雪🙂"),
                "act_templates", Arrays.asList(map("common",map("op","click"),"fields",Arrays.asList("ctl"))),
                "acts", Arrays.asList(Arrays.asList(0,"c1")),
                "ui", map("node_templates", Arrays.asList(map("common",map("role","button"),"fields",Arrays.asList("id","label","ops"))),
                        "nodes", Arrays.asList(Arrays.asList(0,"c1",1,Arrays.asList(0)))),
                "text_sources", map("label", map("resource", "do-not-rewrite", "args", Arrays.asList("s1", "r1"))));
        child.answer(q -> success(q, "s1", null, "completed", payload));
        Map<String,Object> result = controller.accept(map("op", "req", "rid", "old", "get", Arrays.asList("raw", "reply")));
        assertEquals(JsonCodec.encode(payload), JsonCodec.encode(result.get("data")));
    }

    @Test public void continuousInitialReplyIsReturnedBeforePollingAndSettleDoesNotFetchHistory() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        child.answer(q -> success(q, "s1", "a4", "in_progress", map("phase", "continuous_activity")));
        assertEquals("in_progress", controller.accept(map("op", "rest", "rev", "r1")).get("st"));
        assertEquals(Arrays.asList("info", "rest"), child.ops());
        assertEquals("OUTCOME_PENDING", controller.accept(map("op", "wait", "rev", "a4")).get("err"));
        child.answer(q -> receipt(q, "rest", "COMPLETED", map("sid", "p1")));
        child.answer(q -> success(q, "s1", "r3", "completed", map("hero", map("hp", 25))));
        Map<String,Object> settled = controller.accept(map("op", "settle"));
        assertEquals("completed", settled.get("st"));
        assertEquals("t3.1", settled.get("rid"));
        assertEquals("COMPLETED", object(object(settled.get("outcome")).get("data")).get("st"));
        assertEquals("r3", object(settled.get("observation")).get("rev"));
        assertEquals(Arrays.asList("info", "rest", "req", "state"), child.ops());
        assertEquals("s1", child.sent.get(2).get("s"));
        assertFalse(child.sent.get(2).containsKey("get"));
        assertNotEquals(child.sent.get(2).get("id"), child.sent.get(2).get("rid"));
    }

    @Test public void currentAndSettledObservationsKeepTheirOwnTemplateTablesVerbatim() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        Map<String,Object> initial = map("phase","continuous_activity",
                "act_templates",Arrays.asList(map("common",map("op","cancel"),"fields",Arrays.asList("ctl"))),
                "acts",Arrays.asList(Arrays.asList(0,"c1")),
                "ui",map("node_templates",Arrays.asList(map("common",map("role","button"),"fields",Arrays.asList("id","ops"))),
                        "nodes",Arrays.asList(Arrays.asList(0,"c1",Arrays.asList(0)))));
        child.answer(q -> success(q,"s1","a4","in_progress",initial));
        assertEquals(JsonCodec.encode(initial),JsonCodec.encode(controller.accept(map("op","rest","rev","r1")).get("data")));
        Map<String,Object> current = map("phase","player_ready",
                "act_templates",Arrays.asList(map("common",map("op","click"),"fields",Arrays.asList("ctl"))),
                "acts",Arrays.asList(Arrays.asList(0,"c2")),
                "inv_templates",Arrays.asList(map("common",map("name","Current item"),"fields",Arrays.asList("loc"))),
                "inv",Arrays.asList(Arrays.asList(0,"pack.0")),
                "ui",map("node_templates",Arrays.asList(map("common",map("role","button"),"fields",Arrays.asList("id","ops"))),
                        "nodes",Arrays.asList(Arrays.asList(0,"c2",Arrays.asList(0)))));
        child.answer(q -> receipt(q,"rest","COMPLETED",null));
        child.answer(q -> success(q,"s1","r3","completed",current));
        Map<String,Object> settled=controller.accept(map("op","settle"));
        assertEquals(JsonCodec.encode(current),JsonCodec.encode(object(settled.get("observation")).get("data")));
        assertEquals(Arrays.asList("info","rest","req","state"),child.ops());
    }

    @Test public void protocolSixChildFrameIsRejectedWithoutInstallingItsRevision() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        child.answer(q -> map("v",6,"id",q.get("id"),"s","s1","rev","r9","st","completed","data",map()));
        Map<String,Object> result=controller.accept(map("op","state"));
        assertEquals("INVALID_RESPONSE",result.get("err"));
        assertEquals("t3.1",object(result.get("request")).get("id"));
        assertEquals("UNOBSERVED_REVISION",controller.accept(map("op","wait","rev","r9")).get("err"));
        assertEquals(2,child.sent.size());
    }

    @Test public void finiteOperationDiscoversNewScopeBeforeOneFreshState() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        child.answer(q -> success(q, "s1", null, "in_progress", map("phase", "resolving")));
        controller.accept(map("op", "click", "rev", "r1", "ctl", "c1"));
        child.answer(q -> receipt(q, "click", "AWAITING_INPUT", null));
        child.answer(q -> success(q, "s2", "r3", "completed", map("request_prefix", "t3")));
        child.answer(q -> success(q, "s2", "r3", "completed", map("phase", "awaiting_input")));
        Map<String,Object> result = controller.accept(map("op", "settle"));
        assertEquals(Arrays.asList("info", "click", "req", "info", "state"), child.ops());
        assertEquals("s1", child.sent.get(2).get("s"));
        assertEquals("s2", child.sent.get(4).get("s"));
        assertTrue(result.containsKey("discovery"));
    }

    @Test public void discoveryFailureCannotBeHiddenBySuccessfulState() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        child.answer(q -> success(q, "s1", null, "in_progress", map("phase", "resolving")));
        controller.accept(map("op", "wait", "rev", "r1"));
        child.answer(q -> receipt(q, "wait", "COMPLETED", null));
        child.answer(q -> failure(q, "AUDIT_UNAVAILABLE"));
        Map<String,Object> result = controller.accept(map("op", "settle"));
        assertEquals("AUDIT_UNAVAILABLE", result.get("err"));
        assertTrue(result.containsKey("outcome"));
        assertEquals(Arrays.asList("info", "wait", "req", "info"), child.ops());
        assertEquals("OUTCOME_PENDING", controller.accept(map("op", "wait", "rev", "r1")).get("err"));
    }

    @Test public void smallPendingReceiptIsBoundedAndDoesNotRefreshState() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        child.answer(q -> success(q, "s1", "a4", "in_progress", map("phase", "continuous_activity")));
        controller.accept(map("op", "rest", "rev", "r1"));
        child.answer(q -> receipt(q, "rest", "EXECUTING", null));
        Map<String,Object> result = controller.accept(map("op", "settle", "timeout_ms", 1));
        assertEquals("pending", result.get("st"));
        assertEquals(Arrays.asList("info", "rest", "req"), child.ops());
    }

    @Test public void errorWithoutStatusIsRecognizedAndOriginalRejectionMustBeEstablished() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        child.answer(q -> failure(q, "STALE_STATE"));
        assertEquals("STALE_STATE", controller.accept(map("op", "wait", "rev", "r1")).get("err"));
        assertEquals("OUTCOME_PENDING", controller.accept(map("op", "wait", "rev", "r1")).get("err"));
        child.answer(q -> receipt(q, "wait", "REJECTED", null));
        Map<String,Object> result = controller.accept(map("op", "settle"));
        assertEquals("ACTION_REJECTED", result.get("err"));
        assertEquals("STALE_STATE", object(result.get("initial_error")).get("err"));
        assertEquals(Arrays.asList("info", "wait", "req"), child.ops());
    }

    @Test public void unknownReceiptAndWrongReceiptIdentityRemainBlocking() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        child.answer(q -> failure(q, "EXECUTION_UNKNOWN"));
        controller.accept(map("op", "wait", "rev", "r1"));
        child.answer(q -> receipt(q, "wait", "UNKNOWN", null));
        assertEquals("ACTION_UNKNOWN", controller.accept(map("op", "settle")).get("err"));
        child.answer(q -> success(q, "s1", null, "completed", map("id", "wrong", "op", "wait", "st", "COMPLETED")));
        assertEquals("INVALID_RECEIPT", controller.accept(map("op", "settle")).get("err"));
        assertEquals("OUTCOME_PENDING", controller.accept(map("op", "wait", "rev", "r1")).get("err"));
    }

    @Test public void timeoutPreservesPartialExchangeAndDelayedReplyIsNeverReplayed() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        child.answer(q -> null);
        assertEquals("RESPONSE_TIMEOUT", controller.accept(map("op", "wait", "rev", "r1")).get("err"));
        assertEquals("RESPONSE_PENDING", controller.accept(map("op", "state")).get("err"));
        child.frames.add(frame(success(child.sent.get(1), "s1", "r2", "completed", map())));
        assertEquals("r2", controller.accept(map("op", "settle")).get("rev"));
        assertEquals(Arrays.asList("info", "wait"), child.ops());
    }

    @Test public void completelyLostInitialResponseRecoversByOriginalReceiptNotActionReplay() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        child.answer(q -> null);
        assertEquals("RESPONSE_TIMEOUT", controller.accept(map("op", "wait", "rev", "r1")).get("err"));
        child.answer(q -> receipt(q, "wait", "COMPLETED", map("sid", "p2")));
        child.answer(q -> success(q, "s2", "r2", "completed", map("request_prefix", "t3")));
        child.answer(q -> success(q, "s2", "r2", "completed", map()));
        Map<String,Object> result = controller.accept(map("op", "settle"));
        assertEquals("completed", result.get("st"));
        assertEquals("RESPONSE_TIMEOUT", object(result.get("transport_error")).get("err"));
        assertEquals("t3.1", child.sent.get(2).get("rid"));
        assertEquals("t3.2", child.sent.get(2).get("id"));
        assertEquals("s1", child.sent.get(2).get("s"));
        assertEquals(Arrays.asList("info", "wait", "req", "info", "state"), child.ops());
    }

    @Test public void lateOriginalBeforeReceiptIsRetainedSeparatelyAndNeverInstallsItsRevision() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        child.answer(q -> null);
        controller.accept(map("op", "rest", "rev", "r1"));
        child.answer(q -> {
            child.frames.add(frame(success(child.sent.get(1), "s1", "a9", "in_progress", map("phase", "continuous_activity"))));
            return receipt(q, "rest", "COMPLETED", null);
        });
        child.answer(q -> success(q, "s1", "r2", "completed", map("request_prefix", "t3")));
        child.answer(q -> success(q, "s1", "r2", "completed", map()));
        Map<String,Object> result = controller.accept(map("op", "settle"));
        assertEquals("completed", result.get("st"));
        assertEquals("r2", object(result.get("observation")).get("rev"));
        Map<String,Object> late = object(((List<?>)result.get("late_responses")).get(0));
        assertEquals("t3.1", object(late.get("request")).get("id"));
        assertEquals("a9", object(late.get("response")).get("rev"));
        assertEquals("UNOBSERVED_REVISION", controller.accept(map("op", "wait", "rev", "a9")).get("err"));
        assertEquals(Arrays.asList("info", "rest", "req", "info", "state"), child.ops());
    }

    @Test public void lostSynchronousScopeChangeWithLateOriginalBeforeReceiptStillDiscoversScope() throws Exception {
        assertLostSynchronousScopeChange(true);
    }

    @Test public void lostSynchronousScopeChangeWithLateOriginalAfterReceiptStillDiscoversScope() throws Exception {
        assertLostSynchronousScopeChange(false);
    }

    private static void assertLostSynchronousScopeChange(boolean beforeReceipt) throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        child.answer(q -> null);
        assertEquals("RESPONSE_TIMEOUT", controller.accept(map("op", "click", "rev", "r1", "ctl", "c1")).get("err"));
        Map<String,Object> lateStart = success(child.sent.get(1), "s2", "r2", "completed", map("phase", "player_ready"));
        child.answer(q -> {
            if (beforeReceipt) child.frames.add(frame(lateStart));
            return receipt(q, "click", "COMPLETED", map("sid", "p1"));
        });
        child.answer(q -> {
            if (!beforeReceipt) child.frames.add(frame(lateStart));
            return success(q, "s2", "r3", "completed", map("request_prefix", "t3"));
        });
        child.answer(q -> success(q, "s2", "r3", "completed", map("phase", "player_ready")));
        Map<String,Object> result = controller.accept(map("op", "settle"));
        assertEquals("completed", result.get("st"));
        assertEquals(Arrays.asList("info", "click", "req", "info", "state"), child.ops());
        assertEquals("s1", child.sent.get(2).get("s"));
        assertEquals("t3.1", child.sent.get(2).get("rid"));
        assertEquals("s2", child.sent.get(4).get("s"));
        assertEquals("s2", object(result.get("discovery")).get("s"));
        assertEquals("r3", object(result.get("observation")).get("rev"));
        assertEquals(lateStart.get("id"), object(object(((List<?>)result.get("late_responses")).get(0)).get("response")).get("id"));
        assertEquals("UNOBSERVED_REVISION", controller.accept(map("op", "wait", "rev", "r2")).get("err"));
        child.answer(q -> success(q, "s2", "r4", "completed", map()));
        controller.accept(map("op", "wait", "rev", "r3"));
        assertEquals("s2", child.sent.get(5).get("s"));
        assertEquals("r3", child.sent.get(5).get("rev"));
    }

    @Test public void lateOriginalAfterReceiptCannotBePairedWithNewHeader() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        child.answer(q -> null);
        controller.accept(map("op", "wait", "rev", "r1"));
        child.answer(q -> receipt(q, "wait", "COMPLETED", null));
        child.answer(q -> {
            child.frames.add(frame(success(child.sent.get(1), "s1", "r9", "completed", map("old", true))));
            return success(q, "s2", "r2", "completed", map("request_prefix", "t3"));
        });
        child.answer(q -> success(q, "s2", "r2", "completed", map("old", false)));
        Map<String,Object> result = controller.accept(map("op", "settle"));
        assertEquals(false, object(object(result.get("observation")).get("data")).get("old"));
        Map<String,Object> late = object(((List<?>)result.get("late_responses")).get(0));
        assertEquals(true, object(object(late.get("response")).get("data")).get("old"));
        assertEquals("UNOBSERVED_REVISION", controller.accept(map("op", "wait", "rev", "r9")).get("err"));
    }

    @Test public void partialUnfinishedFrameDoesNotSendRecoveryQueryOrConcatenateBodies() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        child.answer(q -> null);
        controller.accept(map("op", "wait", "rev", "r1"));
        child.partial = true;
        assertEquals("RESPONSE_TIMEOUT", controller.accept(map("op", "settle")).get("err"));
        assertEquals(Arrays.asList("info", "wait"), child.ops());
        child.partial = false;
        child.frames.add(frame(success(child.sent.get(1), "s1", "r2", "completed", map())));
        assertEquals("r2", controller.accept(map("op", "settle")).get("rev"));
        assertEquals(Arrays.asList("info", "wait"), child.ops());
    }

    @Test public void lossRecoveryPreservesReceiptErrorAndDoesNotRetryUnresolvedReceiptQuery() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        child.answer(q -> null);
        controller.accept(map("op", "wait", "rev", "r1"));
        child.answer(q -> failure(q, "REQUEST_NOT_FOUND"));
        Map<String,Object> missing = controller.accept(map("op", "settle"));
        assertEquals("REQUEST_NOT_FOUND", missing.get("err"));
        assertTrue(missing.containsKey("transport_error"));
        child.answer(q -> null);
        assertEquals("RESPONSE_TIMEOUT", controller.accept(map("op", "settle", "timeout_ms", 1)).get("err"));
        int count = child.sent.size();
        assertEquals("RESPONSE_TIMEOUT", controller.accept(map("op", "settle", "timeout_ms", 1)).get("err"));
        assertEquals(count, child.sent.size());
        child.frames.add(frame(receipt(child.sent.get(count - 1), "wait", "UNKNOWN", null)));
        assertEquals("ACTION_UNKNOWN", controller.accept(map("op", "settle")).get("err"));
        assertEquals("OUTCOME_PENDING", controller.accept(map("op", "wait", "rev", "r1")).get("err"));
    }

    @Test public void malformedRecoveryFrameCannotAbandonUnresolvedReceiptIdentity() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        child.answer(q -> null);
        controller.accept(map("op", "wait", "rev", "r1"));
        child.rawAnswers.add(new StableController.Frame("{malformed}", null, false));
        assertEquals("INVALID_RESPONSE_JSON", controller.accept(map("op", "settle")).get("err"));
        assertEquals(Arrays.asList("info", "wait", "req"), child.ops());
        assertEquals("RESPONSE_TIMEOUT", controller.accept(map("op", "settle", "timeout_ms", 1)).get("err"));
        assertEquals(Arrays.asList("info", "wait", "req"), child.ops());
        child.frames.add(frame(receipt(child.sent.get(2), "wait", "UNKNOWN", null)));
        assertEquals("ACTION_UNKNOWN", controller.accept(map("op", "settle")).get("err"));
    }

    @Test public void mismatchedIdentityDoesNotConsumeExpectedResponseOrIssueNewRequest() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        child.answer(q -> success(map("id", "other"), "s1", "r9", "completed", map()));
        assertEquals("RESPONSE_ID_MISMATCH", controller.accept(map("op", "wait", "rev", "r1")).get("err"));
        child.frames.add(frame(success(child.sent.get(1), "s1", "r2", "completed", map())));
        assertEquals("r2", controller.accept(map("op", "settle")).get("rev"));
        assertEquals(2, child.sent.size());
    }

    @Test public void malformedCompleteActionReplyRecoversViaReceiptAndDiscoverWithoutReplay() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        child.rawAnswers.add(new StableController.Frame("{bad}", null, false));
        assertEquals("INVALID_RESPONSE_JSON", controller.accept(map("op", "wait", "rev", "r1")).get("err"));
        child.answer(q -> receipt(q, "wait", "COMPLETED", null));
        child.answer(q -> success(q, "s2", "r2", "completed", map("request_prefix", "t3")));
        child.answer(q -> success(q, "s2", "r2", "completed", map()));
        assertEquals("completed", controller.accept(map("op", "settle")).get("st"));
        assertEquals(Arrays.asList("info", "wait", "req", "info", "state"), child.ops());
    }

    @Test public void delayedReceiptResumesSameQueryWithoutAllocatingOrSendingAnother() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        child.answer(q -> success(q, "s1", "a2", "in_progress", map("phase", "continuous_activity")));
        controller.accept(map("op", "rest", "rev", "r1"));
        child.answer(q -> null);
        assertEquals("RESPONSE_TIMEOUT", controller.accept(map("op", "settle", "timeout_ms", 1)).get("err"));
        child.frames.add(frame(receipt(child.sent.get(2), "rest", "COMPLETED", null)));
        child.answer(q -> success(q, "s1", "r2", "completed", map()));
        assertEquals("completed", controller.accept(map("op", "settle")).get("st"));
        assertEquals(Arrays.asList("info", "rest", "req", "state"), child.ops());
    }

    @Test public void delayedStateAfterTerminalReceiptDoesNotPollOrSendStateAgain() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        child.answer(q -> success(q, "s1", "a2", "in_progress", map("phase", "continuous_activity")));
        controller.accept(map("op", "rest", "rev", "r1"));
        child.answer(q -> receipt(q, "rest", "COMPLETED", map("sid", "p8")));
        child.answer(q -> null);
        Map<String,Object> timeout = controller.accept(map("op", "settle"));
        assertEquals("RESPONSE_TIMEOUT", timeout.get("err"));
        assertTrue(timeout.containsKey("outcome"));
        child.frames.add(frame(success(child.sent.get(3), "s1", "r2", "completed", map())));
        Map<String,Object> resumed = controller.accept(map("op", "settle"));
        assertEquals("completed", resumed.get("st"));
        assertEquals("r2", object(resumed.get("observation")).get("rev"));
        assertEquals(Arrays.asList("info", "rest", "req", "state"), child.ops());
    }

    @Test public void malformedStatusFrameIsNotMistakenForCompletedAction() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        child.answer(q -> map("v", ControlRequest.PROTOCOL_VERSION, "id", q.get("id"), "st", "completed", "err", "EXECUTION_UNKNOWN"));
        assertEquals("INVALID_RESPONSE", controller.accept(map("op", "wait", "rev", "r1")).get("err"));
        assertEquals("OUTCOME_PENDING", controller.accept(map("op", "wait", "rev", "r1")).get("err"));
        assertEquals(2, child.sent.size());
    }

    @Test public void cancellationUsesDisplayedActivityAndPreservesOriginalOutcome() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        child.answer(q -> success(q, "s1", "a2", "in_progress", map("phase", "continuous_activity")));
        controller.accept(map("op", "rest", "rev", "r1"));
        child.answer(q -> success(q, "s1", "r3", "completed", map("phase", "ready")));
        controller.accept(map("op", "cancel", "rev", "a2", "rid", "t3.1"));
        assertEquals("a2", child.sent.get(2).get("rev"));
        assertEquals("t3.1", child.sent.get(2).get("rid"));
        child.answer(q -> receipt(q, "rest", "INTERRUPTED", null));
        child.answer(q -> success(q, "s1", "r3", "completed", map()));
        Map<String,Object> result = controller.accept(map("op", "settle", "rid", "t3.1"));
        assertEquals("INTERRUPTED", object(object(result.get("outcome")).get("data")).get("st"));
    }

    @Test public void quitWaitsForExitAndNeverQueriesState() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        child.answer(q -> success(q, "s1", "r2", "completed", map("persistence", map("saves", Arrays.asList(map("sid", "p7"))))));
        child.exitCode = 0;
        assertEquals("completed", controller.accept(map("op", "quit", "rev", "r1")).get("st"));
        assertEquals(1, child.exitWaits);
        assertEquals("CHILD_EXIT_PENDING", controller.accept(map("op", "state")).get("err"));
        assertEquals(Arrays.asList("info", "quit"), child.ops());
    }

    @Test public void asyncQuitSettlesReceiptThenOnlyWaitsForExitKeepingSaveEvidence() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        child.answer(q -> success(q, "s1", null, "in_progress", map("phase", "resolving")));
        controller.accept(map("op", "quit", "rev", "r1"));
        child.answer(q -> receipt(q, "quit", "COMPLETED", map("sid", "p9")));
        Map<String,Object> waiting = controller.accept(map("op", "settle"));
        assertEquals("CHILD_EXIT_TIMEOUT", waiting.get("err"));
        assertTrue(object(waiting.get("outcome")).containsKey("outcome"));
        child.exitCode = 0;
        Map<String,Object> done = controller.accept(map("op", "settle"));
        assertEquals("completed", done.get("st"));
        Map<String,Object> settled = object(done.get("outcome"));
        assertEquals("completed", settled.get("st"));
        assertEquals("p9", object(((List<?>)object(object(settled.get("outcome")).get("data")).get("save")).get(0)).get("sid"));
        assertEquals(Arrays.asList("info", "quit", "req"), child.ops());
    }

    @Test public void childEofAndWriteFailureNeverReplay() throws Exception {
        Fake child = new Fake(); StableController controller = boot(child);
        child.failWrite = true;
        assertEquals("WRITE_UNCERTAIN", controller.accept(map("op", "wait", "rev", "r1")).get("err"));
        child.failWrite = false;
        child.frames.add(new StableController.Frame(null, null, true));
        assertEquals("CHILD_EOF", controller.accept(map("op", "settle")).get("err"));
        assertEquals("RESPONSE_PENDING", controller.accept(map("op", "wait", "rev", "r1")).get("err"));
        assertEquals(Arrays.asList("info", "wait"), child.ops());
    }

    @Test public void childCommandPreservesExactPackagedOrFrozenDevArgumentVector() {
        Map<String,String> env = new HashMap<>();
        env.put("SPDCTL_NATIVE_LAUNCHER", "/bundle with spaces/spdctl");
        env.put("SPDCTL_ENGINE_ARGC", "3");
        env.put("SPDCTL_ENGINE_ARG_0", "/runtime/java");
        env.put("SPDCTL_ENGINE_ARG_1", "-Dliteral=$HOME`x`");
        env.put("SPDCTL_ENGINE_ARG_2", "Main");
        List<String> actual = StableController.childCommand(env,
                new String[]{"control", "--machine", "--data-dir", "/tmp/profile with space", "--no-terminal", "--trace-dir", "/tmp/records"},
                Paths.get("/tmp/profile with space"));
        assertEquals(Arrays.asList("/bundle with spaces/spdctl", "--engine-argc", "3", "/runtime/java", "-Dliteral=$HOME`x`", "Main", "--",
                "run", "--machine", "--data-dir", "/tmp/profile with space", "--no-terminal", "--trace-dir", "/tmp/records"), actual);
        env.put("SPDCTL_NATIVE_LAUNCHER", "relative");
        assertThrows(IllegalArgumentException.class, () -> StableController.childCommand(env, new String[]{"control", "--machine"}, Paths.get("/tmp/profile")));
    }

    @Test public void streamBuffersLargeUtf8AcrossSingleByteReadsThroughLf() throws Exception {
        String payload = "{\"text\":\"" + "雪🙂é".repeat(40000) + "\"}\n";
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        InputStream fragmented = new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(byte[] b, int off, int length) { return super.read(b, off, Math.min(length, 1)); }
        };
        StableController.StreamTransport child = new StableController.StreamTransport(fragmented, new ByteArrayOutputStream(), null);
        StableController.Frame frame = child.receive(10000);
        assertNotNull(frame); assertNull(frame.error); assertFalse(frame.eof);
        assertEquals(payload.substring(0, payload.length() - 1), frame.text);
        assertTrue(child.receive(10000).eof);
    }

    @Test public void streamTimeoutRetainsFragmentAndInvalidUtf8IsRejected() throws Exception {
        PipedInputStream in = new PipedInputStream(); PipedOutputStream out = new PipedOutputStream(in);
        StableController.StreamTransport child = new StableController.StreamTransport(in, new ByteArrayOutputStream(), null);
        byte[] snow = "雪".getBytes(StandardCharsets.UTF_8);
        out.write(snow, 0, 1); out.flush();
        assertNull(child.receive(5));
        assertTrue(child.hasPartialFrame());
        out.write(snow, 1, 2); out.write('\n'); out.flush();
        assertEquals("雪", child.receive(1000).text);
        out.write(0xff); out.write('\n'); out.flush();
        assertEquals("INVALID_RESPONSE_UTF8", child.receive(1000).error);
        out.write('{'); out.close();
        assertEquals("INCOMPLETE_RESPONSE", child.receive(1000).error);
        assertTrue(child.receive(1000).eof);
    }

    private static StableController boot(Fake child) throws Exception {
        return boot(child,"t3");
    }
    private static StableController boot(Fake child,String prefix) throws Exception {
        child.answer(q -> success(q, "s1", "r1", "completed", map("request_prefix", prefix)));
        StableController controller = new StableController(child, 1);
        assertEquals("completed", controller.handshake().get("st"));
        return controller;
    }
    private static Map<String,Object> success(Map<String,Object> q, String scope, String rev, String st, Map<String,Object> data) {
        Map<String,Object> result = map("v", ControlRequest.PROTOCOL_VERSION, "id", q.get("id"), "s", scope, "st", st, "data", data);
        if (rev != null) result.put("rev", rev);
        return result;
    }
    private static Map<String,Object> failure(Map<String,Object> q, String err) { return map("v", ControlRequest.PROTOCOL_VERSION, "id", q.get("id"), "err", err); }
    private static Map<String,Object> receipt(Map<String,Object> q, String op, String st, Object save) {
        return success(q, (String)q.get("s"), null, "completed", map("id", q.get("rid"), "op", op, "st", st, "save", save == null ? Collections.emptyList() : Arrays.asList(save)));
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) { return (Map<String,Object>)value; }
    private static StableController.Frame frame(Map<String,Object> value) { return new StableController.Frame(JsonCodec.encode(value), null, false); }

    private static final class Fake implements StableController.Transport {
        final List<Map<String,Object>> sent = new ArrayList<>();
        final Deque<Function<Map<String,Object>,Map<String,Object>>> answers = new ArrayDeque<>();
        final Deque<StableController.Frame> frames = new ArrayDeque<>(), rawAnswers = new ArrayDeque<>();
        boolean failWrite, partial;
        Integer exitCode;
        int exitWaits;
        void answer(Function<Map<String,Object>,Map<String,Object>> response) { answers.add(response); }
        List<String> ops() { List<String> ops = new ArrayList<>(); for (Map<String,Object> q : sent) ops.add((String)q.get("op")); return ops; }
        @Override public void send(String frame) throws IOException {
            Map<String,Object> q = JsonCodec.decode(frame); sent.add(q);
            if (failWrite) throw new IOException("simulated write failure");
            if (!rawAnswers.isEmpty()) frames.add(rawAnswers.remove());
            else if (!answers.isEmpty()) { Map<String,Object> response = answers.remove().apply(q); if (response != null) frames.add(frame(response)); }
        }
        @Override public StableController.Frame receive(long timeoutMillis) { return frames.poll(); }
        @Override public Integer awaitExit(long timeoutMillis) { exitWaits++; return exitCode; }
        @Override public void closeInput() { }
        @Override public boolean hasPartialFrame() { return partial; }
    }
}
