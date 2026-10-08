package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.ControlRequest;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

/** Deadline tests share one deterministic clock with all transport waits and sends. */
public class StableControllerDeadlineTest {
    @Test public void fiveSecondPollingBudgetYieldsTheFinal410MillisecondReceiptWithoutCallingItAnError() throws Exception {
        Harness h = new Harness(30000);
        h.rest();
        // Each write takes 1 ms; each receipt then takes 410 ms to arrive. With
        // 100 ms poll sleeps, the tenth response arrives at 5010 ms, after the slice.
        for (int i = 0; i < 10; i++) h.child.answer(1, 410, q -> receipt(q, "rest", "EXECUTING"));

        Map<String,Object> first = h.settle(5000);
        assertPending(first, "response_pending", "receipt");
        assertEquals(5000, h.clock.millis());
        assertEquals(12, h.child.sent.size());
        assertRequest(h.child.sent.get(11), first.get("pending_request"));
        assertEquals("t3.11", object(first.get("pending_request")).get("id"));
        assertEquals("t3.1", object(first.get("pending_request")).get("rid"));

        Map<String,Object> second = h.settle(5000);
        assertPending(second, "action_pending", "receipt");
        assertEquals("EXECUTING", object(object(second.get("outcome")).get("data")).get("st"));
        assertEquals("Receiving an outstanding pending receipt must not start another poll cycle", 5010, h.clock.millis());
        assertEquals(12, h.child.sent.size());
    }

    @Test public void delayedExecutingReceiptYieldsThenReleasesTheChannelWithoutAnotherPoll() throws Exception {
        Harness h = new Harness(1000);
        h.rest();
        h.child.answer(410, q -> receipt(q, "rest", "EXECUTING"));

        Map<String,Object> first = h.settle(100);
        assertPending(first, "response_pending", "receipt");
        assertEquals(100, h.clock.millis());
        assertRequest(h.child.sent.get(2), first.get("pending_request"));
        assertEquals("t3.1", first.get("rid"));
        assertEquals("t3.2", object(first.get("pending_request")).get("id"));
        assertEquals("RESPONSE_PENDING", h.controller.accept(map("op", "state")).get("err"));
        assertEquals("RESPONSE_PENDING", h.controller.accept(map("op", "cancel", "rev", "a2", "rid", "t3.1")).get("err"));
        assertEquals(Arrays.asList("info", "rest", "req"), h.child.ops());

        Map<String,Object> second = h.settle(500);
        assertPending(second, "action_pending", "receipt");
        assertEquals("EXECUTING", object(object(second.get("outcome")).get("data")).get("st"));
        assertEquals(410, h.clock.millis());
        assertEquals(Arrays.asList("info", "rest", "req"), h.child.ops());

        h.child.answer(0, q -> success(q, "s1", "a3", "completed", map("phase", "continuous_activity")));
        assertEquals("a3", h.controller.accept(map("op", "state")).get("rev"));
        assertEquals("OUTCOME_PENDING", h.controller.accept(map("op", "wait", "rev", "a3")).get("err"));
        h.child.answer(0, q -> success(q, "s1", "r4", "completed", map("phase", "player_ready")));
        assertEquals("completed", h.controller.accept(map("op", "cancel", "rev", "a2", "rid", "t3.1")).get("st"));
        assertEquals("a2", h.child.sent.get(4).get("rev"));
        assertEquals("t3.1", h.child.sent.get(4).get("rid"));
        assertEquals(Arrays.asList("info", "rest", "req", "state", "cancel"), h.child.ops());
    }

    @Test public void shortSettleCallsDoNotRenewAnOutstandingExchangesHardDeadline() throws Exception {
        Harness h = new Harness(300);
        h.rest();
        h.child.noReply(0);

        Map<String,Object> first = h.settle(100);
        assertPending(first, "response_pending", "receipt");
        Map<String,Object> request = object(first.get("pending_request"));
        Map<String,Object> second = h.settle(100);
        assertPending(second, "response_pending", "receipt");
        assertEquals(request, object(second.get("pending_request")));
        assertEquals(200, h.clock.millis());

        Map<String,Object> expired = h.settle(100);
        assertEquals("RESPONSE_TIMEOUT", expired.get("err"));
        assertEquals(request, object(object(expired.get("receipt")).get("request")));
        assertEquals(300, h.clock.millis());
        assertEquals("RESPONSE_TIMEOUT", h.settle(100).get("err"));
        assertEquals(300, h.clock.millis());
        assertEquals(Arrays.asList("info", "rest", "req"), h.child.ops());
    }

