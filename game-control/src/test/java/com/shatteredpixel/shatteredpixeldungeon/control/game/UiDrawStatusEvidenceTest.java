package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIcon;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.shatteredpixel.shatteredpixeldungeon.ui.IconButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedStatus;
import com.watabou.gltextures.SmartTexture;
import com.watabou.noosa.*;
import com.watabou.noosa.particles.Emitter;
import com.watabou.utils.PointF;
import com.watabou.utils.RectF;
import org.junit.jupiter.api.*;

import java.util.*;

import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.jupiter.api.Assertions.*;

/** Simulates completed draw -> native update -> observation ordering. No GL draw or game action runs. */
class UiDrawStatusEvidenceTest {
    private TextObservationFixture textHooks;
    private Scene scene;
    private UiBridge bridge;
    @BeforeAll static void resources(){GameSnapshotterTest.resourceOnlyRuntime();}
    @BeforeEach void setup(){textHooks=new TextObservationFixture();scene=new Scene();bridge=new UiBridge(()->scene);}
    @AfterEach void restore(){textHooks.close();}

    @Test void allConstructorsRequireACompletedCaptureBeforePublishingRenderedFields()throws Exception{
        StatusButton button=new StatusButton(false);scene.add(button);
        assertFalse(bridge.drawnIntentReady());
        assertNoField(bridge.describeUi(),"sample_icon");
        assertEquals("Visible choice",node(bridge.describeUi(),"button").get("label"));
        assertTrue(bridge.describeActions().stream().anyMatch(action->"ui.activate".equals(action.get("action"))));
        bridge.captureDrawnEvidence();
        assertTrue(bridge.drawnIntentReady());assertTrue(node(bridge.describeUi(),"button").containsKey("sample_icon"));
        Game previous=Game.instance;
        try {
            Game instance=FloatingAppearanceTest.allocate(Game.class);FloatingAppearanceTest.field(instance,Game.class,"scene",scene);Game.instance=instance;
            UiBridge defaultBridge=new UiBridge();assertNoField(defaultBridge.describeUi(),"sample_icon");
            defaultBridge.captureDrawnEvidence();assertTrue(node(defaultBridge.describeUi(),"button").containsKey("sample_icon"));
        } finally {Game.instance=previous;}
    }

    @Test void nativeImageTintAndFrameStayAtTheDrawnSampleWhileLiveMeaningRequiresAnotherDraw(){
        StatusButton button=new StatusButton(false);scene.add(button);bridge.captureDrawnEvidence();
        Map<String,Object> frozen=node(bridge.frozenUi(),"button");Object before=frozen.get("sample_icon");
        String intent=bridge.intentSignature();
        button.image.hardlight(0xFF0000);button.image.frame(new RectF(.25f,0,.5f,.25f));
        assertEquals(before,node(bridge.frozenUi(),"button").get("sample_icon"));
        assertFalse(bridge.drawnIntentReady());assertNotEquals(intent,bridge.intentSignature());
        assertEquals(0,button.clicks,"Neither capture nor readiness may invoke callbacks");
        bridge.captureDrawnEvidence();assertTrue(bridge.drawnIntentReady());
        assertNotEquals(before,node(bridge.frozenUi(),"button").get("sample_icon"));
        assertEquals(before,frozen.get("sample_icon"),"Earlier frozen observations remain independent");
    }

    @Test void passiveFrameAndOpacityUpdatesDoNotBlockIntentButStillNeedADrawToChangePixels(){
        StatusButton button=new StatusButton(true);scene.add(button);bridge.captureDrawnEvidence();
        Object before=node(bridge.frozenUi(),"button").get("sample_icon");String intent=bridge.intentSignature();
        button.image.alpha(.35f);button.image.frame(new RectF(.25f,0,.5f,.25f));
        assertTrue(bridge.drawnIntentReady());assertEquals(intent,bridge.intentSignature());
        assertEquals(before,node(bridge.frozenUi(),"button").get("sample_icon"));
        bridge.captureDrawnEvidence();assertNotEquals(before,node(bridge.frozenUi(),"button").get("sample_icon"));
        button.selected=true;assertFalse(bridge.drawnIntentReady());
        assertNotEquals(intent,bridge.intentSignature(),"The explicit passive override still retains selection meaning");
        bridge.captureDrawnEvidence();assertTrue(bridge.drawnIntentReady());
    }

