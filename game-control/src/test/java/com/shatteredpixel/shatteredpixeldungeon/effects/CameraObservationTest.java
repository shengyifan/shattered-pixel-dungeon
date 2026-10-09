package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.watabou.glwrap.Attribute;
import com.watabou.glwrap.Uniform;
import com.watabou.noosa.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.nio.FloatBuffer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Current HUD geometry observes completed draws without changing native camera behavior. */
class CameraObservationTest {
    @Test void uploadedGeometryIsRecordedOnlyAfterSuccessfulNonemptyDraw() throws Exception {
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
                env.camera.commit(2,-1);script.camera(env.camera);
                assertNull(field(Camera.class,"observedTransform").get(env.camera));
                script.drawQuadSet(FloatBuffer.allocate(16),0);assertEquals(0,draws[0]);
                assertNull(field(Camera.class,"observedTransform").get(env.camera));
                float[] uploaded=env.camera.matrix.clone();env.camera.shake(999,999);
                assertArrayEquals(uploaded,env.camera.matrix,"A shake request does not change the already uploaded matrix");
                env.camera.commit(9,9);
                script.drawQuad(FloatBuffer.allocate(16));assertEquals(1,draws[0]);
                Camera.DrawnTransform first=env.camera.observedTransform();
                assertEquals(2,first.scrollX,.0001);assertEquals(-1,first.scrollY,.0001);
                script.resetCamera();script.camera(env.camera);fail[0]=true;
                assertThrows(IllegalStateException.class,()->script.drawQuad(FloatBuffer.allocate(16)));
                assertSame(first,env.camera.observedTransform(),"A failed draw cannot certify another geometry sample");
                fail[0]=false;script.drawQuad(FloatBuffer.allocate(16));
                assertEquals(9,env.camera.observedTransform().scrollX,.0001);
                assertEquals(9,env.camera.observedTransform().scrollY,.0001);
            } finally {Gdx.gl=previous;Gdx.gl20=previous20;}
        }
    }

    @Test void pixelCameraUsesAlignedGeometryWithoutAdvancingNativeShake() throws Exception {
        try(Environment ignored=new Environment()) {
            Class<?> type=Class.forName(PixelScene.class.getName()+"$PixelCamera");Constructor<?> constructor=type.getDeclaredConstructor(float.class);constructor.setAccessible(true);
            Camera pixel=(Camera)constructor.newInstance(2f);pixel.scroll.set(.1f,.1f);
            field(Camera.class,"shakeX").setFloat(pixel,.2f);field(Camera.class,"shakeY").setFloat(pixel,-.2f);
            Method update=type.getDeclaredMethod("updateMatrix");update.setAccessible(true);update.invoke(pixel);
            float[] original=pixel.matrix.clone();Camera.DrawnTransform sample=pixel.submittedTransform();
            assertNotNull(sample);assertEquals(2,sample.zoom,.0001);
            assertEquals(.5f,sample.scrollX,.0001);assertEquals(0,sample.scrollY,.0001);
            pixel.submittedTransform();pixel.observedTransform();assertArrayEquals(original,pixel.matrix);
            assertEquals(.2f,field(Camera.class,"shakeX").getFloat(pixel));
        }
    }

    @Test void projectionValidityPreservesUninitializedAndNonfiniteGuards() throws Exception {
        try(Environment ignored=new Environment()) {
            TestCamera camera=new TestCamera();assertNull(camera.submittedTransform());
            camera.commit(0,0);assertNotNull(camera.submittedTransform());
            camera.scroll.set(Float.NaN,0);camera.commit(0,0);assertNull(camera.submittedTransform());
            camera.scroll.set(0,0);camera.commit(0,0);assertNotNull(camera.submittedTransform());
        }
    }

    @Test void gameplayWorldFootprintRemainsIndependentOfCameraAndHud() throws Exception {
        try(Environment env=new Environment()){
            Visual source=new Visual(32,32,8,8);
            List<Integer> before=VisualCueProjection.worldFootprint(source,10,100,16);
            assertEquals(Collections.singletonList(22),before);
            env.camera.commit(30,20);env.camera.recordRenderedTransform(env.camera.submittedTransform());
            env.camera.scroll.set(500,500);env.camera.zoom=4;env.camera.visible=false;
            assertEquals(before,VisualCueProjection.worldFootprint(source,10,100,16));
            assertNull(source.camera,"Semantic geometry must not populate camera caches");
        }
    }

    private static final class TestCamera extends Camera {
        TestCamera(){super(0,0,100,80,1);fullScreen=true;}
        void commit(float dx,float dy){shakeX=dx;shakeY=dy;updateMatrix();}
    }
    private static final class Environment implements AutoCloseable {
        final RuntimeObserver oldObserver=Game.observer;
        final int oldWidth=Game.width,oldHeight=Game.height;
        final float oldInvW,oldInvH;
        final TestCamera camera=new TestCamera();
        Environment() throws Exception {
            oldInvW=field(Camera.class,"invW2").getFloat(null);oldInvH=field(Camera.class,"invH2").getFloat(null);
            Game.width=100;Game.height=80;field(Camera.class,"invW2").setFloat(null,.02f);field(Camera.class,"invH2").setFloat(null,.025f);
            camera.commit(0,0);Game.observer=new RuntimeObserver(){@Override public boolean observesVisualCues(){return true;}};
        }
        public void close()throws Exception{Game.observer=oldObserver;Game.width=oldWidth;Game.height=oldHeight;field(Camera.class,"invW2").setFloat(null,oldInvW);field(Camera.class,"invH2").setFloat(null,oldInvH);}
    }
    private static Field field(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static <T>T allocate(Class<T> type)throws Exception{Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field field=field(unsafe,"theUnsafe");return type.cast(unsafe.getMethod("allocateInstance",Class.class).invoke(field.get(null),type));}
}
