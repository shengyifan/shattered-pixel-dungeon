import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;
import org.objectweb.asm.*;

/** Test-only uncaught Actor failure, never added to a release or a formal playthrough. */
public final class UncaughtRuntimeAgent {
    private static final String ROOT="com/shatteredpixel/shatteredpixeldungeon/";
    private static final String ACTOR=ROOT+"actors/Actor";
    private static final String STORE=ROOT+"control/desktop/store/AuditStore";
    private static final AtomicBoolean fired=new AtomicBoolean();
    private static volatile boolean selectedRequest;
    private static Path directory;

    public static void premain(String argument,Instrumentation instrumentation)throws Exception{
        directory=Path.of(argument).toRealPath();
        if(!directory.toString().contains("/desktop-control/build/fixtures/")
                ||!Files.readString(directory.resolve("test_fixture.json")).contains("\"counts_as_win\": false"))
            throw new IllegalArgumentException("Uncaught injection requires an explicit isolated test fixture");
        instrumentation.addTransformer(new ClassFileTransformer(){
            @Override public byte[] transform(ClassLoader loader,String name,Class<?> redef,ProtectionDomain domain,byte[] bytes){
                if(!ACTOR.equals(name)&&!STORE.equals(name))return null;
                ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9,writer){
                    @Override public MethodVisitor visitMethod(int access,String method,String desc,String signature,String[] exceptions){
                        return new MethodVisitor(Opcodes.ASM9,super.visitMethod(access,method,desc,signature,exceptions)){
                            @Override public void visitCode(){
                                super.visitCode();
                                if(STORE.equals(name)&&method.equals("begin")&&desc.contains("[B")){
                                    super.visitVarInsn(Opcodes.ALOAD,2);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,"UncaughtRuntimeAgent","select","(Ljava/lang/String;)V",false);
                                }
                            }
                            @Override public void visitMethodInsn(int opcode,String owner,String called,String descriptor,boolean isInterface){
                                if(ACTOR.equals(name)&&method.equals("process")&&ACTOR.equals(owner)&&called.equals("act")&&descriptor.equals("()Z"))
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,"UncaughtRuntimeAgent","fire","()V",false);
                                super.visitMethodInsn(opcode,owner,called,descriptor,isInterface);
                            }
                        };
                    }
                },0);
                return writer.toByteArray();
            }
        });
    }

    public static void select(String id){
        try{Path armed=directory.resolve("uncaught.armed");selectedRequest=Files.isRegularFile(armed)&&Files.readString(armed).equals(id);}
        catch(Exception error){throw new AssertionError(error);}
    }
    public static void fire(){
        if(!selectedRequest||!fired.compareAndSet(false,true))return;
        RuntimeException failure=new RuntimeException("PRIVATE_UNCAUGHT_ACTOR_FIXTURE");
        try{
            StringBuilder marker=new StringBuilder("thread="+Thread.currentThread().getName()+"\n");
            for(StackTraceElement frame:failure.getStackTrace())marker.append(frame).append('\n');
            Files.writeString(directory.resolve("uncaught.reached"),marker,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        }catch(Exception error){throw new AssertionError("Could not record uncaught fixture marker",error);}
        throw failure;
    }
}
