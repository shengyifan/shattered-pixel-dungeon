import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Test-only opt-in observer-boundary injection for the actual packaged launcher. */
public final class Cli904ExpressionFixtureAgent {
    public static void premain(String argument, Instrumentation instrumentation) throws Exception {
        String configured = System.getenv("SPD_CLI904_FIXTURE_ROOT");
        if (configured == null || !configured.equals(argument))
            throw new IllegalArgumentException("Explicit CLI904 fixture root is required");
        Path root = Path.of(configured).toRealPath();
        String nonce = System.getenv("SPD_CLI904_FIXTURE_NONCE");
        if (!root.toString().contains("/desktop-control/build/fixtures/cli904-expression-")
                || nonce == null || !nonce.matches("[a-f0-9]{32}")
                || !root.getFileName().toString().endsWith(nonce)
                || !Files.readString(root.resolve("test_fixture.json")).contains("\"counts_as_win\": false")
                || !Files.readString(root.resolve("fixture.nonce")).equals(nonce))
            throw new IllegalArgumentException("A unique marked non-playthrough fixture directory is required");
        Path ownJar = Path.of(Cli904ExpressionFixtureAgent.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        instrumentation.appendToSystemClassLoaderSearch(new JarFile(ownJar.toFile()));
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                                              ProtectionDomain domain, byte[] bytes) {
                if (!"com/shatteredpixel/shatteredpixeldungeon/control/game/GameController".equals(name)) return null;
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override public MethodVisitor visitMethod(int access, String method, String descriptor,
                                                               String signature, String[] exceptions) {
                        MethodVisitor visitor = super.visitMethod(access, method, descriptor, signature, exceptions);
                        if (!method.equals("afterFrame") || !descriptor.equals("()V")) return visitor;
                        return new MethodVisitor(Opcodes.ASM9, visitor) {
                            @Override public void visitCode() {
                                super.visitCode();
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "com/shatteredpixel/shatteredpixeldungeon/control/desktop/Cli904ExpressionScenarioFixtures",
                                        "pulse", "()V", false);
                            }
                        };
                    }
                }, 0);
                return writer.toByteArray();
            }
        });
        Files.writeString(root.resolve("agent-" + ProcessHandle.current().pid() + ".ready"),
                "test-only packaged runtime agent\n");
    }
}
