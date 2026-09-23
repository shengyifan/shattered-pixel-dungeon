package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedAppearance;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.watabou.gltextures.SmartTexture;
import com.watabou.glwrap.Attribute;
import com.watabou.glwrap.Uniform;
import com.watabou.noosa.*;
import com.watabou.utils.RectF;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.nio.FloatBuffer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic draw-boundary tests, with no real graphics context or personal profile. */
class ScreenEffectRenderTest {
    @Test void uploadedMatrixIsRecordedOnlyAfterSuccessfulNonemptyDrawAndCannotPredictTheNextShake() throws Exception {
        try(Environment env=new Environment()) {
            GL20 previous=Gdx.gl,previous20=Gdx.gl20;
            boolean[] fail={false};int[] draws={0};
            GL20 gl=(GL20)Proxy.newProxyInstance(GL20.class.getClassLoader(),new Class<?>[]{GL20.class},(proxy,method,args)->{
                if(method.getName().equals("glDrawElements")){if(fail[0])throw new IllegalStateException("fixture draw failure");draws[0]++;}
                return method.getReturnType()==int.class?0:method.getReturnType()==boolean.class?false:null;
            });
            Gdx.gl=Gdx.gl20=gl;
            try {
                NoosaScript script=allocate(NoosaScript.class);script.uCamera=new Uniform(0);script.aXY=new Attribute(1);script.aUV=new Attribute(2);
                env.camera.commit(2,-1);Camera.DrawnTransform first=env.camera.submittedTransform();
                script.camera(env.camera);assertEquals(0,env.camera.observedDrawGeneration());
                script.drawQuadSet(FloatBuffer.allocate(16),0);assertEquals(0,draws[0]);
                env.camera.shake(999,999);assertSame(first.episode,env.camera.submittedTransform().episode,"A request has not changed the displayed matrix");
                env.camera.commit(9,9); // A future matrix is not the one already uploaded to this shader.
                script.drawQuad(FloatBuffer.allocate(16));assertEquals(1,draws[0]);assertEquals(-2,env.camera.observedTransform().offsetX,0.0001);
                assertSame(first.episode,env.camera.observedTransform().episode);
                script.resetCamera();script.camera(env.camera);fail[0]=true;
                assertThrows(IllegalStateException.class,()->script.drawQuad(FloatBuffer.allocate(16)));
                assertEquals(1,env.camera.observedDrawGeneration(),"Failed draw cannot certify another camera sample");
                fail[0]=false;script.drawQuad(FloatBuffer.allocate(16));assertEquals(-9,env.camera.observedTransform().offsetX,0.0001);
            } finally {Gdx.gl=previous;Gdx.gl20=previous20;}
        }
    }

    @Test void pixelCameraUsesTheActualAlignedProjectionAndObservingNeverAdvancesIt() throws Exception {
        try(Environment env=new Environment()) {
            Class<?> type=Class.forName(PixelScene.class.getName()+"$PixelCamera");Constructor<?> constructor=type.getDeclaredConstructor(float.class);constructor.setAccessible(true);
            Camera pixel=(Camera)constructor.newInstance(2f);pixel.scroll.set(.1f,.1f);
            field(Camera.class,"shakeX").setFloat(pixel,.2f);field(Camera.class,"shakeY").setFloat(pixel,-.2f);
            Method update=type.getDeclaredMethod("updateMatrix");update.setAccessible(true);update.invoke(pixel);
            float[] original=pixel.matrix.clone();Camera.DrawnTransform sample=pixel.submittedTransform();
            assertNotNull(sample);assertEquals(-1,sample.offsetX,0.0001);assertEquals(0,sample.offsetY,0.0001);
            assertEquals(2,sample.zoom,0.0001);assertEquals(.5f,sample.scrollX,0.0001);
            pixel.submittedTransform();pixel.observedTransform();assertArrayEquals(original,pixel.matrix);
            assertEquals(.2f,field(Camera.class,"shakeX").getFloat(pixel));
        }
    }

    @Test void overlayMeasurementsIncludeTextureAlphaAndExcludeInvisibleAdditiveBlack() {
        Visual source=new Visual(0,0,100,80);source.alpha(.5f);
        VisualCueProjection.Rect bounds=new VisualCueProjection.Rect(0,0,100,80);
        Map<String,Object> half=ScreenEffectCollector.overlayFields(source,0x80FFFFFF,true,bounds);
        assertEquals((128/255f)*.5f,(Float)half.get("opacity"),0.00001);assertEquals(0xFFFFFF,half.get("color"));
        assertEquals("additive",half.get("blend"));
        assertNull(ScreenEffectCollector.overlayFields(source,0,false,bounds));
        assertNull(ScreenEffectCollector.overlayFields(source,0xFF000000,true,bounds));
        assertNotNull(ScreenEffectCollector.overlayFields(source,0xFF000000,false,bounds));
        source.ra=Float.NaN;assertNull(ScreenEffectCollector.overlayFields(source,0xFFFFFFFF,false,bounds));
    }

