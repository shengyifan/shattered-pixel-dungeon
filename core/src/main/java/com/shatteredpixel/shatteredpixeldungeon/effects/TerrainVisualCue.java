package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTilemap;
import com.watabou.noosa.Game;
import com.watabou.noosa.Tilemap;
import com.watabou.noosa.VisualCue;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Source-reviewed meanings of frames already selected by a custom terrain layer. */
public final class TerrainVisualCue implements Tilemap.DrawObserver {
    private static final class Meaning {
        final String kind, state;
        Meaning(String kind,String state){this.kind=kind;this.state=state;}
    }
    private final Map<Integer,Meaning> frames=new LinkedHashMap<>();
    private TerrainVisualCue frame(int frame,String kind,String state){frames.put(frame,new Meaning(kind,state));return this;}
    private TerrainVisualCue frames(int[] selected,String kind){for(int frame:selected)frame(frame,kind,null);return this;}

    public static TerrainVisualCue cavesArena(){
        TerrainVisualCue result=new TerrainVisualCue().frame(37,"exposed_wiring",null).frame(38,"pylon_platform","destroyed");
        for(int frame=32;frame<=36;frame++)result.frame(frame,"metal_gate","broken");
        for(int frame=40;frame<=44;frame++)result.frame(frame,"metal_gate","closed");
        return result.frames(new int[]{45,46,47,53,55,61,62,63},"pylon_platform");
    }
    public static TerrainVisualCue vaultEntrance(){
        return new TerrainVisualCue().frames(new int[]{9,10,11,17,18,19,25,26,27},"vault_entrance");
    }
    public static TerrainVisualCue vaultBarrier(){
        return new TerrainVisualCue().frames(new int[]{13,14,15,21,22,23,29,30,31},"vault_barrier");
    }
    public static TerrainVisualCue mineExit(){return new TerrainVisualCue().frame(17,"mine_exit",null);}

    @Override public void observeState(Tilemap source){
        if(!Game.observer.observesVisualCues()||Dungeon.level==null)return;
        source.visitPresentationFrames((frame,left,top,right,bottom)->{
            int cell=VisualCueProjection.gridCell(source.x+left,source.y+top,right-left,bottom-top,
                    DungeonTilemap.SIZE,Dungeon.level.width(),Dungeon.level.length());
            VisualCue cue=selectedFrame(frame,cell);
            if(cue!=null)GameScene.observeTerrainVisual(source,cue);
        });
    }
    VisualCue selectedFrame(int frame,int cell){
        Meaning meaning=frames.get(frame);if(meaning==null||cell<0)return null;
        Map<String,Object> facts=meaning.state==null?null:Collections.singletonMap("state",meaning.state);
        return new VisualCue(meaning.kind,cell,null,null,null,null,facts);
    }
}
