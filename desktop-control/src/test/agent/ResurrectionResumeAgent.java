import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.ProtectionDomain;
import java.util.*;
import org.objectweb.asm.*;

/** Test-only original lifecycle trigger and read-only proof; never edits a game/save field. */
public final class ResurrectionResumeAgent {
    private static Path directory;
    private static boolean paused, resumedWritten;
    public static void premain(String argument, Instrumentation instrumentation) throws Exception {
        directory=Path.of(argument).toRealPath();
        if(!directory.toString().contains("/desktop-control/build/fixtures/")
                ||!java.util.regex.Pattern.compile("\"counts_as_win\"\\s*:\\s*false")
                    .matcher(Files.readString(directory.resolve("test_fixture.json"))).find())
            throw new IllegalArgumentException("Resurrection lifecycle test requires an isolated fixture");
        instrumentation.addTransformer(new ClassFileTransformer(){
            @Override public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes){
                if(!name.equals("com/watabou/noosa/Game"))return null;
                ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9,writer){
                    @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions){
                        MethodVisitor method=super.visitMethod(access,name,descriptor,signature,exceptions);
                        if(!name.equals("render")||!descriptor.equals("()V"))return method;
                        return new MethodVisitor(Opcodes.ASM9,method){
                            @Override public void visitInsn(int opcode){
                                if(opcode==Opcodes.RETURN)super.visitMethodInsn(Opcodes.INVOKESTATIC,"ResurrectionResumeAgent","afterFrame","()V",false);
                                super.visitInsn(opcode);
                            }
                        };
                    }
                },0);return writer.toByteArray();
            }
        });
    }
    private static Object field(Class<?> type,String name,Object owner)throws Exception{
        Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(owner);
    }
    private static Map<String,Object> proof()throws Exception{
        Class<?> game=Class.forName("com.watabou.noosa.Game");
        Object scene=game.getMethod("scene").invoke(null);
        if(scene==null||!scene.getClass().getSimpleName().equals("GameScene"))return null;
        Class<?> dungeon=Class.forName("com.shatteredpixel.shatteredpixeldungeon.Dungeon");
        Object hero=dungeon.getField("hero").get(null);if(hero==null)return null;
        Object window=Class.forName("com.shatteredpixel.shatteredpixeldungeon.windows.WndResurrect").getField("instance").get(null);
        if(window==null)return null;
        Class<?> sceneType=Class.forName("com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene");
        Thread actor=(Thread)field(sceneType,"actorThread",null);
        Class<?> actorType=Class.forName("com.shatteredpixel.shatteredpixeldungeon.actors.Actor");
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("thread",Thread.currentThread().getName());data.put("hp",hero.getClass().getField("HP").get(hero));
        data.put("run_id",dungeon.getField("runId").get(null));data.put("window",window.getClass().getSimpleName());
        data.put("actor_thread",actor==null?null:actor.getName());data.put("actor_alive",actor!=null&&actor.isAlive());
        data.put("actor_yielded",actorType.getMethod("isYielded").invoke(null));
        data.put("actor_processing",actorType.getMethod("processing").invoke(null));
        data.put("fullscreen",Class.forName("com.badlogic.gdx.Graphics").getMethod("isFullscreen")
                .invoke(Class.forName("com.badlogic.gdx.Gdx").getField("graphics").get(null)));
        data.put("language",Class.forName("com.shatteredpixel.shatteredpixeldungeon.messages.Messages").getMethod("lang").invoke(null).toString());
        return data;
    }
    private static void write(String filename,Map<String,Object> data)throws Exception{
        String json=(String)Class.forName("com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec")
                .getMethod("encode",Object.class).invoke(null,data);
        Path temp=directory.resolve(filename+".tmp");Files.writeString(temp,json);
        Files.move(temp,directory.resolve(filename),StandardCopyOption.REPLACE_EXISTING);
    }
    public static void afterFrame(){
        try{
            if(Files.isRegularFile(directory.resolve("resume-observe.armed"))&&!resumedWritten){
                Map<String,Object> data=proof();
                if(data!=null){write("resume-observed.json",data);resumedWritten=true;}
            }
            if(paused||!Files.isRegularFile(directory.resolve("native-pause.armed")))return;
            Map<String,Object> data=proof();if(data==null)return;
            if(((Number)data.get("hp")).intValue()>0||!Boolean.TRUE.equals(data.get("actor_yielded")))
                throw new AssertionError("Pause must occur at the original death UI handoff");
            paused=true;
            Class<?> game=Class.forName("com.watabou.noosa.Game");
            game.getMethod("pause").invoke(game.getField("instance").get(null));
            data.put("original_pause_returned",true);write("native-pause-completed.json",data);
            Object app=Class.forName("com.badlogic.gdx.Gdx").getField("app").get(null);
            Class.forName("com.badlogic.gdx.Application").getMethod("exit").invoke(app);
        }catch(Exception error){throw new AssertionError(error);}
    }
}