    @Test public void alreadyQueuedReplyIsPolledBeforeRejectingAnExpiredExchange() throws Exception {
        Harness h = new Harness(300);
        h.rest();
        h.child.answer(350, q -> receipt(q, "rest", "EXECUTING"));
        assertPending(h.settle(100), "response_pending", "receipt");
        h.clock.advance(300);
        int reads = h.child.receiveTimeouts.size();

        Map<String,Object> result = h.settle(1);
        assertPending(result, "action_pending", "receipt");
        assertEquals(400, h.clock.millis());
        assertEquals(Long.valueOf(0), h.child.receiveTimeouts.get(reads));
        assertEquals(Arrays.asList("info", "rest", "req"), h.child.ops());
    }

    @Test public void sendingConsumesTheSliceAndDoesNotMoveTheHardDeadline() throws Exception {
        Harness h = new Harness(300);
        h.rest();
        h.child.noReply(120);

        assertPending(h.settle(100), "response_pending", "receipt");
        assertEquals("A send that exceeds the slice must not be followed by another timed wait", 120, h.clock.millis());
        assertPending(h.settle(100), "response_pending", "receipt");
        assertEquals(220, h.clock.millis());
        assertEquals("RESPONSE_TIMEOUT", h.settle(100).get("err"));
        assertEquals("The hard deadline starts before the send", 300, h.clock.millis());
        assertEquals(Arrays.asList("info", "rest", "req"), h.child.ops());
    }

    @Test public void terminalReceiptAtSliceEndRetainsOutcomeAndDefersDiscovery() throws Exception {
        Harness h = new Harness(1000);
        h.resolving();
        h.child.answer(100, q -> receipt(q, "wait", "COMPLETED"));

        Map<String,Object> first = h.settle(100);
        assertPending(first, "budget_exhausted", "discovery");
        assertEquals("COMPLETED", object(object(first.get("outcome")).get("data")).get("st"));
        assertEquals(Arrays.asList("info", "wait", "req"), h.child.ops());
        h.child.answer(0, q -> success(q, "s2", "r3", "completed", map("request_prefix", "t3")));
        h.child.answer(0, q -> success(q, "s2", "r3", "completed", map("phase", "player_ready")));
        Map<String,Object> second = h.settle(100);
        assertEquals("completed", second.get("st"));
        assertEquals(first.get("outcome"), second.get("outcome"));
        assertEquals("s2", h.child.sent.get(4).get("s"));
        assertEquals(Arrays.asList("info", "wait", "req", "info", "state"), h.child.ops());
    }

    @Test public void completedDiscoveryAtSliceEndIsKeptWhileOnlyStateRemains() throws Exception {
        Harness h = new Harness(1000);
        h.resolving();
        h.child.answer(0, q -> receipt(q, "wait", "COMPLETED"));
        h.child.answer(100, q -> success(q, "s2", "r3", "completed", map("request_prefix", "t3")));

        Map<String,Object> first = h.settle(100);
        assertPending(first, "budget_exhausted", "observation");
        assertEquals("s2", object(first.get("discovery")).get("s"));
        assertEquals(Arrays.asList("info", "wait", "req", "info"), h.child.ops());
        h.child.answer(0, q -> success(q, "s2", "r4", "completed", map("phase", "player_ready")));
        Map<String,Object> second = h.settle(100);
        assertEquals("completed", second.get("st"));
        assertEquals(first.get("discovery"), second.get("discovery"));
        assertEquals("r4", object(second.get("observation")).get("rev"));
        assertEquals(Arrays.asList("info", "wait", "req", "info", "state"), h.child.ops());
    }

