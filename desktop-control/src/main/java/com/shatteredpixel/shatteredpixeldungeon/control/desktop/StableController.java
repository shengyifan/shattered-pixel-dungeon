package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.ControlRequest;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.NdjsonReader;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.RequestArguments;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.WireNames;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** One serial child, no game policy, and no implicit substitution of an observed revision. */
public final class StableController {
    static final long RESPONSE_TIMEOUT_MS = 30000;
    static final long SETTLE_TIMEOUT_MS = 5000;
    private static final Set<String> TERMINAL = new HashSet<>(Arrays.asList("COMPLETED", "AWAITING_INPUT", "INTERRUPTED"));
    private static final Set<String> SUCCESS = new HashSet<>(Arrays.asList("completed", "awaiting_input", "in_progress", "interrupted"));
    private static final Set<String> HISTORY = new HashSet<>(Arrays.asList("req", "history", "events"));
    private final Transport transport;
    private final long responseTimeout;
    private final Clock clock;
    private final Map<String,String> displayedRevisions = new LinkedHashMap<>();
    private final LinkedHashMap<String,Pending> pending = new LinkedHashMap<>();
    private final Map<String,Pending> lateRequests = new LinkedHashMap<>();
    private final List<Object> lateDeliveries = new ArrayList<>();
    private String prefix, currentScope;
    private long sequence;
    private Exchange inFlight;
    private Map<String,Object> quitOutcome;
    private boolean quitting, exited;

    StableController(Transport transport, long responseTimeout) {
        this(transport, responseTimeout, new Clock() {
            public long nanoTime() { return System.nanoTime(); }
            public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
        });
    }

    StableController(Transport transport, long responseTimeout, Clock clock) {
        this.transport = transport;
        this.responseTimeout = responseTimeout;
        this.clock = clock;
    }

    static int launch(String[] args, Path profile, InputStream input, PrintStream output) throws Exception {
        return launch(args,profile,input,output,System.err);
    }

    static int launch(String[] args, Path profile, InputStream input, PrintStream output, PrintStream diagnostics) throws Exception {
        final Process process;
        try {
            // All three child descriptors must be fresh pipes. The native relay sets
            // O_NONBLOCK on them; an inherited terminal stderr can share its open-file
            // description with this JVM's stdout and make PrintStream writes fail.
            process = new ProcessBuilder(childCommand(System.getenv(), args, profile))
                    .redirectError(ProcessBuilder.Redirect.PIPE).start();
        } catch (IOException failure) {
            throw new LaunchFailure("CONTROLLER_CHILD_START_FAILED",failure);
        }
        DiagnosticForwarder errors = new DiagnosticForwarder(process.getErrorStream(),diagnostics);
        StreamTransport transport = new StreamTransport(process.getInputStream(), process.getOutputStream(), process);
        StableController controller = new StableController(transport, RESPONSE_TIMEOUT_MS);
        Throwable primary = null;
        try {
            Map<String,Object> hello = controller.handshake();
            writeFrame(output,hello);
            if (controller.prefix == null) return 1;
            NdjsonReader reader = new NdjsonReader(input);
            NdjsonReader.Frame intentFrame;
            while (!controller.exited && (intentFrame = nextIntent(reader)) != null) {
                Map<String,Object> result;
                try {
                    if (intentFrame.error != null) throw intentFrame.error;
                    result = controller.accept(JsonCodec.decode(intentFrame.text));
                }
                catch (RuntimeException invalid) { result = localError("INVALID_INTENT", invalid.getMessage(), null); }
                writeFrame(output,result);
            }
            transport.closeInput();
            Integer exit = transport.awaitExit(RESPONSE_TIMEOUT_MS);
            if (exit == null) {
                writeFrame(output,localError("CHILD_EXIT_TIMEOUT", "Child has not exited; no requests were replayed", null));
                return 1;
            }
            return exit;
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            // EOF is the ordinary lifecycle shutdown request. Never kill a child with unresolved game work.
            try { transport.closeInput(); }
            catch (IOException failure) { if(primary!=null)primary.addSuppressed(failure);else throw failure; }
            // A viewer launcher may still hold a diagnostic descriptor. Never wait
            // indefinitely for it, or for a diagnostics consumer that is not reading.
            if(!process.isAlive()) {
                try { errors.await(5000); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    if(primary!=null)primary.addSuppressed(interrupted);else throw interrupted;
                }
            }
        }
    }