    @Test void equalValuedChildReplacementAndDetachInvalidateTheOwnersOldRenderedRecord(){
        StatusButton button=new StatusButton(false);scene.add(button);bridge.captureDrawnEvidence();
        Object before=node(bridge.frozenUi(),"button").get("sample_icon");
        Image old=button.image;button.remove(old);button.image=image();button.add(button.image);
        assertEquals(before,button.renderedStatus().get("sample_icon"),"The replacement intentionally has equal pixels");
        assertNoField(bridge.describeUi(),"sample_icon");assertFalse(bridge.drawnIntentReady());
        bridge.captureDrawnEvidence();assertEquals(before,node(bridge.frozenUi(),"button").get("sample_icon"));
        button.remove(button.image);assertNoField(bridge.describeUi(),"sample_icon");
        assertTrue(bridge.drawnIntentReady(),"An empty optional status cannot hold the render boundary");
        button.add(button.image);assertNoField(bridge.describeUi(),"sample_icon");assertFalse(bridge.drawnIntentReady());
        bridge.captureDrawnEvidence();assertTrue(node(bridge.describeUi(),"button").containsKey("sample_icon"));
    }

    @Test void recycledVisualLifetimeCannotBorrowTheSameInstancesOldPixels(){
        StatusButton button=new StatusButton(false);scene.add(button);bridge.captureDrawnEvidence();
        long lifetime=button.image.renderedLifetime();button.image.revive();assertTrue(button.image.renderedLifetime()>lifetime);
        assertNoField(bridge.describeUi(),"sample_icon");assertFalse(bridge.drawnIntentReady());
        bridge.captureDrawnEvidence();assertTrue(bridge.drawnIntentReady());assertTrue(node(bridge.describeUi(),"button").containsKey("sample_icon"));
    }

    @Test void aHiddenOrDetachedOwnerNeedsAnotherCaptureWhenShownAgain(){
        StatusButton button=new StatusButton(false);scene.add(button);bridge.captureDrawnEvidence();
        button.visible=false;assertNoField(bridge.describeUi(),"sample_icon");assertTrue(bridge.drawnIntentReady(),"A newly hidden node cannot hold the render boundary");
        button.visible=true;assertNoField(bridge.describeUi(),"sample_icon");assertFalse(bridge.drawnIntentReady());
        bridge.captureDrawnEvidence();assertTrue(bridge.drawnIntentReady());
        button.image.visible=false;assertNoField(bridge.describeUi(),"sample_icon");
        button.image.visible=true;assertNoField(bridge.describeUi(),"sample_icon");
        bridge.captureDrawnEvidence();assertTrue(node(bridge.describeUi(),"button").containsKey("sample_icon"));
        scene.remove(button);assertNoField(bridge.describeUi(),"sample_icon");scene.add(button);
        assertNoField(bridge.describeUi(),"sample_icon");bridge.captureDrawnEvidence();assertTrue(bridge.drawnIntentReady());
    }

    @Test void replacingTheSceneCannotReuseRenderedEvidenceEvenForTheSameAttachedObject(){
        StatusButton button=new StatusButton(false);scene.add(button);bridge.captureDrawnEvidence();
        Scene previous=scene;scene=new Scene();scene.add(button);
        assertNoField(bridge.describeUi(),"sample_icon");assertFalse(bridge.drawnIntentReady());
        bridge.captureDrawnEvidence();assertTrue(bridge.drawnIntentReady());
        scene=previous;scene.add(button);assertNoField(bridge.describeUi(),"sample_icon");
        bridge.captureDrawnEvidence();assertTrue(node(bridge.describeUi(),"button").containsKey("sample_icon"));
    }

    @Test void realBuffButtonGreyMaskCannotPublishItsPostDrawPixelHeight()throws Exception{
        Class<?> type=Class.forName("com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator$BuffButton");
        IconButton button=(IconButton)FloatingAppearanceTest.allocate(type);initialize(button);
        FloatingAppearanceTest.field(button,Group.class,"members",new ArrayList<Gizmo>());
        BuffIcon icon=FloatingAppearanceTest.allocate(BuffIcon.class);initialize(icon);icon.x=icon.y=10;icon.width=icon.height=7;
        FloatingAppearanceTest.field(icon,BuffIcon.class,"displayedIndex",12);
        Image grey=new Image();grey.x=grey.y=10;grey.width=7;grey.height=2;grey.camera=icon.camera;
        button.add(icon);button.add(grey);FloatingAppearanceTest.field(button,IconButton.class,"icon",icon);
        FloatingAppearanceTest.field(button,type,"grey",grey);
        FloatingAppearanceTest.field(button,type,"buff",new Buff(){@Override public String name(){return ResourceTextFixture.literal("Observed buff");}});
        scene.add(button);bridge.captureDrawnEvidence();Map<String,Object> drawn=node(bridge.frozenUi(),"button");
        assertEquals(2,((Map<?,?>)drawn.get("icon_overlay")).get("covered_pixels"));
        grey.height=5;icon.hardlight(0xFF0000);
        assertEquals(drawn.get("icon_overlay"),node(bridge.frozenUi(),"button").get("icon_overlay"));
        assertEquals(drawn.get("icon"),node(bridge.frozenUi(),"button").get("icon"));assertFalse(bridge.drawnIntentReady());
        bridge.captureDrawnEvidence();assertTrue(bridge.drawnIntentReady());
        assertEquals(5,((Map<?,?>)node(bridge.describeUi(),"button").get("icon_overlay")).get("covered_pixels"));
    }

