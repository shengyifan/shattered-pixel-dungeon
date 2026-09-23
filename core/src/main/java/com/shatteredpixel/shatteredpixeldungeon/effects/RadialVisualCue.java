package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTilemap;
import com.watabou.noosa.Image;
import com.watabou.noosa.VisualCue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Known cell extent of an existing radial presentation; never the planned attack coverage. */
public final class RadialVisualCue {
    private RadialVisualCue() {}

    public static VisualCue capture(Image source,String kind,String shape,float textureRadius) {
        if(Dungeon.level==null||source==null||!Float.isFinite(textureRadius)||textureRadius<=0
                ||!Float.isFinite(source.am+source.aa)||source.angle!=0)return null;
        VisualCueProjection.Rect bounds=VisualCueProjection.worldBounds(source);if(bounds==null)return null;
        float cx=(bounds.left+bounds.right)/2f,cy=(bounds.top+bounds.bottom)/2f;
        float rx=textureRadius*Math.abs(source.scale.x),ry=textureRadius*Math.abs(source.scale.y);
        if(!Float.isFinite(cx+cy+rx+ry)||rx<=0||ry<=0)return null;
        int w=Dungeon.level.width(),h=Dungeon.level.height();float tile=DungeonTilemap.SIZE;
        List<Integer> visible=new ArrayList<>();boolean partial=false;
        int firstX=(int)Math.floor((cx-rx)/tile),lastX=(int)Math.ceil((cx+rx)/tile)-1;
        int firstY=(int)Math.floor((cy-ry)/tile),lastY=(int)Math.ceil((cy+ry)/tile)-1;
        // Bounds are presentation geometry. Never loop beyond the finite current floor.
        partial=firstX<0||firstY<0||lastX>=w||lastY>=h;
        for(int y=Math.max(0,firstY);y<=Math.min(h-1,lastY);y++)for(int x=Math.max(0,firstX);x<=Math.min(w-1,lastX);x++){
            float nearX=Math.max(x*tile,Math.min(cx,(x+1)*tile));
            float nearY=Math.max(y*tile,Math.min(cy,(y+1)*tile));
            float dx=(nearX-cx)/rx,dy=(nearY-cy)/ry;
            if(dx*dx+dy*dy>=1)continue;
            int cell=y*w+x;
            if(VisualCueProjection.knownCell(cell,Dungeon.level.length(),Dungeon.level.heroFOV))visible.add(cell);
            else partial=true;
        }
        if(visible.isEmpty())return null;
        Map<String,Object> fact=new LinkedHashMap<>();fact.put("shape",shape);fact.put("cells",visible);
        fact.put("coverage","visual_extent");if(partial)fact.put("partial",true);
        return new VisualCue(kind,visible.get(0),null,null,null,null,fact);
    }
}