    static void writeFrame(PrintStream output,Map<String,Object> frame) throws IOException {
        output.println(JsonCodec.encode(frame));
        if(output.checkError())throw new LaunchFailure("CONTROLLER_OUTPUT_FAILED",null);
    }

    private static NdjsonReader.Frame nextIntent(NdjsonReader reader) throws IOException {
        try { return reader.next(); }
        catch(IOException failure){throw new LaunchFailure("CONTROLLER_INPUT_FAILED",failure);}
    }

    /** Stage-specific public diagnosis; arbitrary exception messages and paths are not printed. */
    static final class LaunchFailure extends IOException {
        final String code;
        LaunchFailure(String code,Throwable cause){super(code,cause);this.code=code;}
    }

    static String launchDiagnostic(Throwable failure){
        String code=failure instanceof LaunchFailure?((LaunchFailure)failure).code:"CONTROLLER_FAILED";
        Throwable cause=failure.getCause()==null?failure:failure.getCause();
        return "spdctl: "+code+" ("+cause.getClass().getSimpleName()+")";
    }

    /** Bounded byte forwarding, separate from the NDJSON channel and its frame reader. */
    static final class DiagnosticForwarder {
        private final Thread worker;
        private volatile boolean consumerFailed;
        private volatile IOException readFailure;
        DiagnosticForwarder(InputStream source,PrintStream destination){
            worker=new Thread(()->{
                try(InputStream owned=source){
                    byte[] bytes=new byte[8192];int count;
                    while((count=owned.read(bytes))!=-1){
                        if(!consumerFailed){
                            destination.write(bytes,0,count);destination.flush();
                            consumerFailed=destination.checkError();
                        }
                        // The child's original stderr has already been recorded by
                        // the native relay. A broken display sink must not fill its pipe.
                    }
                }catch(IOException failure){
                    readFailure=failure;
                    if(!consumerFailed){destination.println("spdctl: CONTROLLER_DIAGNOSTIC_READ_FAILED");consumerFailed=destination.checkError();}
                }
            },"SPD Controller Diagnostics");
            worker.setDaemon(true);worker.start();
        }
        boolean await(long timeoutMillis)throws InterruptedException{
            if(timeoutMillis<1)throw new IllegalArgumentException("A positive diagnostic wait is required");
            worker.join(timeoutMillis);return !worker.isAlive();
        }
        boolean consumerFailed(){return consumerFailed;}
        IOException readFailure(){return readFailure;}
    }