    @Test public void delayedDiscoveryRetainsItsRequestAndContinuesInTheDiscoveredScope() throws Exception {
        Harness h = new Harness(1000);
        h.resolving();
        h.child.answer(0, q -> receipt(q, "wait", "COMPLETED"));
        h.child.answer(150, q -> success(q, "s2", "r3", "completed", map("request_prefix", "t3")));

        Map<String,Object> first = h.settle(100);
        assertPending(first, "response_pending", "discovery");
        assertRequest(h.child.sent.get(3), first.get("pending_request"));
        h.child.answer(0, q -> success(q, "s2", "r4", "completed", map("phase", "player_ready")));
        Map<String,Object> second = h.settle(100);
        assertEquals("completed", second.get("st"));
        assertEquals(150, h.clock.millis());
        assertEquals("s2", h.child.sent.get(4).get("s"));
        assertEquals(Arrays.asList("info", "wait", "req", "info", "state"), h.child.ops());
    }

    @Test public void delayedObservationRetainsOutcomeAndResumesTheSameStateRequest() throws Exception {
        Harness h = new Harness(1000);
        h.rest();
        h.child.answer(0, q -> receipt(q, "rest", "COMPLETED"));
        h.child.answer(150, q -> success(q, "s1", "r3", "completed", map("phase", "player_ready")));

        Map<String,Object> first = h.settle(100);
        assertPending(first, "response_pending", "observation");
        assertRequest(h.child.sent.get(3), first.get("pending_request"));
        assertEquals("COMPLETED", object(object(first.get("outcome")).get("data")).get("st"));
        Map<String,Object> second = h.settle(100);
        assertEquals("completed", second.get("st"));
        assertEquals(first.get("outcome"), second.get("outcome"));
        assertEquals("r3", object(second.get("observation")).get("rev"));
        assertEquals(150, h.clock.millis());
        assertEquals(Arrays.asList("info", "rest", "req", "state"), h.child.ops());
    }

    @Test public void lateOriginalAtSliceBoundaryRemainsSeparateFromTheOutstandingReceipt() throws Exception {
        Harness h = new Harness(300);
        h.child.answer(400, q -> success(q, "s1", "a9", "in_progress", map("phase", "continuous_activity")));
        assertEquals("RESPONSE_TIMEOUT", h.controller.accept(map("op", "rest", "rev", "r1")).get("err"));
        h.child.answer(150, q -> receipt(q, "rest", "EXECUTING"));

        Map<String,Object> first = h.settle(100);
        assertPending(first, "response_pending", "receipt");
        assertEquals("t3.1", first.get("rid"));
        assertEquals("t3.2", object(first.get("pending_request")).get("id"));
        List<?> late = (List<?>) first.get("late_responses");
        assertNotNull(late);
        assertEquals(1, late.size());
        assertEquals("t3.1", object(object(late.get(0)).get("response")).get("id"));
        assertEquals("a9", object(object(late.get(0)).get("response")).get("rev"));
        Map<String,Object> second = h.settle(100);
        assertPending(second, "action_pending", "receipt");
        assertFalse(second.containsKey("late_responses"));
        assertEquals(Arrays.asList("info", "rest", "req"), h.child.ops());
    }

    @Test public void queuedInitialQuitReplyWaitsForExitOnlyWithinTheCurrentSettleBudget() throws Exception {
        Harness h = new Harness(300);
        h.child.answer(301, q -> success(q, "s1", "r2", "completed",
                map("persistence", map("saves", Arrays.asList(map("sid", "p7"))))));
        assertEquals("RESPONSE_TIMEOUT", h.controller.accept(map("op", "quit", "rev", "r1")).get("err"));
        assertEquals(300, h.clock.millis());
        assertTrue(h.child.exitWaits.isEmpty());
        // The exchange deadline stays expired. Only an already queued response
        // may be received; settle does not extend that deadline to await it.
        h.clock.advance(1);

        Map<String,Object> first = h.settle(1);
        assertEquals("exit", first.get("controller"));
        assertEquals("pending", first.get("st"));
        assertEquals("CHILD_EXIT_TIMEOUT", first.get("err"));
        assertEquals(Arrays.asList(1L), h.child.exitWaits);
        assertEquals(302, h.clock.millis());
        Map<String,Object> original = object(first.get("outcome"));
        assertEquals("t3.1", original.get("id"));
        assertEquals("completed", original.get("st"));
        assertEquals("r2", original.get("rev"));
        assertEquals("p7", object(((List<?>) object(object(original.get("data")).get("persistence")).get("saves")).get(0)).get("sid"));
        assertEquals(Arrays.asList("info", "quit"), h.child.ops());

        Map<String,Object> second = h.settle(1);
        assertEquals("exit", second.get("controller"));
        assertEquals("pending", second.get("st"));
        assertEquals(original, second.get("outcome"));
        assertEquals(Arrays.asList(1L, 1L), h.child.exitWaits);
        assertEquals(303, h.clock.millis());
        assertEquals(Arrays.asList("info", "quit"), h.child.ops());
    }

