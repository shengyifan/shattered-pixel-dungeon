package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.control.desktop.store.AuditStore;
import com.shatteredpixel.shatteredpixeldungeon.control.desktop.store.AuditException;
import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

public class EnglishAuditBoundaryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void englishEventAndOriginalTextCommitTogetherInSeparateStores() throws Exception {
        Path directory = temporary.newFolder().toPath();
        try(AuditStore store = new AuditStore(directory)) {
            String scope = store.menuScope();
            store.eventWithOriginalText(scope,"game.log",map("text","You descend."),map("text","你向下走去。"));
            assertTrue(store.events(scope,0,10).toString().contains("You descend."));
            assertFalse(store.events(scope,0,10).toString().contains("你向下"));
            try(Connection db = DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("internal.sqlite3"));
                Statement statement = db.createStatement()) {
                try(ResultSet rows=statement.executeQuery("SELECT text FROM logs WHERE channel='displayed_text_original'")) {
                    assertTrue(rows.next());assertTrue(rows.getString(1).contains("你向下走去。"));
                    long linked=((Number)JsonCodec.decode(rows.getString(1)).get("event_sequence")).longValue();
                    assertEquals(((Number)store.events(scope,0,10).get(0).get("sequence")).longValue(),linked);
                }
                statement.execute("CREATE TRIGGER fail_original BEFORE INSERT ON logs WHEN NEW.channel='displayed_text_original' BEGIN SELECT RAISE(ABORT,'test failure'); END");
            }
            int before=store.events(scope,0,10).size();
            assertThrows(AuditException.class,() -> store.eventWithOriginalText(scope,"game.log",
                    map("text","Second event"),map("text","第二条记录")));
            assertEquals(before,store.events(scope,0,10).size());
        }
    }
}