    @Test void nativeIconButtonDimmedThresholdAndImageAlphaDescribeTheSameCompletedDraw(){
        IconButton button=new IconButton(){
            @Override protected void createChildren(){}
            @Override protected void layout(){}
            @Override protected String hoverText(){return ResourceTextFixture.literal("Native icon choice");}
            @Override protected void onClick(){}
        };
        Image icon=image();icon.alpha(.36f);button.icon(icon);scene.add(button);bridge.captureDrawnEvidence();
        Map<String,Object> drawn=node(bridge.frozenUi(),"button");Object appearance=drawn.get("icon");
        assertEquals(false,drawn.get("dimmed"));assertEquals(.36f,((Map<?,?>)appearance).get("alpha"));
        String intent=bridge.intentSignature();icon.alpha(.35f);
        Map<String,Object> afterStep=node(bridge.frozenUi(),"button");
        assertEquals(false,afterStep.get("dimmed"));assertEquals(appearance,afterStep.get("icon"));
        assertFalse(bridge.drawnIntentReady());assertNotEquals(intent,bridge.intentSignature());
        bridge.captureDrawnEvidence();Map<String,Object> next=node(bridge.frozenUi(),"button");
        assertEquals(true,next.get("dimmed"));assertEquals(.35f,((Map<?,?>)next.get("icon")).get("alpha"));
        assertTrue(bridge.drawnIntentReady());assertEquals(false,drawn.get("dimmed"));
    }

    @Test void ownerKillAndReviveWithTheSameChildrenRequiresNewDisplayEvidence(){
        StatusButton button=new StatusButton(false);scene.add(button);bridge.captureDrawnEvidence();
        Image sameChild=button.image;long lifetime=button.observationLifetime();
        button.kill();button.revive();sameChild.revive();
        assertSame(sameChild,button.childrenSnapshot().get(0));assertTrue(button.observationLifetime()>lifetime);
        assertNoField(bridge.describeUi(),"sample_icon");assertFalse(bridge.drawnIntentReady());
        bridge.captureDrawnEvidence();assertTrue(bridge.drawnIntentReady());
        assertTrue(node(bridge.describeUi(),"button").containsKey("sample_icon"));
    }

    @Test void decorativeEmitterAttachmentParticleChurnAndReuseDoNotInvalidateAnUnchangedControl(){
        StatusButton button=new StatusButton(false);scene.add(button);bridge.captureDrawnEvidence();
        Object before=node(bridge.frozenUi(),"button").get("sample_icon");String intent=bridge.intentSignature();
        Emitter decoration=new Emitter();button.add(decoration);Image particle=image();decoration.add(particle);
        assertEquals(before,node(bridge.frozenUi(),"button").get("sample_icon"));assertTrue(bridge.drawnIntentReady());
        particle.kill();particle.revive();decoration.remove(particle);decoration.add(image());decoration.add(image());
        decoration.revive();assertEquals(before,node(bridge.frozenUi(),"button").get("sample_icon"));
        assertTrue(bridge.drawnIntentReady());assertEquals(intent,bridge.intentSignature());
        button.remove(decoration);assertEquals(before,node(bridge.frozenUi(),"button").get("sample_icon"));
        assertTrue(bridge.drawnIntentReady());assertEquals(intent,bridge.intentSignature());
    }

