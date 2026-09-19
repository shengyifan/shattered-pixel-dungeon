package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.control.desktop.store.AuditStore;
import com.shatteredpixel.shatteredpixeldungeon.control.game.UiProjectionHints;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.ProtocolException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.util.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

public class PublicHandlesTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    @Test public void onlyTypedFieldsChangeAndOriginalHistoryAndSourcesStayOpaque()throws Exception{
        try(AuditStore store=new AuditStore(temporary.newFolder().toPath())){
            PublicHandles handles=new PublicHandles(store);
            Map<String,Object> opaque=map("scope_id","run:opaque","state_version","source-epoch:1");
            Map<String,Object> canonical=map("scope_id","run:one","state_version","epoch:1","session_id","session-one",
                    "text","run:one", "id","caller-original", "raw",opaque,"reply",opaque,"text_sources",opaque,
                    "schema",opaque,"text_origins",opaque,"map_context","map-one",
                    "continuous_activity",map("state_version","activity:epoch:1","target_id","caller-original"),
                    "persistence",map("saves_during_request",Arrays.asList(
                            map("receipt_id","save-one","scope_id","run:one","origin_scope_id","menu:one","origin_request_id","caller-original"),
                            map("receipt_id","save-two","scope_id","run:one","origin_scope_id","menu:one","origin_request_id","caller-original"))));
            Map<?,?> out=(Map<?,?>)handles.encode(canonical);
            assertEquals("s1",out.get("scope_id"));assertEquals("r1",out.get("state_version"));
            assertEquals("t1",out.get("session_id"));assertEquals("m1",out.get("map_context"));
            for(String key:Arrays.asList("raw","reply","text_sources","schema","text_origins"))assertSame(opaque,out.get(key));
            assertEquals("run:one",out.get("text"));assertEquals("caller-original",out.get("id"));
            Map<?,?> activity=(Map<?,?>)out.get("continuous_activity");assertEquals("a1",activity.get("state_version"));assertEquals("caller-original",activity.get("target_id"));
            List<?> saves=(List<?>)((Map<?,?>)out.get("persistence")).get("saves_during_request");
            assertEquals("p1",((Map<?,?>)saves.get(0)).get("receipt_id"));assertEquals("p2",((Map<?,?>)saves.get(1)).get("receipt_id"));
            assertEquals("s2",((Map<?,?>)saves.get(0)).get("origin_scope_id"));
            assertEquals("epoch:1",handles.resolveVersion("r1"));assertEquals("activity:epoch:1",handles.resolveVersion("a1"));
            assertEquals("run:one",handles.resolveScope("s1"));
            assertThrows(ProtocolException.class,()->handles.resolveVersion("epoch:1"));
            assertThrows(ProtocolException.class,()->handles.resolveScope("run:one"));
            assertEquals("run:one",canonical.get("scope_id"));
        }
    }
    @Test public void captureHintsSurviveIdentityProjectionWithoutRebindingControlIds()throws Exception{
        try(AuditStore store=new AuditStore(temporary.newFolder().toPath())){
            UiProjectionHints.Node hint=new UiProjectionHints.Node(true,Collections.emptyList(),null,Collections.emptyMap());
            Map<String,Object> ui=new UiProjectionHints(Collections.singletonMap("c1",hint)).attach(map("controls",Arrays.asList(map("id","c1","role","button"))));
            Map<?,?> out=(Map<?,?>)new PublicHandles(store).encode(map("ui",ui));
            assertSame(hint,UiProjectionHints.get(out.get("ui")).nodes.get("c1"));
        }
    }
}
