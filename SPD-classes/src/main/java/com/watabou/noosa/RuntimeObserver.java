package com.watabou.noosa;

import java.util.List;
import java.util.Objects;
import java.util.Map;

/** Optional in-process lifecycle observer. No transport or game rules live here. */
public interface RuntimeObserver {
    RuntimeObserver NONE = new RuntimeObserver() {};
    default void afterFrame() {}
    /** Completed scene draw, before step/input/update can change the pixels' source objects. */
    default void afterDraw() {}
    /** Render-thread hook before scene animations update; never performs disk IO. */
    default void beforeSceneUpdate() {}
    /** Render-thread scheduling gate; called under the waiting actor thread's monitor. Never perform IO here. */
    default boolean beforeActorResume() { return true; }
    default void onException(Throwable error) {}
    default void onDispose() {}
    default void onSave(String runId, int slot, Throwable error) {}
    default void onRunEnded(String runId, boolean won) {}
    /** Optional immutable text provenance. NONE preserves the original String identity. */
    default String onTextResource(String rendered, String resolvedKey, String language, Object[] arguments) { return rendered; }
    /** The successful source-language template permits masking parameters the GUI did not display. */
    default String onTextResource(String rendered, String resolvedKey, String language, Object[] arguments, String guiTemplate) {
        return onTextResource(rendered, resolvedKey, language, arguments);
    }
    default String onTextOperation(String operation, String rendered, Object... operands) { return rendered; }
    default void onTextBound(Object owner, String rendered) {}
    default void onTextReleased(Object owner) {}
    /** Snapshot of text that an existing game log control has just drawn, never a raw log signal. */
    default void onGameLog(String contextId, List<LogEntry> entries) {}
    default boolean observesVisualCues() { return false; }
    /** One actually displayed floating-text occurrence; appearance contains only gated draw evidence. */
    default void onFloatingText(String runId, Object levelIdentity, int depth,
                                String text, boolean clipped, Map<String,Object> appearance) {}
    /** A known graphical combat announcement, emitted once after its unobscured native draw. */
    default void onBanner(String runId,String kind,Map<String,Object> appearance) {}
    /** Complete current-frame screen effects. Opaque episode identities must never be serialized. */
    default void onScreenEffects(String runId,Object levelIdentity,int depth,List<ScreenEffect> effects) {}
    /** One completed GameScene draw. The identity token is opaque and must never be serialized. */
    default void onVisualCues(String runId, Object levelIdentity, int depth, List<VisualCue> cues) {
        onVisualCues(runId,levelIdentity,depth,cues,true);
    }
    default void onVisualCues(String runId, Object levelIdentity, int depth, List<VisualCue> cues, boolean presentationReady) {}
    /** Quantitative display noise from the same completed draw; separate from discrete visual cues. */
    default void onVisualMetrics(String runId,Object levelIdentity,int depth,List<VisualMetric> metrics,java.util.Set<Object> visibleEpisodes) {}
    /** Existing emitter children have finished drawing; its opaque episode is only a sampling key. */
    default void onEmitterDraw(com.watabou.noosa.particles.Emitter source,Object episode) {}
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
