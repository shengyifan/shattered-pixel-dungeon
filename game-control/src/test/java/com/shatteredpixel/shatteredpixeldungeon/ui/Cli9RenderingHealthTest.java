package com.shatteredpixel.shatteredpixeldungeon.ui;

import com.watabou.gltextures.SmartTexture;
import com.watabou.noosa.*;
import com.watabou.utils.PointF;
import com.watabou.utils.RectF;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Cli9RenderingHealthTest {
    @Test void nativeLayoutIncludesDisplayedDotAndClampsItToHealthAndShield() throws Exception {
        HealthBar bar=allocate(HealthBar.class);
        for(String name:List.of("Bg","Hp","Shld","Dot")){
            ColorBlock part=allocate(ColorBlock.class);part.scale=new PointF(1,1);set(bar,HealthBar.class,name,part);
        }
        bar.camera=new Camera(0,0,100,100,1);bar.setRect(0,0,16,2);
        bar.level(.25f,.5f,.125f);assertArrayEquals(new int[]{16,4,8,2},bar.renderedPixelWidths());
        assertEquals(2,sample(bar.gameplayStatus()).get("incoming_dot"));
        bar.level(.25f,.5f,2f);assertArrayEquals(new int[]{16,4,8,8},bar.renderedPixelWidths());
        bar.level(1f,1f,0f);assertEquals(0,sample(bar.gameplayStatus()).get("incoming_dot"));
        assertEquals(Set.of("total","filled","with_shield","incoming_dot","basis"),sample(bar.gameplayStatus()).keySet());
    }

    @Test void bothHudSizesExportLaidOutSegmentsWithoutReadingCharacters() throws Exception {
        for(float width:new float[]{50,128}){
            StatusPane pane=visual(allocate(StatusPane.class));
            installBars(pane,StatusPane.class,width);
            assertEquals(Math.round(width/8),sample(pane.gameplayStatus()).get("incoming_dot"));
        }
        for(float width:new float[]{47,96}){
            BossHealthBar pane=visual(allocate(BossHealthBar.class));
            Image skull=image(8,8);skull.parent=pane;set(pane,BossHealthBar.class,"skull",skull);
            installBars(pane,BossHealthBar.class,width);
            assertEquals(Math.round(width/8),sample(pane.gameplayStatus()).get("incoming_dot"));
            assertEquals(false,((Map<?,?>)pane.gameplayStatus().get("shown")).get("boss_warning"));
        }
    }
    private static void installBars(Gizmo pane,Class<?> owner,float width)throws Exception{
        Image hp=image(width,4),shield=image(width,4),dot=image(width,4);
        hp.scale.x=.25f;shield.scale.x=.5f;dot.scale.x=.125f;
        hp.parent=shield.parent=dot.parent=(Group)pane;
        set(pane,owner,"hp",hp);set(pane,owner,"shieldHP",shield);set(pane,owner,"Dot",dot);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> sample(Map<String,Object> status){
        return (Map<String,Object>)((List<?>)((Map<?,?>)status.get("health_estimate")).get("samples")).get(0);
    }
    private static Image image(float width,float height)throws Exception{
        Image image=new Image();SmartTexture texture=allocate(SmartTexture.class);texture.width=texture.height=128;
        image.texture=texture;image.frame(new RectF(0,0,width/128,height/128));return image;
    }
    private static <T extends Gizmo>T visual(T node){
        node.exists=node.alive=node.visible=true;node.camera=new Camera(0,0,200,200,1);return node;
    }
    private static void set(Object o,Class<?> c,String n,Object v)throws Exception{Field f=c.getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
    private static <T>T allocate(Class<T> c)throws Exception{Class<?> u=Class.forName("sun.misc.Unsafe");Field f=u.getDeclaredField("theUnsafe");f.setAccessible(true);return c.cast(u.getMethod("allocateInstance",Class.class).invoke(f.get(null),c));}
}
