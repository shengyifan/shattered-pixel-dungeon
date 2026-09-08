package com.shatteredpixel.shatteredpixeldungeon.control.game;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputProcessor;
import com.watabou.input.InputHandler;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class InputGenerationTest {
    @Test public void countsConsumedUserEventsWithoutCountingPointerHover(){
        AtomicReference<InputProcessor> processor=new AtomicReference<>();
        Input input=(Input)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Input.class},(p,m,args)->{
            if(m.getName().equals("setInputProcessor"))processor.set((InputProcessor)args[0]);
            return null;
        });
        InputHandler handler=new InputHandler(input);
        handler.addInputProcessor(new InputAdapter(){
            @Override public boolean keyDown(int key){return true;}
            @Override public boolean mouseMoved(int x,int y){return true;}
            @Override public boolean touchDown(int x,int y,int pointer,int button){return true;}
        });
        assertEquals(0,handler.interactionGeneration());
        processor.get().mouseMoved(1,2);assertEquals(0,handler.interactionGeneration());
        assertTrue(processor.get().keyDown(Input.Keys.A));assertEquals(1,handler.interactionGeneration());
        assertTrue(processor.get().touchDown(1,2,0,0));assertEquals(2,handler.interactionGeneration());
    }
}
