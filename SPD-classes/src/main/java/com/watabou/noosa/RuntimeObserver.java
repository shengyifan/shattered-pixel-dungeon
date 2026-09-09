package com.watabou.noosa;

import java.util.List;
import java.util.Objects;

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
    /** Snapshot of text that an existing game log control has just drawn, never a raw log signal. */
    default void onGameLog(String contextId, List<LogEntry> entries) {}
    default boolean observesVisualCues() { return false; }
    /** One completed GameScene draw. The identity token is opaque and must never be serialized. */
    default void onVisualCues(String runId, Object levelIdentity, int depth, List<VisualCue> cues) {
        onVisualCues(runId,levelIdentity,depth,cues,true);
    }
    default void onVisualCues(String runId, Object levelIdentity, int depth, List<VisualCue> cues, boolean presentationReady) {}
    final class LogEntry {
        public final String text;
        public final int color;
        public final boolean clipped;
        public LogEntry(String text,int color){this(text,color,false);}
        public LogEntry(String text,int color,boolean clipped){this.text=Objects.requireNonNull(text);this.color=color;this.clipped=clipped;}
        @Override public boolean equals(Object value){
            return value instanceof LogEntry&&color==((LogEntry)value).color&&clipped==((LogEntry)value).clipped&&text.equals(((LogEntry)value).text);
        }
        @Override public int hashCode(){return 31*(31*text.hashCode()+color)+(clipped?1:0);}
    }
    default boolean exitRequested() { return true; }
}
