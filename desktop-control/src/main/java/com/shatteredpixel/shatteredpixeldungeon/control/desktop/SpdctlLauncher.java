package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import com.shatteredpixel.shatteredpixeldungeon.control.desktop.store.AuditStore;
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
        if(args.length==1&&(args[0].equals("--help")||args[0].equals("--version"))){
            protocol.println(args[0].equals("--version")?"CLI.0.8.6 (protocol 1, game 3.3.8)":"spdctl run --machine [--data-dir ABSOLUTE_PROFILE_DIRECTORY]");return;
        }
        Path profile=System.getenv("SPDCTL_PROFILE")==null
                ?Paths.get(System.getProperty("user.home"),"Library","Application Support","Shattered Pixel Dungeon CLI")
                :Paths.get(System.getenv("SPDCTL_PROFILE"));
        int exitCode=0;
        try{
            boolean machine=false;
            if(args.length==0||!args[0].equals("run"))throw new IllegalArgumentException("Expected run --machine");
            for(int i=1;i<args.length;i++){
                if(args[i].equals("--machine"))machine=true;
                else if(args[i].equals("--data-dir")&&i+1<args.length)profile=Paths.get(args[++i]);
                else throw new IllegalArgumentException("Unknown launcher argument");
            }
            if(!machine||!profile.isAbsolute())throw new IllegalArgumentException("Machine mode and absolute profile path required");
            Files.createDirectories(profile);profile=profile.toRealPath();
            System.setProperty("Specification-Title","Shattered Pixel Dungeon");
            System.setProperty("Implementation-Title","com.shatteredpixel.shatteredpixeldungeon");
            System.setProperty("Specification-Version","3.3.8");
            System.setProperty("Implementation-Version","896");
            try(ProfileLock lock=new ProfileLock(profile);AuditStore store=new AuditStore(profile.resolve("audit"))){
                store.recoverInterrupted();
                store.beginSession(java.util.UUID.randomUUID().toString());
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
                    try{DesktopLauncher.launch(new String[0],profile,(thread,error)->{uncaughtRuntimeFailure.set(true);session.recordException(error);game.exitNow();},true);loopReturned=true;}
                    catch(Throwable error){session.recordException(error);throw error;}
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
            try{
                Path emergency=profile.resolve("audit").resolve("emergency");Files.createDirectories(emergency);
                try(PrintWriter writer=new PrintWriter(Files.newBufferedWriter(emergency.resolve("startup-"+System.currentTimeMillis()+".log"),StandardCharsets.UTF_8))){failure.printStackTrace(writer);}
            }catch(Throwable ignored){}
            diagnostics.println("spdctl: STARTUP_FAILED (details recorded in the profile when possible)");
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