    @Test @SuppressWarnings("unchecked") void opaquePresentationAndUnknownExtensionMetadataAreDeeplyFrozenWithoutNumericCoercion(){
        StatusButton button=new StatusButton(false);
        List<Object> values=new ArrayList<>(Arrays.asList(7,.25f,false,null));
        Map<String,Object> detail=map("count",3,"values",values);
        List<Object> diagnostics=new ArrayList<>(List.of(map("field","unknown_extension","code","fixture")));
        Map<String,Object> presentation=map("status","partial","diagnostics",diagnostics,"detail",detail);
        button.metadata.put("presentation",presentation);button.metadata.put("unknown_extension",map("nested",detail));
        scene.add(button);bridge.captureDrawnEvidence();Map<String,Object> before=node(bridge.frozenUi(),"button");
        Object frozenPresentation=before.get("presentation"),frozenExtension=before.get("unknown_extension");
        values.set(0,99);values.add("late");detail.put("count",9);presentation.put("status","complete");
        diagnostics.add(map("field","late","code","late"));
        Map<String,Object> afterStep=node(bridge.frozenUi(),"button");
        assertEquals(frozenPresentation,afterStep.get("presentation"));assertEquals(frozenExtension,afterStep.get("unknown_extension"));
        Map<String,Object> capturedPresentation=(Map<String,Object>)frozenPresentation;
        Map<String,Object> capturedDetail=(Map<String,Object>)capturedPresentation.get("detail");
        List<Object> capturedValues=(List<Object>)capturedDetail.get("values");
        assertEquals(Arrays.asList(7,.25f,false,null),capturedValues);
        assertInstanceOf(Integer.class,capturedValues.get(0));assertInstanceOf(Float.class,capturedValues.get(1));
        assertThrows(UnsupportedOperationException.class,()->capturedDetail.put("count",0));
        assertThrows(UnsupportedOperationException.class,()->capturedValues.add(0));
        assertThrows(UnsupportedOperationException.class,()->((List<Object>)capturedPresentation.get("diagnostics")).clear());
        Map<String,Object> capturedExtension=(Map<String,Object>)frozenExtension;
        assertThrows(UnsupportedOperationException.class,()->((Map<String,Object>)capturedExtension.get("nested")).clear());
        bridge.captureDrawnEvidence();assertNotEquals(frozenPresentation,node(bridge.frozenUi(),"button").get("presentation"));
        assertEquals("partial",capturedPresentation.get("status"),"New capture must not rewrite the previous observation");
    }

    /** Has real native Image state, but intentionally performs no GL draw or input registration. */
    private static final class StatusButton extends Button implements RenderedStatus {
        Image image=image();final boolean passive;boolean selected;int clicks;
        final Map<String,Object> metadata=new LinkedHashMap<>();
        StatusButton(boolean passive){this.passive=passive;camera=new Camera(0,0,100,100,1);add(image);}
        @Override protected void createChildren(){}
        @Override protected String hoverText(){return ResourceTextFixture.literal("Visible choice");}
        @Override protected void onClick(){clicks++;}
        @Override public Map<String,Object> renderedStatus(){
            if(image.parent!=this||!image.visible||!image.exists)return Collections.emptyMap();
            RectF frame=image.frame();Map<String,Object> result=map("sample_icon",map("frame",List.of(frame.left,frame.top,frame.right,frame.bottom),
                    "color",image.displayedTextColor(),"alpha",image.alpha(),"selected",selected));
            result.putAll(metadata);return result;
        }
        @Override public Map<String,Object> intentStatus(){return passive?map("sample_icon",map("selected",selected)):renderedStatus();}
    }
    private static Image image(){
        try {SmartTexture texture=FloatingAppearanceTest.allocate(SmartTexture.class);texture.width=texture.height=32;
            Image image=new Image();image.texture=texture;image.frame(new RectF(0,0,.25f,.25f));image.x=image.y=10;image.camera=new Camera(0,0,100,100,1);return image;
        }catch(Exception error){throw new AssertionError(error);}
    }
    private static void initialize(Gizmo value){
        value.exists=value.visible=value.active=value.alive=true;value.camera=new Camera(0,0,100,100,1);
        if(value instanceof Visual){Visual visual=(Visual)value;visual.origin=new PointF();visual.scale=new PointF(1,1);visual.rm=visual.gm=visual.bm=visual.am=1;}
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> node(Map<String,Object> ui,String role){
        return ((List<Map<String,Object>>)ui.get("controls")).stream().filter(row->role.equals(row.get("role"))).findFirst().orElseThrow();
    }
    private static void assertNoField(Map<String,Object> ui,String field){
        assertTrue(((List<?>)ui.get("controls")).stream().noneMatch(row->((Map<?,?>)row).containsKey(field)),"No completed matching draw may expose "+field);
    }
}
