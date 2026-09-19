package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.control.desktop.store.AuditStore;
import com.shatteredpixel.shatteredpixeldungeon.control.desktop.store.AuditException;
import com.shatteredpixel.shatteredpixeldungeon.control.game.GameController;
import com.shatteredpixel.shatteredpixeldungeon.desktop.DesktopLauncher;
import com.shatteredpixel.shatteredpixeldungeon.desktop.ProfileLock;
import com.watabou.noosa.Game;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;

/** Launches the ordinary GUI and the serial machine channel in the same JVM. */
public final class SpdctlLauncher {
    public static void main(String[] args){
        PrintStream protocol=new PrintStream(new FileOutputStream(FileDescriptor.out),true,StandardCharsets.UTF_8);
        PrintStream diagnostics=new PrintStream(new FileOutputStream(FileDescriptor.err),true,StandardCharsets.UTF_8);
        if(args.length==1&&args[0].equals("--help")){
            try(InputStream help=SpdctlLauncher.class.getResourceAsStream("/cli-help.md")){
                if(help==null)throw new IOException("Missing bundled help resource");
                byte[] bytes=help.readAllBytes();
                protocol.write(bytes,0,bytes.length);
                if(protocol.checkError())System.exit(1);
            }catch(IOException failure){
                diagnostics.println("spdctl: HELP_UNAVAILABLE (bundled cli-help.md could not be read)");
                System.exit(1);
            }
            return;
        }
        if(args.length==1&&args[0].equals("--version")){
            protocol.println("CLI.6.0.0 (protocol 6, game 3.3.8)");return;
        }
        Path profile=System.getenv("SPDCTL_PROFILE")==null
                ?Paths.get(System.getProperty("user.home"),"Library","Application Support","Shattered Pixel Dungeon CLI v6")
                :Paths.get(System.getenv("SPDCTL_PROFILE"));
        int exitCode=0;
        boolean profileAccepted=false;
        try{
            boolean machine=false;
            boolean control=args.length>0&&args[0].equals("control");
            if(args.length==0||!(args[0].equals("run")||control))throw new IllegalArgumentException("Expected run or control --machine");
            for(int i=1;i<args.length;i++){
                if(args[i].equals("--machine"))machine=true;
                else if(args[i].equals("--data-dir")&&i+1<args.length)profile=Paths.get(args[++i]);
                else if(control&&args[i].equals("--no-terminal")){}
                else if(control&&args[i].equals("--trace-dir")&&i+1<args.length){
                    if(!Paths.get(args[++i]).isAbsolute())throw new IllegalArgumentException("Absolute trace path required");
                }
                else throw new IllegalArgumentException("Unknown launcher argument");
            }
            if(!machine||!profile.isAbsolute())throw new IllegalArgumentException("Machine mode and absolute profile path required");
            if(control){
                int result=StableController.launch(args,profile,System.in,protocol);
                if(result!=0)System.exit(result);
                return;
            }
            // Remember freshness before this launch creates its lock, audit, or other profile files.
            Runnable initializeDefaults=CliProfileDefaults.prepare(profile);
            // Refuse old or incomplete audit pairs before a profile lock or emergency file can touch them.
            AuditStore.preflight(profile.resolve("audit"));
            Files.createDirectories(profile);profile=profile.toRealPath();
            System.setProperty("Specification-Title","Shattered Pixel Dungeon");
            System.setProperty("Implementation-Title","com.shatteredpixel.shatteredpixeldungeon");
            System.setProperty("Specification-Version","3.3.8");
            System.setProperty("Implementation-Version","896");
            try(ProfileLock lock=new ProfileLock(profile);AuditStore store=new AuditStore(profile.resolve("audit"))){
                profileAccepted=true;
                Files.createDirectories(profile.resolve("audit").resolve("emergency"));
                store.recoverInterrupted();
                store.beginSession(java.util.UUID.randomUUID().toString(),
                        (String)com.shatteredpixel.shatteredpixeldungeon.control.game.BuildCatalog.current().get("build_id"),
                        (String)com.shatteredpixel.shatteredpixeldungeon.control.game.BuildCatalog.current().get("cli_version"),
                        com.shatteredpixel.shatteredpixeldungeon.control.protocol.ControlRequest.PROTOCOL_VERSION);
                AtomicReference<MachineSession> ref=new AtomicReference<>();
                java.util.concurrent.atomic.AtomicBoolean uncaughtRuntimeFailure=new java.util.concurrent.atomic.AtomicBoolean();
                boolean loopReturned=false;
                try {
                importEmergencyReports(profile,store);
                String errorFile=java.lang.management.ManagementFactory.getPlatformMXBean(
                        com.sun.management.HotSpotDiagnosticMXBean.class).getVMOption("ErrorFile").getValue();
                store.recordLog("runtime.environment",com.shatteredpixel.shatteredpixeldungeon.control.protocol.JsonCodec.encode(
                        com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map(
                                "java_version",System.getProperty("java.version"),"os_arch",System.getProperty("os.arch"),
                                "error_file",errorFile,"profile",profile.toString(),
                                "build_id",com.shatteredpixel.shatteredpixeldungeon.control.game.BuildCatalog.current().get("build_id"))));
                GameController game=new GameController(profile,store.menuScope(),error->{if(ref.get()!=null)ref.get().recordException(error);});
                try(MachineSession session=new MachineSession(store,game,protocol)){
                    ref.set(session);
                    Game.observer=game;
                    System.setOut(new PrintStream(new DiagnosticOutput(session,"stdout"),true,StandardCharsets.UTF_8));
                    System.setErr(new PrintStream(new DiagnosticOutput(session,"stderr"),true,StandardCharsets.UTF_8));
                    Thread reader=new Thread(()->session.read(System.in),"SPD Machine Input");reader.setDaemon(true);reader.start();
                    try{DesktopLauncher.launch(new String[0],profile,(thread,error)->{uncaughtRuntimeFailure.set(true);session.recordException(error);game.exitNow();},true,initializeDefaults);loopReturned=true;}
                    catch(Throwable error){session.recordRuntimeFailure(error);game.runtimeFailed(error);throw error;}
                    finally{Game.observer=com.watabou.noosa.RuntimeObserver.NONE;}
                }
                } catch(Throwable failure) {
                    try { store.recordException(failure); } catch(Throwable recordingFailure) { failure.addSuppressed(recordingFailure); }
                    throw failure;
                } finally {
                    MachineSession session=ref.get();
                    boolean failed=!loopReturned||session==null||session.failed()||uncaughtRuntimeFailure.get();
                    store.endSession(failed?"FAILED":"CLOSED",uncaughtRuntimeFailure.get()?"uncaught_runtime_failure":loopReturned?"game_loop_returned":"launcher_failure");
                    if(failed)exitCode=1;
                }
            }
        }catch(Throwable failure){
            if(profileAccepted)try{
                Path emergency=profile.resolve("audit").resolve("emergency");Files.createDirectories(emergency);
                try(PrintWriter writer=new PrintWriter(Files.newBufferedWriter(emergency.resolve("startup-"+System.currentTimeMillis()+".log"),StandardCharsets.UTF_8))){failure.printStackTrace(writer);}
            }catch(Throwable ignored){}
            diagnostics.println(failure instanceof AuditException
                    ? "spdctl: " + ((AuditException)failure).code + " (" + failure.getMessage() + ")"
                    : "spdctl: STARTUP_FAILED (details recorded only after accepting the CLI 6 profile)");
            System.exit(1);
        }
        if(exitCode!=0)System.exit(exitCode);
    }
    private static void importEmergencyReports(Path profile,AuditStore store)throws IOException{
        Path emergency=profile.resolve("audit").resolve("emergency");
        if(!Files.isDirectory(emergency,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(emergency))return;
        try(DirectoryStream<Path> reports=Files.newDirectoryStream(emergency,"*.log")){
            for(Path report:reports){
                if(!Files.isRegularFile(report,LinkOption.NOFOLLOW_LINKS))continue;
                byte[] bytes=Files.readAllBytes(report);
                store.recordLog("recovered_emergency_base64",report.getFileName()+"\n"+java.util.Base64.getEncoder().encodeToString(bytes));
                Files.move(report,report.resolveSibling(report.getFileName()+".imported"));
            }
        }
    }
    private static final class DiagnosticOutput extends OutputStream {
        private final MachineSession session;private final String channel;private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        DiagnosticOutput(MachineSession session,String channel){this.session=session;this.channel=channel;}
        @Override public synchronized void write(int value){bytes.write(value);if(value=='\n')flush();}
        @Override public synchronized void flush(){if(bytes.size()>0){session.recordLog(channel,bytes.toString(StandardCharsets.UTF_8));bytes.reset();}}
    }
}
