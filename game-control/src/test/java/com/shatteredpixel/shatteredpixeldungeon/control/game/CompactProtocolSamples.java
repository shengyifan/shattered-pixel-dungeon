package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;

/** Offline benchmark fixtures from explicitly supplied, already public protocol evidence. */
public final class CompactProtocolSamples {
    private CompactProtocolSamples() { }
    public static void main(String[] arguments) throws Exception {
        if(arguments.length!=2)throw new IllegalArgumentException("Usage: CompactProtocolSamples <public-evidence.json> <output.json>");
        Map<String,Object> evidence=JsonCodec.decode(Files.readString(Path.of(arguments[0]),StandardCharsets.UTF_8));
        List<Object> samples=new ArrayList<>();int index=0;
        for(Object exchange:(List<?>)evidence.get("exchanges")) {
            Map<?,?> response=(Map<?,?>)((Map<?,?>)exchange).get("response");
            if(!(response.get("result") instanceof Map) || !((Map<?,?>)response.get("result")).containsKey("observation"))continue;
            String name=index==0?"dungeon-state":"dungeon-state-"+index;
            Map<String,Object> compact=CompactProtocol.success((String)response.get("id"),(String)response.get("scope_id"),
                    (String)response.get("status"),response.get("result"),true,false);
            samples.add(map("name",name,"before",response,"after",compact));
            if(index++==0) {
                samples.add(map("name",name+"-sources","before",response,"after",CompactProtocol.success(
                        (String)response.get("id"),(String)response.get("scope_id"),(String)response.get("status"),response.get("result"),true,true)));
                Map<?,?> canonical=(Map<?,?>)response.get("result");
                Object ui=((Map<?,?>)canonical.get("observation")).get("ui");
                Map<String,Object> actions=map("scope_id",canonical.get("scope_id"),"state_version",canonical.get("state_version"),
                        "phase",canonical.get("phase"),"observation",map("ui",ui),"actions",canonical.get("actions"));
                samples.add(map("name","actions","before",map("protocol_version",2,"id","actions","scope_id",response.get("scope_id"),
                        "ok",true,"status","completed","result",actions),"after",CompactProtocol.success("actions",(String)response.get("scope_id"),"completed",actions,true,false)));
            }
        }
        Map<String,Object> output=map("source",arguments[0],"policy","Only previously recorded public protocol responses; no profiles, saves or audit databases.","samples",samples);
        Path destination=Path.of(arguments[1]);Files.createDirectories(destination.toAbsolutePath().getParent());
        Files.writeString(destination,JsonCodec.encode(output)+"\n",StandardCharsets.UTF_8);
        System.out.println("Wrote "+samples.size()+" public protocol samples to "+destination);
    }
}
