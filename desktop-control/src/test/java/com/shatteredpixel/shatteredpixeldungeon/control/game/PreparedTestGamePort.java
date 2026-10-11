package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.desktop.MachineSession;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Adapts established isolated session fixtures to the mandatory prepared-action seam.
 * Production has no legacy fallback. Preparation reads the fake's boundary directly,
 * while its existing action/save/cancel behavior runs only after the session commits audit. */
interface PreparedTestGamePort extends MachineSession.GamePort {
    void prepareRun(String id);

    @Override default GameController.ActionPreparation prepareAction(String scope,String version,
            Map<String,Object> args,String requestId,String plannedRun) {
        GameController.State before=latest();
        CompletableFuture<GameController.State> prepared=new CompletableFuture<>();
        if(before==null||!Objects.equals(scope,before.scopeId))
            prepared.completeExceptionally(new GameController.NotExecuted("SCOPE_MISMATCH",null));
        else if(!Objects.equals(version,before.version))
            prepared.completeExceptionally(new GameController.NotExecuted("STALE_STATE",null));
        else prepared.complete(before);
        return new GameController.ActionPreparation(scope,version,args,requestId,plannedRun,prepared,
                new GameController.Execution(new CompletableFuture<>(),new CompletableFuture<>()));
    }

    @Override default GameController.Execution commitPreparedAction(GameController.ActionPreparation prepared) {
        prepared.preparedBefore.join();
        if(prepared.plannedRun!=null)prepareRun(prepared.plannedRun);
        return start(prepared.version,prepared.args,prepared.requestId);
    }

    @Override default void abortPreparedAction(GameController.ActionPreparation prepared) { }
}