    @Test public void consumingQueuedInitialQuitReplyCanLeaveZeroExitWaitBudget() throws Exception {
        Harness h = new Harness(300);
        h.child.answer(301, q -> success(q, "s1", "r2", "completed", map("phase", "ready")));
        assertEquals("RESPONSE_TIMEOUT", h.controller.accept(map("op", "quit", "rev", "r1")).get("err"));
        h.clock.advance(1);
        // Model one millisecond spent delivering an already queued frame. This
        // is processing time, not permission to await an expired exchange.
        h.child.nextDeliveryMillis = 1;

        Map<String,Object> first = h.settle(1);
        assertEquals("exit", first.get("controller"));
        assertEquals("pending", first.get("st"));
        assertEquals("CHILD_EXIT_TIMEOUT", first.get("err"));
        assertEquals(Arrays.asList(0L), h.child.exitWaits);
        assertEquals(302, h.clock.millis());
        Map<String,Object> original = object(first.get("outcome"));
        assertEquals("t3.1", original.get("id"));
        assertEquals("completed", original.get("st"));
        assertEquals(Arrays.asList("info", "quit"), h.child.ops());

        Map<String,Object> second = h.settle(1);
        assertEquals("pending", second.get("st"));
        assertEquals(original, second.get("outcome"));
        assertEquals(Arrays.asList(0L, 1L), h.child.exitWaits);
        assertEquals(303, h.clock.millis());
        assertEquals(Arrays.asList("info", "quit"), h.child.ops());
    }

    private static void assertPending(Map<String,Object> result, String reason, String stage) {
        assertEquals(result.toString(), "settle", result.get("controller"));
        assertEquals(result.toString(), "pending", result.get("st"));
        assertEquals(result.toString(), reason, result.get("pending_reason"));
        assertEquals(result.toString(), stage, result.get("stage"));
        assertFalse(result.toString(), result.containsKey("err"));
        assertEquals(result.toString(), "response_pending".equals(reason), result.containsKey("pending_request"));
    }

    private static void assertRequest(Map<String,Object> sent, Object pendingRequest) {
        // Decoding the actual wire turns integer values into Long; compare the
        // complete serialized requests instead of depending on Java number classes.
        assertEquals(JsonCodec.encode(sent), JsonCodec.encode(object(pendingRequest)));
    }

    private static final class Harness {
        final FakeClock clock = new FakeClock();
        final TimedTransport child = new TimedTransport(clock);
        final StableController controller;

        Harness(long responseTimeout) throws Exception {
            child.answer(0, q -> success(q, "s1", "r1", "completed", map("request_prefix", "t3")));
            controller = new StableController(child, responseTimeout, clock);
            assertEquals("completed", controller.handshake().get("st"));
        }

        void rest() throws Exception {
            child.answer(0, q -> success(q, "s1", "a2", "in_progress", map("phase", "continuous_activity")));
            assertEquals("in_progress", controller.accept(map("op", "rest", "rev", "r1")).get("st"));
        }

        void resolving() throws Exception {
            child.answer(0, q -> success(q, "s1", null, "in_progress", map("phase", "resolving")));
            assertEquals("in_progress", controller.accept(map("op", "wait", "rev", "r1")).get("st"));
        }

        Map<String,Object> settle(long duration) throws Exception {
            return controller.accept(map("op", "settle", "timeout_ms", duration));
        }
    }

