import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import org.objectweb.asm.*;

/** Test-only bytecode barriers. Never compiled into a release or used by formal playthroughs. */
public final class EngineBoundaryAgent {
    private static Path directory;
    private static String selected;
    private static final AtomicBoolean reached=new AtomicBoolean();
    private static volatile boolean selectedRequest;
    private static final String PREFIX="com/shatteredpixel/shatteredpixeldungeon/";
    private static final String STORE=PREFIX+"control/desktop/store/AuditStore";
    private static final String CONTROLLER=PREFIX+"control/game/GameController";
    private static final String DUNGEON=PREFIX+"Dungeon";

    public static void premain(String args,Instrumentation instrumentation)throws Exception {
        String[] parts=args.split(";",2);
        if(parts.length!=2)throw new IllegalArgumentException("Expected stage;fixture-directory");
        selected=parts[0];directory=Path.of(parts[1]).toRealPath();
        if(!directory.toString().contains("/desktop-control/build/fixtures/")
                || !Files.readString(directory.resolve("test_fixture.json")).contains("\"counts_as_win\": false"))
            throw new IllegalArgumentException("Only explicit non-playthrough fixtures may use instrumentation");
        instrumentation.addTransformer(new ClassFileTransformer(){
            @Override public byte[] transform(ClassLoader loader,String name,Class<?> redef,ProtectionDomain domain,byte[] bytes){
                if(!name.equals(STORE)&&!name.equals(CONTROLLER)&&!name.equals(DUNGEON))return null;
                ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9,writer){
                    @Override public MethodVisitor visitMethod(int access,String method,String descriptor,String signature,String[] exceptions){
                        MethodVisitor target=super.visitMethod(access,method,descriptor,signature,exceptions);
                        return new MethodVisitor(Opcodes.ASM9,target){
                            private void hit(String stage){
                                if(!selected.equals(stage))return;
                                super.visitLdcInsn(stage);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,"EngineBoundaryAgent","hit","(Ljava/lang/String;)V",false);
                            }
                            private boolean begin(){return name.equals(STORE)&&method.equals("begin")&&descriptor.contains("[B");}
                            @Override public void visitCode(){
                                super.visitCode();
                                if(begin()){
                                    super.visitVarInsn(Opcodes.ALOAD,2);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,"EngineBoundaryAgent","selectRequest","(Ljava/lang/String;)V",false);
                                    hit("before-register");
                                }
                                if(name.equals(STORE)&&method.equals("markExecuting"))hit("before-intent");
                                if(name.equals(CONTROLLER)&&method.equals("perform"))hit("before-callback");
                                if(name.equals(STORE)&&method.equals("complete"))hit("after-settlement-before-result");
                                if(name.equals(DUNGEON)&&method.equals("init")&&descriptor.equals("()V"))hit("before-new-run-init");
                            }
                            @Override public void visitInsn(int opcode){
                                if(opcode>=Opcodes.IRETURN&&opcode<=Opcodes.RETURN){
                                    if(begin())hit("after-register");
                                    if(name.equals(STORE)&&method.equals("markExecuting"))hit("after-intent");
                                    if(name.equals(CONTROLLER)&&method.equals("perform"))hit("after-callback");
                                    if(name.equals(CONTROLLER)&&method.equals("perform")&&selected.equals("slow-quit")){
                                        super.visitVarInsn(Opcodes.ALOAD,1);
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC,"EngineBoundaryAgent","holdQuit","(Ljava/util/Map;)V",false);
                                    }
                                    if(name.equals(STORE)&&method.equals("complete"))hit("after-result-before-output");
                                }
                                super.visitInsn(opcode);
                            }
                            @Override public void visitMethodInsn(int opcode,String owner,String called,String desc,boolean isInterface){
                                if(name.equals(STORE)&&method.equals("transaction")&&owner.equals("java/sql/Connection")&&called.equals("commit"))
                                    hit("inside-dual-transaction");
                                super.visitMethodInsn(opcode,owner,called,desc,isInterface);
                                if(name.equals(DUNGEON)&&method.equals("saveAll")&&owner.equals(DUNGEON)&&called.equals("saveGameChecked"))
                                    hit("between-save-files");
                            }
                        };
                    }
                },0);
                return writer.toByteArray();
            }
        });
    }

    public static void selectRequest(String id){
        try { selectedRequest=Files.isRegularFile(directory.resolve("barrier.armed"))
                && Files.readString(directory.resolve("barrier.armed")).equals(id); }
        catch(Exception failure){throw new AssertionError(failure);}
    }

    /** Delay only a selected native quit after it has saved and set its exit intent. */
    public static void holdQuit(Map<?,?> args){
        if("app.quit".equals(args.get("action")))hit("slow-quit");
    }

    public static void hit(String stage){
        if(!selected.equals(stage)||!selectedRequest||!reached.compareAndSet(false,true))return;
        try {
            StringBuilder marker=new StringBuilder(stage+"\nthread="+Thread.currentThread().getName()+"\n");
            for(StackTraceElement frame:Thread.currentThread().getStackTrace())marker.append(frame).append('\n');
            try(FileChannel output=FileChannel.open(directory.resolve("barrier.reached"),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
                ByteBuffer bytes=ByteBuffer.wrap(marker.toString().getBytes(StandardCharsets.UTF_8));
                while(bytes.hasRemaining())output.write(bytes);
                output.force(true);
            }
            if(selected.equals("slow-quit")){
                long deadline=System.nanoTime()+120_000_000_000L;
                while(!Files.isRegularFile(directory.resolve("barrier.release"))){
                    if(System.nanoTime()>deadline)throw new AssertionError("Timed out waiting to release test-only slow quit");
                    LockSupport.parkNanos(10_000_000L);
                }
            }else while(true)LockSupport.parkNanos(1_000_000_000L);
        }catch(Exception failure){throw new AssertionError("Cannot record test barrier",failure);}
    }
}
