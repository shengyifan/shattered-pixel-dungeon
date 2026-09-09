import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.ProtectionDomain;
import java.util.*;
import org.objectweb.asm.*;

/** Injects only known fixture text into the real GameLog; never used by a formal playthrough. */
public final class GameLogFixtureAgent {
    private static Path directory;
    private static String selected="",mode="";
    private static Object pendingLog;
    private static boolean awaitingDraw;
    private static final String LOG="com/shatteredpixel/shatteredpixeldungeon/ui/GameLog";
    public static void premain(String argument,Instrumentation instrumentation)throws Exception{
        directory=Path.of(argument).toRealPath();
        if(!directory.toString().contains("/desktop-control/build/fixtures/")
                ||!Files.readString(directory.resolve("test_fixture.json")).contains("\"counts_as_win\": false"))
            throw new IllegalArgumentException("Game log injection requires an isolated non-playthrough fixture");
        instrumentation.addTransformer(new ClassFileTransformer(){
            @Override public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes){
                if(!LOG.equals(name))return null;
                ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9,writer){
                    @Override public MethodVisitor visitMethod(int access,String method,String descriptor,String signature,String[] exceptions){
                        return new MethodVisitor(Opcodes.ASM9,super.visitMethod(access,method,descriptor,signature,exceptions)){
                            @Override public void visitCode(){super.visitCode();if(method.equals("update")&&descriptor.equals("()V")){
                                super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"GameLogFixtureAgent","inject","(Ljava/lang/Object;)V",false);
                            }}
                            @Override public void visitInsn(int opcode){
                                if(method.equals("draw")&&descriptor.equals("()V")&&opcode==Opcodes.RETURN){
                                    super.visitVarInsn(Opcodes.ALOAD,0);super.visitMethodInsn(Opcodes.INVOKESTATIC,"GameLogFixtureAgent","drawn","(Ljava/lang/Object;)V",false);
                                }super.visitInsn(opcode);
                            }
                        };
                    }
                },0);return writer.toByteArray();
            }
        });
    }
    public static void inject(Object log){
        try{
            Path command=directory.resolve("log-fixture.command");if(!Files.isRegularFile(command))return;
            String value=Files.readString(command);if(value.equals(selected))return;
            String[] parts=value.split("\\|",2);if(parts.length!=2)throw new IllegalArgumentException("Invalid log fixture command");
            selected=value;mode=parts[1];pendingLog=log;awaitingDraw=true;
            if(mode.equals("console")){System.err.println("PRIVATE_CONSOLE_LOG_FIXTURE");return;}
            if(mode.equals("rebuild")){Class.forName("com.watabou.noosa.Game").getMethod("resetScene").invoke(null);return;}
            Class.forName(LOG.replace('/','.')).getMethod("wipe").invoke(null);
            Class<?> glog=Class.forName("com.shatteredpixel.shatteredpixeldungeon.utils.GLog");
            Method newline=glog.getMethod("newLine"),line=glog.getMethod("i",String.class,Object[].class);
            newline.invoke(null);
            if(mode.equals("burst")){
                for(int i=0;i<18;i++){newline.invoke(null);line.invoke(null,String.format("BURST_%02d",i),new Object[0]);}
            }else if(mode.equals("long")){
                StringBuilder text=new StringBuilder("TOP_NEVER_VISIBLE ");for(int i=0;i<1000;i++)text.append("filler ");text.append("BOTTOM_VISIBLE");
                line.invoke(null,text.toString(),new Object[0]);
            }else if(mode.equals("same"))line.invoke(null,"REPEATED_VISIBLE_TEXT",new Object[0]);
            else throw new IllegalArgumentException("Unsupported log fixture mode");
        }catch(Exception error){throw new AssertionError(error);}
    }
    public static void drawn(Object log){
        if(!awaitingDraw||mode.equals("rebuild")&&log==pendingLog)return;
        try{
            List<String> raw=new ArrayList<>();
            List<?> children=(List<?>)log.getClass().getMethod("childrenSnapshot").invoke(log);
            for(Object child:children)if(child!=null&&child.getClass().getName().equals("com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock"))
                raw.add((String)child.getClass().getMethod("text").invoke(child));
            Map<String,Object> result=new LinkedHashMap<>();result.put("command",selected);result.put("mode",mode);
            result.put("raw_control_texts",raw);result.put("rebuilt",log!=pendingLog);
            String encoded=(String)Class.forName("com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec").getMethod("encode",Object.class).invoke(null,result);
            Path temporary=directory.resolve("log-fixture.done.tmp");Files.writeString(temporary,encoded,StandardCharsets.UTF_8);
            Files.move(temporary,directory.resolve("log-fixture.done.json"),StandardCopyOption.REPLACE_EXISTING);
            awaitingDraw=false;
        }catch(Exception error){throw new AssertionError(error);}
    }
}