    @Test void overlayVisibilityFollowsActualSceneDrawOrderAndNotCreationOrder() throws Exception {
        try(Environment env=new Environment()) {
            Window modal=allocate(Window.class);modal.exists=modal.alive=modal.visible=true;modal.camera=env.ui;
            Image flash=env.overlay();env.scene.add(modal);env.scene.add(flash);
            env.collector.beginDraw();env.collector.overlayDrawn(flash,new Object(),0x80FFFFFF,true);env.collector.finishDraw();
            assertEquals(1,env.last().size(),"A flash drawn above the modal remains visible");
            env.scene.bringToFront(modal);
            env.collector.beginDraw();env.collector.overlayDrawn(flash,new Object(),0x80FFFFFF,true);env.collector.finishDraw();
            assertTrue(env.last().isEmpty(),"The same modal drawn later obscures the overlay");
            modal.visible=false;flash.x=1;
            env.collector.beginDraw();env.collector.overlayDrawn(flash,new Object(),0x80FFFFFF,true);env.collector.finishDraw();
            assertTrue(env.last().isEmpty(),"A partly off-surface flash is not reported as a full overlay");
        }
    }

    @Test void shakeNeedsActualDrawAndVisibleWorldReferenceAndItsAbsenceIsAnObservedEmptyFrame() throws Exception {
        try(Environment env=new Environment()) {
            env.camera.commit(2,1);
            env.collector.beginDraw();env.collector.finishDraw();assertTrue(env.last().isEmpty(),"A committed/requested matrix alone is not a draw");
            env.collector.beginDraw();env.camera.recordRenderedTransform(env.camera.submittedTransform());env.collector.finishDraw();
            assertEquals("camera_displacement",env.last().get(0).kind);
            Map<String,Object> publicData=env.last().get(0).publicData();List<?> offset=(List<?>)publicData.get("offset_pixels");
            assertEquals(-2,((Number)offset.get(0)).floatValue(),.0001);assertEquals(-1,((Number)offset.get(1)).floatValue(),.0001);
            assertFalse(publicData.containsKey("duration"));assertFalse(publicData.containsKey("magnitude"));assertFalse(publicData.containsKey("cause"));
            Arrays.fill(Dungeon.level.heroFOV,false);
            env.collector.beginDraw();env.camera.recordRenderedTransform(env.camera.submittedTransform());env.collector.finishDraw();assertTrue(env.last().isEmpty());
            Arrays.fill(Dungeon.level.heroFOV,true);env.camera.commit(0,0);
            env.collector.beginDraw();env.camera.recordRenderedTransform(env.camera.submittedTransform());env.collector.finishDraw();assertTrue(env.last().isEmpty());
        }
    }

    @Test void transparentOverlayDoesNotHideCameraButLaterVisibleCoverDoes() throws Exception {
        try(Environment env=new Environment()) {
            Image flash=env.overlay();env.scene.add(flash);env.camera.commit(2,0);
            env.collector.beginDraw();env.camera.recordRenderedTransform(env.camera.submittedTransform());
            env.collector.overlayDrawn(flash,new Object(),0,false);env.collector.finishDraw();
            assertEquals("camera_displacement",env.last().get(0).kind);
            env.collector.beginDraw();env.camera.recordRenderedTransform(env.camera.submittedTransform());
            env.collector.overlayDrawn(flash,new Object(),0xFFFFFFFF,false);env.collector.finishDraw();
            assertEquals(1,env.last().size());assertEquals("screen_overlay",env.last().get(0).kind);
        }
    }

    @Test void mapDepthCameraAndDrawTreeChangesDiscardMixedFrames() throws Exception {
        try(Environment env=new Environment()) {
            env.collector.beginDraw();Dungeon.depth++;env.collector.finishDraw();assertTrue(env.frames.isEmpty());Dungeon.depth--;
            env.collector.beginDraw();Camera.main=new TestCamera();env.collector.finishDraw();assertTrue(env.frames.isEmpty());Camera.main=env.camera;
            env.collector.beginDraw();env.scene.add(new Group());env.collector.finishDraw();assertTrue(env.frames.isEmpty());
            env.collector.beginDraw();env.collector.finishDraw();assertEquals(1,env.frames.size());
        }
    }

