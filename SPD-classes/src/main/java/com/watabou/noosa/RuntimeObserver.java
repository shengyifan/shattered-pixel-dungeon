package com.watabou.noosa;

/** Optional in-process lifecycle observer. No transport or game rules live here. */
public interface RuntimeObserver {
    RuntimeObserver NONE = new RuntimeObserver() {};
    default void afterFrame() {}
    /** Render-thread hook before scene animations update; never performs disk IO. */
    default void beforeSceneUpdate() {}
    /** Render-thread scheduling gate; called under the waiting actor thread's monitor. Never perform IO here. */
    default boolean beforeActorResume() { return true; }
    default void onException(Throwable error) {}
    default void onDispose() {}
    default void onSave(String runId, int slot, Throwable error) {}
    default void onRunEnded(String runId, boolean won) {}
    default boolean exitRequested() { return true; }
}
