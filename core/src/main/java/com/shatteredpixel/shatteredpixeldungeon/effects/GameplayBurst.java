package com.shatteredpixel.shatteredpixeldungeon.effects;

import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.particles.Emitter;
import com.watabou.utils.PointF;
import java.util.Map;

/** Explicitly reviewed native emission sources. Requested counts and shader details never enter observation. */
public final class GameplayBurst {
    private GameplayBurst() {}

    public static void burst(Emitter emitter,Emitter.Factory factory,int amount,String publicKind,int cell,boolean measuredCount){
        Map<Gizmo,Long> preceding=before(emitter);
        emitter.burst(factory,amount);
        tag(emitter,factory,publicKind,cell,null,measuredCount,preceding);
    }
    public static void burstForCharacter(Emitter emitter,Emitter.Factory factory,int amount,String publicKind,CharSprite sprite,boolean measuredCount){
        Map<Gizmo,Long> preceding=before(emitter);
        emitter.burst(factory,amount);
        tag(emitter,factory,publicKind,-1,sprite,measuredCount,preceding);
    }
    public static void start(Emitter emitter,Emitter.Factory factory,float interval,int quantity,String publicKind,int cell,boolean measuredCount){
        Map<Gizmo,Long> preceding=before(emitter);
        emitter.start(factory,interval,quantity);
        tag(emitter,factory,publicKind,cell,null,measuredCount,preceding);
    }
    public static void startForCharacter(Emitter emitter,Emitter.Factory factory,float interval,int quantity,String publicKind,CharSprite sprite,boolean measuredCount){
        Map<Gizmo,Long> preceding=before(emitter);
        emitter.start(factory,interval,quantity);
        tag(emitter,factory,publicKind,-1,sprite,measuredCount,preceding);
    }
    private static Map<Gizmo,Long> before(Emitter emitter){
        return Game.observer.observesVisualCues()&&emitter!=null?CellParticleCue.existingContributors(emitter):null;
    }
    private static void tag(Emitter emitter,Emitter.Factory factory,String kind,int cell,CharSprite sprite,
                            boolean measured,Map<Gizmo,Long> preceding){
        if(preceding!=null&&Game.observer.observesVisualCues())
            emitter.observeDraw(CellParticleCue.observedSignal(kind,cell,sprite,emitter,factory,measured,preceding));
    }

    // The scoped adapter retains the exact native sprite/Splash dispatch, its guards and factory RNG.
    // It is observer-owned and thread-local: unrelated, unannotated Splash calls remain unobserved.
    private static final ThreadLocal<Declaration> SPLASH = new ThreadLocal<>();
    private static final class Declaration {
        final String kind;final int cell;final CharSprite sprite;final boolean measured;
        Declaration(String kind,int cell,CharSprite sprite,boolean measured){this.kind=kind;this.cell=cell;this.sprite=sprite;this.measured=measured;}
    }
    static final class SplashCapture {
        final Declaration declaration;final Map<Gizmo,Long> preceding;
        SplashCapture(Declaration declaration,Map<Gizmo,Long> preceding){this.declaration=declaration;this.preceding=preceding;}
    }
    public static void spriteBurst(CharSprite sprite,int color,int amount,String publicKind,boolean measuredCount){
        Declaration previous=SPLASH.get();
        SPLASH.set(new Declaration(publicKind,-1,sprite,measuredCount));
        try{sprite.burst(color,amount);}finally{restore(previous);}
    }
    public static void splash(PointF point,int color,int amount,String publicKind,int cell,boolean measuredCount){
        Declaration previous=SPLASH.get();
        SPLASH.set(new Declaration(publicKind,cell,null,measuredCount));
        try{Splash.at(point,color,amount);}finally{restore(previous);}
    }
    private static void restore(Declaration previous){if(previous==null)SPLASH.remove();else SPLASH.set(previous);}
    static SplashCapture beforeSplash(Emitter emitter){
        Declaration declaration=SPLASH.get();
        Map<Gizmo,Long> preceding=declaration==null?null:before(emitter);
        return preceding==null?null:new SplashCapture(declaration,preceding);
    }
    static void afterSplash(SplashCapture captured,Emitter emitter,Emitter.Factory factory){
        if(captured==null)return;
        Declaration declaration=captured.declaration;
        tag(emitter,factory,declaration.kind,declaration.cell,declaration.sprite,declaration.measured,captured.preceding);
    }
}
