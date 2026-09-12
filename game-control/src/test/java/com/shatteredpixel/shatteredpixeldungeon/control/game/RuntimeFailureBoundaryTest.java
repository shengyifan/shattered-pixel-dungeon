package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.ProtocolException;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.jupiter.api.Assertions.*;

/** No backend or frame loop: failure settlement must release queued work synchronously. */
class RuntimeFailureBoundaryTest {
    @Test void launchFailureReleasesQueuedReadsAndCertifiesUndispatchedActions() {
        GameController controller=controller();
        CompletableFuture<GameController.State> read=controller.observe();
        GameController.Execution action=controller.start("v1",map("action","wait"),"not-dispatched");
        CompletableFuture<GameController.State> cancel=controller.cancelPrepared("v1","not-dispatched");
        Throwable original=new NullPointerException("fixture primary monitor missing");
        controller.runtimeFailed(original);
        assertTrue(controller.disposed());assertSame(original,failure(read));
        assertNotExecuted(original,action.completion);assertNotExecuted(original,cancel);
        assertSame(original,failure(controller.observe()));
        assertNotExecuted(original,controller.execute("v1",map("action","wait")));
        assertNotExecuted(original,controller.cancelPrepared("v1","later"));
        controller.onDispose();assertSame(original,failure(controller.observe()),"Normal disposal cannot replace the first runtime error");
    }

    @Test void alreadyDispatchedWorkAndCancellationRemainUncertain() throws Exception {
        GameController controller=controller();
        GameController.Execution action=controller.start("v1",map("action","wait"),"already-dispatched");
        Queue<?> queue=(Queue<?>)field("queue").get(controller);
        // This fixture marks the existing dispatch boundary; it never invokes a game callback.
        field("executing").set(controller,queue.remove());
        CompletableFuture<GameController.State> cancellation=new CompletableFuture<>();
        field("cancellationCompletion").set(controller,cancellation);
        Throwable original=new IllegalStateException("fixture runtime continuation failed");
        controller.runtimeFailed(original);
        assertSame(original,failure(action.completion));assertSame(original,failure(cancellation));
        assertFalse(failure(action.completion) instanceof GameController.NotExecuted);
    }

    @Test void ordinaryDisposalStillReportsTheSessionClosedReason() {
        GameController controller=controller();CompletableFuture<GameController.State> read=controller.observe();
        controller.onDispose();Throwable error=failure(read);
        assertInstanceOf(ProtocolException.class,error);assertEquals("SESSION_CLOSED",((ProtocolException)error).code);
        assertSame(error,failure(controller.observe()));
    }

    private static GameController controller(){return new GameController(null,"menu:runtime-test",error->{throw new AssertionError(error);});}
    private static Field field(String name)throws Exception{Field field=GameController.class.getDeclaredField(name);field.setAccessible(true);return field;}
    private static Throwable failure(CompletableFuture<?> future){
        assertTrue(future.isDone(),"Runtime failure must release work without waiting for the 30-second protocol timeout");
        return assertThrows(CompletionException.class,future::join).getCause();
    }
    private static void assertNotExecuted(Throwable original,CompletableFuture<?> future){
        Throwable error=failure(future);assertInstanceOf(GameController.NotExecuted.class,error);
        assertEquals("ENGINE_ERROR",((GameController.NotExecuted)error).code);assertSame(original,error.getCause());
    }
}