    static List<String> childCommand(Map<String,String> environment, String[] args, Path profile) {
        String launcher = environment.get("SPDCTL_NATIVE_LAUNCHER");
        if (launcher == null || !Paths.get(launcher).isAbsolute())
            throw new IllegalArgumentException("Controller requires an absolute SPDCTL_NATIVE_LAUNCHER from the native launcher");
        List<String> command = new ArrayList<>();
        command.add(launcher);
        if (environment.containsKey("SPDCTL_ENGINE_ARGC")) {
            int count;
            try { count = Integer.parseInt(environment.get("SPDCTL_ENGINE_ARGC")); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid frozen engine argument count"); }
            if (count < 1 || count > 128) throw new IllegalArgumentException("Invalid frozen engine argument count");
            command.add("--engine-argc"); command.add(Integer.toString(count));
            for (int i = 0; i < count; i++) {
                String value = environment.get("SPDCTL_ENGINE_ARG_" + i);
                if (value == null) throw new IllegalArgumentException("Missing frozen engine argument");
                command.add(value);
            }
            command.add("--");
        }
        command.add("run"); command.add("--machine");
        command.add("--data-dir"); command.add(profile.toAbsolutePath().normalize().toString());
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--machine": break;
                case "--data-dir": i++; break;
                case "--no-terminal": command.add(args[i]); break;
                case "--trace-dir":
                    if (i + 1 >= args.length) throw new IllegalArgumentException("Missing trace directory");
                    command.add(args[i]); command.add(args[++i]); break;
                default: throw new IllegalArgumentException("Unknown controller argument");
            }
        }
        return command;
    }

    Map<String,Object> handshake() throws InterruptedException {
        Map<String,Object> request = map("v", ControlRequest.PROTOCOL_VERSION,
                "id", "h" + UUID.randomUUID().toString().replace("-", ""), "op", "info");
        Map<String,Object> response = exchange(request, responseTimeout);
        if (isLocalError(response) || response.containsKey("err")) return response;
        Object value = object(response.get("data")).get("request_prefix");
        if (!(value instanceof String) || !((String)value).matches("t[0-9a-z]+"))
            return localError("INVALID_HANDSHAKE", "info did not provide a persistent request_prefix", request);
        prefix = (String)value;
        observe("info", response);
        return response;
    }

    Map<String,Object> accept(Map<String,Object> intent) throws InterruptedException {
        Map<String,Object> result = acceptIntent(intent);
        if (!lateDeliveries.isEmpty()) {
            if (!result.containsKey("controller")) result = map("controller", "response", "response", result);
            result.put("late_responses", new ArrayList<>(lateDeliveries));
            lateDeliveries.clear();
        }
        return result;
    }

    private Map<String,Object> acceptIntent(Map<String,Object> intent) throws InterruptedException {
        String op = string(intent.get("op"));
        if ("settle".equals(op)) return settle(intent);
        if (exited || quitting) return localError("CHILD_EXIT_PENDING", "Only settle may wait for process exit", null);
        if (prefix == null) return localError("HANDSHAKE_REQUIRED", "No valid child handshake", null);
        if (inFlight != null) return localError("RESPONSE_PENDING", "Use settle to receive the original response before sending another request", inFlight.request);
        if (WireNames.canonicalOperation(op) == null) return localError("UNKNOWN_OPERATION", "Unknown controller operation", null);
        if (intent.containsKey("v") || intent.containsKey("id"))
            return localError("INVALID_INTENT", "The controller owns v and id; use ctl for a UI control", null);
        boolean action = !WireNames.isQuery(op);
        if (action && !pending.isEmpty() && !"cancel".equals(op))
            return localError("OUTCOME_PENDING", "Establish the original action outcome with settle before another action", pending.values().iterator().next().request);
        String scope = string(intent.get("s"));
        if (action) {
            String revision = string(intent.get("rev"));
            String observedScope = displayedRevisions.get(revision);
            if (observedScope == null) return localError("UNOBSERVED_REVISION", "Actions require an explicit revision from a displayed live observation", null);
            if (scope != null && !scope.equals(observedScope))
                return localError("REVISION_SCOPE_MISMATCH", "The supplied scope is not the displayed revision's scope", null);
            scope = observedScope;
            if ("cancel".equals(op) && !pending.containsKey(string(intent.get("rid"))))
                return localError("UNKNOWN_ACTIVITY", "cancel must identify this controller's pending action", null);
        } else if (scope == null) scope = currentScope;
        if (!"info".equals(op) && scope == null) return localError("SCOPE_REQUIRED", "Discover a live scope with info", null);
        Map<String,Object> request = request(op, scope, intent);
        // Validate the same flat grammar as the direct interface before crossing the child boundary.
        try {
            RequestArguments.validate(op, intent);
            ControlRequest.parse(JsonCodec.encode(request));
        }
        catch (RuntimeException invalid) { return localError("INVALID_INTENT", invalid.getMessage(), request); }
        if (action) pending.put((String)request.get("id"), new Pending(request));
        Map<String,Object> response = exchange(request, responseTimeout);
        return handleResponse(request, response, responseTimeout);
    }

    private Map<String,Object> handleResponse(Map<String,Object> request, Map<String,Object> response, long exitWaitBudget) throws InterruptedException {
        String op = (String)request.get("op"), id = (String)request.get("id");
        Pending action = pending.get(id);
        if (isLocalError(response)) {
            if (action != null) action.transportError = response;
            return response;
        }
        if (action != null) {
            action.initial = response;
            String phase = string(object(response.get("data")).get("phase"));
            if (!response.containsKey("err") && phase != null)
                action.discover = "resolving".equals(phase) || "cancelling".equals(phase);
            if (!response.containsKey("err") && !"in_progress".equals(response.get("st"))) pending.remove(id);
        }
        observe(op, response);
        if ("quit".equals(op) && !response.containsKey("err") && TERMINAL.contains(String.valueOf(response.get("st")).toUpperCase(Locale.ROOT))) {
            quitting = true;
            return awaitQuit(response, exitWaitBudget);
        }
        return response;
    }

    private Map<String,Object> settle(Map<String,Object> intent) throws InterruptedException {
        try { RequestArguments.validate("settle", intent); }
        catch (RuntimeException invalid) { return localError("INVALID_INTENT", invalid.getMessage(), null); }
        long duration = SETTLE_TIMEOUT_MS;
        if (intent.containsKey("timeout_ms")) {
            Object n = intent.get("timeout_ms");
            if (!(n instanceof Long || n instanceof Integer) || ((Number)n).longValue() < 1 || ((Number)n).longValue() > SETTLE_TIMEOUT_MS)
                return localError("INVALID_INTENT", "timeout_ms must be an integer from 1 to 5000", null);
            duration = ((Number)n).longValue();
        }
        long deadline = deadlineAfter(duration);
        if (quitting) return awaitQuit(null, remaining(deadline));
        String id = string(intent.get("rid"));
        if (inFlight != null && inFlight.pendingOwner == null) {
            Map<String,Object> original = inFlight.request;
            if (id != null && !id.equals(original.get("id")))
                return localError("RESPONSE_PENDING", "Receive the outstanding frame before settling another request", original);
            Pending unresolved = pending.get(original.get("id"));
            Received received = receiveUntil(deadline);
            if (received.yielded())
                return localError("RESPONSE_PENDING", "The outstanding initial response has not arrived; use settle", original);
            Map<String,Object> response = received.response;
            if (!isLocalError(response)) {
                // The delayed initial reply is shown before any polling or other operation.
                return handleResponse(original, response, remaining(deadline));
            }
            if (unresolved == null || !"RESPONSE_TIMEOUT".equals(response.get("err")) || transport.hasPartialFrame())
                return response;
            // Exceptional recovery only: query the original identity, never resend its action.
            // The byte reader retains any later complete original reply for independent delivery.
            if (unresolved.transportError == null) unresolved.transportError = response;
            lateRequests.put((String)original.get("id"), unresolved);
            inFlight = null;
            id = (String)original.get("id");
        }
        if (id == null && inFlight != null && inFlight.pendingOwner != null)
            id = (String)inFlight.pendingOwner.request.get("id");
        if (id == null) for (String candidate : pending.keySet()) id = candidate;
        Pending action = pending.get(id);
        if (action == null) return localError("NO_PENDING_ACTION", "There is no pending action with this rid", null);
        if (inFlight != null && inFlight.pendingOwner != action)
            return localError("RESPONSE_PENDING", "Receive the outstanding frame before settling another request", inFlight.request);
        boolean resumedReceipt = inFlight != null && "req".equals(inFlight.request.get("op"));
        Map<String,Object> result = map("controller", "settle", "rid", id, "s", action.request.get("s"));
        if (action.transportError != null) result.put("transport_error", action.transportError);
        if (action.initial != null && action.initial.containsKey("err")) result.put("initial_error", action.initial);
        if (action.lastReceipt != null) result.put("outcome", action.lastReceipt);
        if (action.discovery != null) result.put("discovery", action.discovery);
        while (true) {
            if (action.receipt == null) {
                Received received = stageExchange(action, "req", (String)action.request.get("s"), map("rid", id), deadline);
                if (received.yielded()) return settlePending(result, "receipt", "budget_exhausted");
                Map<String,Object> receipt = received.response;
                if (isLocalError(receipt) || receipt.containsKey("err")) {
                    if ("quit".equals(action.request.get("op")) && action.lateOriginal != null
                            && !action.lateOriginal.containsKey("err")
                            && TERMINAL.contains(String.valueOf(action.lateOriginal.get("st")).toUpperCase(Locale.ROOT))) {
                        result.put("original_response", action.lateOriginal);
                        result.put("receipt", receipt);
                        result.put("st", "completed");
                        pending.remove(id); quitting = true;
                        return awaitQuit(result, remaining(deadline));
                    }
                    return settleFailure(result, "receipt", receipt);
                }
                Map<String,Object> body = object(receipt.get("data"));
                if (!id.equals(body.get("id")) || !Objects.equals(action.request.get("op"), body.get("op")))
                    return settleFailure(result, "receipt", localError("INVALID_RECEIPT", "Receipt identity does not match the original action", action.request));
                action.lastReceipt = receipt;
                result.put("outcome", receipt);
                String status = string(body.get("st"));
                if ("RECEIVED".equals(status) || "EXECUTING".equals(status)) {
                    // Receiving a query left by the preceding call must release the
                    // serial channel, not fill the next budget with another poll.
                    if (resumedReceipt || remaining(deadline) <= 100)
                        return settlePending(result, "receipt", "action_pending");
                    clock.sleep(Math.min(100, remaining(deadline))); continue;
                }
                if (!TERMINAL.contains(status)) {
                    result.put("st", "error");
                    result.put("err", status == null ? "INVALID_RECEIPT" : "ACTION_" + status);
                    // A durable rejection is definite; UNKNOWN and all unknown states remain blocked.
                    if ("REJECTED".equals(status)) pending.remove(id);
                    return result;
                }
                action.receipt = receipt;
            }
            result.put("outcome", action.receipt);
            if ("quit".equals(action.request.get("op"))) {
                // The action is settled even while the separate process exit is
                // still pending. Keep the nested wrapper valid for every reader.
                result.put("st", "completed");
                pending.remove(id); quitting = true;
                return awaitQuit(result, remaining(deadline));
            }
            if (action.discover && action.discovery == null) {
                Received received = stageExchange(action, "info", null, Collections.emptyMap(), deadline);
                if (received.yielded()) return settlePending(result, "discovery", "budget_exhausted");
                Map<String,Object> discovery = received.response;
                if (isLocalError(discovery) || discovery.containsKey("err")) return settleFailure(result, "discovery", discovery);
                action.discovery = discovery;
                observe("info", discovery);
            }
            if (action.discovery != null) result.put("discovery", action.discovery);
            Received received = stageExchange(action, "state", currentScope, Collections.emptyMap(), deadline);
            if (received.yielded()) return settlePending(result, "observation", "budget_exhausted");
            Map<String,Object> observation = received.response;
            if (isLocalError(observation) || observation.containsKey("err")) return settleFailure(result, "observation", observation);
            observe("state", observation);
            result.put("observation", observation);
            result.put("st", "completed");
            pending.remove(id);
            return result;
        }
    }

    private Received stageExchange(Pending owner, String op, String scope, Map<String,Object> args, long deadline) throws InterruptedException {
        if (inFlight == null) {
            if (expired(deadline)) return Received.yield();
            Map<String,Object> request = request(op, scope, args);
            inFlight = new Exchange(request, owner, deadlineAfter(responseTimeout));
            return writeAndReceive(deadline);
        }
        if (inFlight.pendingOwner != owner || !op.equals(inFlight.request.get("op")))
            return Received.of(localError("RESPONSE_PENDING", "A different recovery response remains outstanding", inFlight.request));
        return receiveUntil(deadline);
    }

    private Map<String,Object> settlePending(Map<String,Object> result, String stage, String reason) {
        result.put("st", "pending");
        result.put("pending_reason", inFlight == null ? reason : "response_pending");
        result.put("stage", stage);
        if (inFlight != null) result.put("pending_request", inFlight.request);
        return result;
    }

    private static Map<String,Object> settleFailure(Map<String,Object> result, String stage, Map<String,Object> failure) {
        result.put(stage, failure); result.put("st", "error");
        result.put("err", failure.getOrDefault("err", "RECOVERY_FAILED")); return result;
    }

    private Map<String,Object> request(String op, String scope, Map<String,Object> args) {
        if (sequence == Long.MAX_VALUE) throw new IllegalStateException("Controller request sequence exhausted");
        Map<String,Object> request = map("v", ControlRequest.PROTOCOL_VERSION, "id", prefix + "." + Long.toString(++sequence));
        if (scope != null) request.put("s", scope);
        request.put("op", op);
        for (Map.Entry<String,Object> entry : args.entrySet())
            if (!Arrays.asList("v", "id", "s", "op").contains(entry.getKey())) request.put(entry.getKey(), entry.getValue());
        return request;
    }

    private void observe(String op, Map<String,Object> response) {
        if (HISTORY.contains(op) || response.containsKey("err") || isLocalError(response)) return;
        String scope = string(response.get("s")), revision = string(response.get("rev"));
        if (scope != null) currentScope = scope;
        if (revision != null && scope != null) displayedRevisions.put(revision, scope);
    }

    private Map<String,Object> exchange(Map<String,Object> request, long timeout) throws InterruptedException {
        if (inFlight != null) return localError("RESPONSE_PENDING", "An earlier response is still outstanding", inFlight.request);
        inFlight = new Exchange(request, null, deadlineAfter(timeout));
        return writeAndReceive(inFlight.responseDeadline).response;
    }

    private Received writeAndReceive(long deadline) throws InterruptedException {
        try { transport.send(JsonCodec.encode(inFlight.request) + "\n"); }
        catch (IOException failure) { return Received.of(localError("WRITE_UNCERTAIN", "Request write failed; its ID is retained and will not be replayed", inFlight.request)); }
        return receiveUntil(deadline);
    }

    private Received receiveUntil(long deadline) throws InterruptedException {
        while (true) {
            // A complete frame already queued wins over elapsed deadlines. In
            // particular, a caller can resume after a long pause without losing it.
            Frame frame = transport.receive(0);
            if (frame == null) {
                long now = clock.nanoTime();
                if (inFlight.responseDeadline - now <= 0) {
                    if (inFlight.timeoutError == null)
                        inFlight.timeoutError = localError("RESPONSE_TIMEOUT", "The original response remains outstanding; use settle", inFlight.request);
                    return Received.of(inFlight.timeoutError);
                }
                if (deadline - now <= 0) return Received.yield();
                long wait = Math.min(waitMillis(deadline, now), waitMillis(inFlight.responseDeadline, now));
                frame = transport.receive(wait);
                if (frame == null) continue;
            }
            if (frame.eof) return Received.of(localError("CHILD_EOF", "Child output ended before the outstanding response was established", inFlight.request));
            Map<String,Object> original = inFlight.request;
            // Recover an invalid initial action through its receipt, but never abandon a recovery query.
            if (frame.error != null) {
                if (inFlight.pendingOwner == null) inFlight = null;
                return Received.of(localError(frame.error, "Invalid complete child frame; preserve the original request identity", original));
            }
            Map<String,Object> response;
            try { response = JsonCodec.decode(frame.text); }
            catch (RuntimeException malformed) {
                if (inFlight.pendingOwner == null) inFlight = null;
                return Received.of(localError("INVALID_RESPONSE_JSON", "Invalid complete child JSON; establish the original request's outcome", original));
            }
            boolean valid = Long.valueOf(ControlRequest.PROTOCOL_VERSION).equals(response.get("v"))
                    && (response.containsKey("st") != response.containsKey("err"))
                    && (!response.containsKey("st") || SUCCESS.contains(response.get("st")))
                    && (!response.containsKey("err") || string(response.get("err")) != null);
            if (!Objects.equals(original.get("id"), response.get("id"))) {
                Pending late = lateRequests.get(response.get("id"));
                if (late != null && valid) {
                    lateRequests.remove(response.get("id"));
                    lateDeliveries.add(map("request", late.request, "response", response));
                    late.lateOriginal = response;
                    // This is historical evidence after receipt recovery began, never a live binding.
                    String phase = string(object(response.get("data")).get("phase"));
                    // It cannot clear discovery: a lost synchronous action can have changed scope,
                    // while currentScope deliberately still belongs to the last displayed live frame.
                    if (!response.containsKey("err") && ("resolving".equals(phase) || "cancelling".equals(phase)))
                        late.discover = true;
                    continue;
                }
                Map<String,Object> error = localError("RESPONSE_ID_MISMATCH", "Unexpected child identity; the expected response remains outstanding", original);
                error.put("unexpected_response", response);
                return Received.of(error);
            }
            if (!valid) {
                if (inFlight.pendingOwner == null) inFlight = null;
                return Received.of(localError("INVALID_RESPONSE", "Child frame must contain protocol 8, matching id, and either st or err", original));
            }
            inFlight = null;
            return Received.of(response);
        }
    }

    private Map<String,Object> awaitQuit(Map<String,Object> response, long timeout) throws InterruptedException {
        if (response != null) quitOutcome = response;
        Integer code = transport.awaitExit(timeout);
        if (code == null) return map("controller", "exit", "st", "pending", "err", "CHILD_EXIT_TIMEOUT", "outcome", quitOutcome);
        exited = true;
        if (code != 0) return map("controller", "exit", "st", "error", "err", "CHILD_EXIT_FAILED", "exit_code", code, "outcome", quitOutcome);
        if (quitOutcome != null && "settle".equals(quitOutcome.get("controller"))) {
            quitOutcome.put("st", "completed"); quitOutcome.put("exit_code", code);
        }
        return response == null ? map("controller", "exit", "st", "completed", "exit_code", code, "outcome", quitOutcome) : quitOutcome;
    }

    private long deadlineAfter(long millis) { return clock.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis); }
    private boolean expired(long deadline) { return deadline - clock.nanoTime() <= 0; }
    private long remaining(long deadline) { return Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - clock.nanoTime())); }
    private static long waitMillis(long deadline, long now) {
        long nanos = deadline - now;
        return nanos <= 0 ? 0 : 1 + (nanos - 1) / TimeUnit.MILLISECONDS.toNanos(1);
    }
    private static String string(Object value) { return value instanceof String && !((String)value).isEmpty() ? (String)value : null; }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) { return value instanceof Map ? (Map<String,Object>)value : Collections.emptyMap(); }
    private static boolean isLocalError(Map<String,Object> value) { return "error".equals(value.get("controller")); }
    private static Map<String,Object> localError(String code, String message, Map<String,Object> request) {
        Map<String,Object> error = map("controller", "error", "err", code, "message", message);
        if (request != null) error.put("request", request);
        return error;
    }

    private static final class Pending {
        final Map<String,Object> request;
        Map<String,Object> initial, receipt, lastReceipt, discovery, transportError, lateOriginal;
        // Until a trustworthy initial phase arrives, the operation might have crossed scopes.
        boolean discover = true;
        Pending(Map<String,Object> request) { this.request = request; }
    }
    private static final class Exchange {
        final Map<String,Object> request;
        final Pending pendingOwner;
        final long responseDeadline;
        Map<String,Object> timeoutError;
        Exchange(Map<String,Object> request, Pending pendingOwner, long responseDeadline) {
            this.request = request; this.pendingOwner = pendingOwner; this.responseDeadline = responseDeadline;
        }
    }
    private static final class Received {
        final Map<String,Object> response;
        private Received(Map<String,Object> response) { this.response = response; }
        static Received of(Map<String,Object> response) { return new Received(response); }
        static Received yield() { return new Received(null); }
        boolean yielded() { return response == null; }
    }

    interface Clock {
        long nanoTime();
        void sleep(long millis) throws InterruptedException;
    }

    interface Transport {
        void send(String frame) throws IOException;
        Frame receive(long timeoutMillis) throws InterruptedException;
        Integer awaitExit(long timeoutMillis) throws InterruptedException;
        void closeInput() throws IOException;
        default boolean hasPartialFrame() { return false; }
    }
    static final class Frame {
        final String text, error;
        final boolean eof;
        Frame(String text, String error, boolean eof) { this.text = text; this.error = error; this.eof = eof; }
    }

    /** Bytes are buffered through LF before strict UTF-8 decoding; timeouts never discard a partial frame. */
    static final class StreamTransport implements Transport {
        private final BlockingQueue<Frame> frames = new LinkedBlockingQueue<>();
        private final OutputStream output;
        private final Process process;
        private volatile boolean ended;
        private volatile boolean partial;
        StreamTransport(InputStream input, OutputStream output, Process process) {
            this.output = output; this.process = process;
            Thread reader = new Thread(() -> {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try {
                    byte[] chunk = new byte[8192]; int count;
                    while ((count = input.read(chunk)) != -1) {
                        for (int i = 0; i < count; i++) {
                            if (chunk[i] == '\n') {
                                try {
                                    String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
                                    frames.add(new Frame(text, null, false));
                                } catch (CharacterCodingException invalid) { frames.add(new Frame(null, "INVALID_RESPONSE_UTF8", false)); }
                                bytes.reset();
                                partial = false;
                            } else { bytes.write(chunk[i]); partial = true; }
                        }
                    }
                    if (bytes.size() != 0) frames.add(new Frame(null, "INCOMPLETE_RESPONSE", false));
                } catch (IOException failure) { frames.add(new Frame(null, "CHILD_READ_FAILED", false)); }
                finally { ended = true; frames.add(new Frame(null, null, true)); }
            }, "SPD Controller Response Reader");
            reader.setDaemon(true); reader.start();
        }
        @Override public void send(String frame) throws IOException { output.write(frame.getBytes(StandardCharsets.UTF_8)); output.flush(); }
        @Override public Frame receive(long timeoutMillis) throws InterruptedException {
            if (ended && frames.isEmpty()) return new Frame(null, null, true);
            return frames.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        }
        @Override public Integer awaitExit(long timeoutMillis) throws InterruptedException {
            return process != null && process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) ? process.exitValue() : null;
        }
        @Override public void closeInput() throws IOException { output.close(); }
        @Override public boolean hasPartialFrame() { return partial; }
    }
}
