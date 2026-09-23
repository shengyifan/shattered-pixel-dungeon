package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndMessage;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import java.util.Arrays;

/** Original renderer effects in an explicitly isolated fixture process. */
final class ScreenVisualFixtures {
    static boolean supports(String name){return Arrays.asList("screen-flash-additive","screen-flash-normal","screen-shake",
            "screen-flash-below-modal","screen-flash-above-modal").contains(name);}

    static void prepare(String name,Hero hero) {
        switch(name) {
            case "screen-flash-additive":GameScene.flash(0x80FF8040,true);break;
            case "screen-flash-normal":GameScene.flash(0x80904020,false);break;
            case "screen-shake":SPDSettings.screenShake(1);PixelScene.shake(3f,1f);break;
            case "screen-flash-below-modal":case "screen-flash-above-modal": {
                GameScene.flash(0x80FFFFFF,true);
                Game.runOnRenderThread(()->{
                    WndMessage modal=new WndMessage(Messages.literal("Screen-effect occlusion fixture"));GameScene.show(modal);
                    // Group.add may reuse a hole, so explicitly arrange the native draw layers under test.
                    GameScene scene=(GameScene)Game.scene();scene.bringToFront(modal);
                    if(name.equals("screen-flash-above-modal"))for(Gizmo child:scene.childrenSnapshot())
                        if(child!=null&&child.getClass().getEnclosingClass()==PixelScene.class&&child.getClass().getSimpleName().equals("Fader"))
                            scene.bringToFront(child);
                });break;
            }
            default:throw new IllegalArgumentException("Unknown screen visual fixture");
        }
    }
}
