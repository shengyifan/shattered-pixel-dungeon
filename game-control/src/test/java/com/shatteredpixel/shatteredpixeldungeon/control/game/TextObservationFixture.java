package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.shatteredpixel.shatteredpixeldungeon.control.game.text.TextProvenance;
import com.watabou.noosa.Game;
import com.watabou.noosa.RuntimeObserver;

/** Installs only the neutral text hooks; it cannot execute or inspect game state. */
final class TextObservationFixture implements AutoCloseable {
    private final RuntimeObserver previous=Game.observer;
    TextObservationFixture() {
        Game.observer=new RuntimeObserver() {
            @Override public String onTextResource(String value,String key,String language,Object[] args) {
                return TextProvenance.INSTANCE.onTextResource(value,key,language,args);
            }
            @Override public String onTextResource(String value,String key,String language,Object[] args,String guiTemplate) {
                return TextProvenance.INSTANCE.onTextResource(value,key,language,args,guiTemplate);
            }
            @Override public String onTextOperation(String operation,String value,Object... args) {
                return TextProvenance.INSTANCE.onTextOperation(operation,value,args);
            }
            @Override public void onTextBound(Object owner,String value) {TextProvenance.INSTANCE.onTextBound(owner,value);}
            @Override public void onTextReleased(Object owner) {TextProvenance.INSTANCE.onTextReleased(owner);}
        };
    }
    @Override public void close() {Game.observer=previous;}
}