    private static final class FakeClock implements StableController.Clock {
        long now;
        @Override public long nanoTime() { return now; }
        @Override public void sleep(long millis) { advance(millis); }
        void advance(long millis) { now += TimeUnit.MILLISECONDS.toNanos(millis); }
        long millis() { return TimeUnit.NANOSECONDS.toMillis(now); }
    }

    private static final class TimedTransport implements StableController.Transport {
        final FakeClock clock;
        final List<Map<String,Object>> sent = new ArrayList<>();
        final List<Long> receiveTimeouts = new ArrayList<>();
        final List<Long> exitWaits = new ArrayList<>();
        final Deque<SendPlan> plans = new ArrayDeque<>();
        final PriorityQueue<ScheduledFrame> frames = new PriorityQueue<>(Comparator
                .comparingLong((ScheduledFrame frame) -> frame.at).thenComparingLong(frame -> frame.order));
        long order, nextDeliveryMillis;

        TimedTransport(FakeClock clock) { this.clock = clock; }
        void answer(long delay, Function<Map<String,Object>,Map<String,Object>> reply) {
            answer(0, delay, reply);
        }
        void answer(long sendMillis, long delay, Function<Map<String,Object>,Map<String,Object>> reply) {
            plans.add(new SendPlan(sendMillis, delay, reply));
        }
        void noReply(long sendMillis) { plans.add(new SendPlan(sendMillis, 0, null)); }
        List<String> ops() {
            List<String> result = new ArrayList<>();
            for (Map<String,Object> request : sent) result.add((String) request.get("op"));
            return result;
        }

        @Override public void send(String text) {
            Map<String,Object> request = JsonCodec.decode(text);
            sent.add(request);
            assertFalse("Unexpected request: " + request, plans.isEmpty());
            SendPlan plan = plans.remove();
            clock.advance(plan.sendMillis);
            if (plan.reply != null) {
                Map<String,Object> reply = plan.reply.apply(request);
                frames.add(new ScheduledFrame(clock.nanoTime() + TimeUnit.MILLISECONDS.toNanos(plan.replyDelay), order++,
                        new StableController.Frame(JsonCodec.encode(reply), null, false)));
            }
        }

        @Override public StableController.Frame receive(long timeoutMillis) {
            assertTrue("Negative transport timeout", timeoutMillis >= 0);
            receiveTimeouts.add(timeoutMillis);
            long deadline = clock.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            ScheduledFrame next = frames.peek();
            if (next != null && next.at <= deadline) {
                clock.now = Math.max(clock.nanoTime(), next.at);
                clock.advance(nextDeliveryMillis);
                nextDeliveryMillis = 0;
                return frames.remove().frame;
            }
            clock.now = deadline;
            return null;
        }

        @Override public Integer awaitExit(long timeoutMillis) {
            assertTrue("Negative exit wait timeout", timeoutMillis >= 0);
            exitWaits.add(timeoutMillis);
            clock.advance(timeoutMillis);
            return null;
        }
        @Override public void closeInput() { }
    }

    private static final class SendPlan {
        final long sendMillis, replyDelay;
        final Function<Map<String,Object>,Map<String,Object>> reply;
        SendPlan(long sendMillis, long replyDelay, Function<Map<String,Object>,Map<String,Object>> reply) {
            this.sendMillis = sendMillis;
            this.replyDelay = replyDelay;
            this.reply = reply;
        }
    }

    private static final class ScheduledFrame {
        final long at, order;
        final StableController.Frame frame;
        ScheduledFrame(long at, long order, StableController.Frame frame) {
            this.at = at;
            this.order = order;
            this.frame = frame;
        }
    }

    private static Map<String,Object> success(Map<String,Object> request, String scope, String revision,
                                              String status, Map<String,Object> data) {
        Map<String,Object> result = map("v", ControlRequest.PROTOCOL_VERSION, "id", request.get("id"),
                "s", scope, "st", status, "data", data);
        if (revision != null) result.put("rev", revision);
        return result;
    }

    private static Map<String,Object> receipt(Map<String,Object> request, String operation, String status) {
        return success(request, (String) request.get("s"), null, "completed", map("id", request.get("rid"),
                "op", operation, "st", status, "save", Collections.emptyList()));
    }

    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {
        assertTrue("Expected object: " + value, value instanceof Map);
        return (Map<String,Object>) value;
    }
}