    @Test void exactCameraTransformChangesClippingAndHudIntersectionWithoutChangingWorldParticleCell() throws Exception {
        try(Environment env=new Environment()) {
            env.camera.commit(0,0);env.camera.recordRenderedTransform(env.camera.submittedTransform());
            Image source=env.icon();source.camera=env.camera;source.x=0;source.y=20;
            assertFalse(RenderedAppearance.image(source).isEmpty());
            Method bounds=VisualCueCollector.class.getDeclaredMethod("screenBounds",Gizmo.class,Camera.class);bounds.setAccessible(true);
            VisualCueProjection.Rect before=(VisualCueProjection.Rect)bounds.invoke(null,source,env.camera);
            env.camera.commit(3,0);env.camera.recordRenderedTransform(env.camera.submittedTransform());
            assertTrue(RenderedAppearance.image(source).isEmpty(),"Shake clips the left edge that nominal scroll would accept");
            source.x=32;VisualCueProjection.Rect shifted=(VisualCueProjection.Rect)bounds.invoke(null,source,env.camera);
            assertEquals(29,shifted.left,.0001);
            Camera.DrawnTransform transform=env.camera.observedTransform();
            assertEquals(36,transform.screenToWorldX((shifted.left+shifted.right)/2f),.0001);
            VisualCueProjection.Viewport viewport=new VisualCueProjection.Viewport(transform.scrollX,transform.scrollY,transform.width,transform.height,transform.x,transform.y,transform.zoom);
            assertFalse(VisualCueProjection.permitsScreenBounds(shifted,viewport,Collections.singletonList(new VisualCueProjection.Rect(28,20,30,28))),"Actual shaken pixels intersect HUD");
            assertEquals(0,before.left,.0001);
        }
    }

    private static final class TestCamera extends Camera {
        TestCamera(){super(0,0,100,80,1);fullScreen=true;}
        void commit(float dx,float dy){shakeX=dx;shakeY=dy;updateMatrix();}
    }
    private static final class Grid extends Level {
        Grid(){setSize(10,10);Arrays.fill(heroFOV,true);}
        @Override protected boolean build(){return false;}
        @Override protected void createMobs(){}
        @Override protected void createItems(){}
    }
    private static final class Environment implements AutoCloseable {
        final Game oldGame=Game.instance;final RuntimeObserver oldObserver=Game.observer;final Camera oldCamera=Camera.main;
        final Level oldLevel=Dungeon.level;final String oldRun=Dungeon.runId;final int oldDepth=Dungeon.depth,oldWidth=Game.width,oldHeight=Game.height;
        final float oldInvW,oldInvH;
        final GameScene scene=new GameScene();final TestCamera camera=new TestCamera(),ui=new TestCamera();
        final ScreenEffectCollector collector=new ScreenEffectCollector(scene);final List<List<ScreenEffect>> frames=new ArrayList<>();
        Environment() throws Exception {
            oldInvW=field(Camera.class,"invW2").getFloat(null);oldInvH=field(Camera.class,"invH2").getFloat(null);
            Game.width=100;Game.height=80;field(Camera.class,"invW2").setFloat(null,.02f);field(Camera.class,"invH2").setFloat(null,.025f);
            Game.instance=allocate(Game.class);field(Game.class,"scene").set(Game.instance,scene);Camera.main=camera;
            camera.commit(0,0);ui.commit(0,0);Dungeon.level=new Grid();Dungeon.runId="screen-fixture";Dungeon.depth=1;
            Game.observer=new RuntimeObserver(){@Override public boolean observesVisualCues(){return true;}
                @Override public void onScreenEffects(String run,Object level,int depth,List<ScreenEffect> effects){frames.add(effects);}};
        }
        List<ScreenEffect> last(){assertFalse(frames.isEmpty());return frames.get(frames.size()-1);}
        Image icon()throws Exception{Image image=new Image();SmartTexture texture=allocate(SmartTexture.class);texture.width=texture.height=8;image.texture=texture;image.frame(new RectF(0,0,1,1));image.camera=ui;return image;}
        Image overlay()throws Exception{Image image=icon();image.width=100;image.height=80;return image;}
        public void close()throws Exception{Game.instance=oldGame;Game.observer=oldObserver;Camera.main=oldCamera;Game.width=oldWidth;Game.height=oldHeight;Dungeon.level=oldLevel;Dungeon.runId=oldRun;Dungeon.depth=oldDepth;field(Camera.class,"invW2").setFloat(null,oldInvW);field(Camera.class,"invH2").setFloat(null,oldInvH);}
    }
    private static Field field(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static <T>T allocate(Class<T> type)throws Exception{Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field field=field(unsafe,"theUnsafe");return type.cast(unsafe.getMethod("allocateInstance",Class.class).invoke(field.get(null),type));}
}
