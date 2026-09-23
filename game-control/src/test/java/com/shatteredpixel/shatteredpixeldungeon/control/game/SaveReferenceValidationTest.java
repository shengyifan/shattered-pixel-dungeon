package com.shatteredpixel.shatteredpixeldungeon.control.game;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

public class SaveReferenceValidationTest {
    @Test public void directAndFrozenSaveReferencesRejectInvalidTypesWithoutChangingEvidence() {
        for(Object invalid:Arrays.asList(false,true,0.0,-0.5,-1,2,"0",Collections.emptyList(),Collections.emptyMap())) {
            Map<String,Object> snapshot=map("persistence",map("saves",Arrays.asList(map("sid","p1"),map("sid","p2")),"saved",invalid));
            Map<String,Object> frame=map("v",8,"id","query","s","s1","data",snapshot);
            String original=com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec.encode(frame);
            assertThrows(IllegalArgumentException.class,()->CompactStructures.expand(frame));
            assertEquals(original,com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec.encode(frame));
            assertThrows(IllegalArgumentException.class,()->CompactStructures.expand(map("before",snapshot,"after",snapshot)));
        }
    }

    @Test public void nullableMissingAndDistinctReceiptsKeepTheirOwnScopesAndExtensions() {
        Map<String,Object> first=map("sid","p1","future",false),second=map("sid","p2","s","s0","src_s",null);
        for(Object saved:Arrays.asList(0,1,null,map("sid","p3","future",Arrays.asList(null,false,0)))) {
            Map<String,Object> snapshot=map("persistence",map("saves",Arrays.asList(first,second),"saved",saved));
            Map<?,?> frame=(Map<?,?>)CompactStructures.expand(map("v",8,"id","query","s","s1","data",snapshot));
            Map<?,?> persistence=(Map<?,?>)((Map<?,?>)frame.get("data")).get("persistence");
            List<?> receipts=(List<?>)persistence.get("saves");
            assertEquals("s1",((Map<?,?>)receipts.get(0)).get("s"));assertEquals("s0",((Map<?,?>)receipts.get(1)).get("s"));
            assertNull(((Map<?,?>)receipts.get(1)).get("src_s"));assertEquals(false,((Map<?,?>)receipts.get(0)).get("future"));
            if(saved instanceof Integer)assertEquals(receipts.get((Integer)saved),persistence.get("saved"));
            else if(saved==null)assertNull(persistence.get("saved"));
            else assertEquals(Arrays.asList(null,false,0),((Map<?,?>)persistence.get("saved")).get("future"));
        }
        Map<?,?> decoded=(Map<?,?>)CompactStructures.expand(map("persistence",map("saves",Collections.emptyList())));
        assertFalse(((Map<?,?>)decoded.get("persistence")).containsKey("saved"));
        assertThrows(IllegalArgumentException.class,()->CompactStructures.expand(map("persistence",map("saves",Arrays.asList(false),"saved",0))));
    }
}
