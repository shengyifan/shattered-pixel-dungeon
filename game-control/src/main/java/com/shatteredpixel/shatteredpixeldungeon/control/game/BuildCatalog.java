package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/** Immutable software/resource provenance, separate from the game RNG and observation state. */
public final class BuildCatalog {
    private static final Map<String,Object> VALUE=load();
    private BuildCatalog(){}
    public static Map<String,Object> current(){return VALUE;}
    private static Map<String,Object> load(){
        try(InputStream in=BuildCatalog.class.getResourceAsStream("/control-build.json")){
            if(in==null)return Collections.singletonMap("status","unavailable_in_this_runtime");
            return Collections.unmodifiableMap(JsonCodec.decode(new String(in.readAllBytes(),StandardCharsets.UTF_8)));
        }catch(IOException error){throw new IllegalStateException("BUILD_CATALOG_UNAVAILABLE",error);}
    }
}
