package com.shatteredpixel.shatteredpixeldungeon.control.desktop;

import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static com.shatteredpixel.shatteredpixeldungeon.control.protocol.Values.map;
import static org.junit.Assert.*;

public class ControllerDiagnosticsTest {
    @Test public void forwardsSplitBytesExactlyAndClosesOnlyItsOwnedInput()throws Exception{
        byte[] text="warning: 雪🙂\n".getBytes(StandardCharsets.UTF_8);
        byte[] bytes=Arrays.copyOf(text,text.length+2);bytes[text.length]=(byte)0xff;bytes[text.length+1]=(byte)0x80;
        TrackingInput source=new TrackingInput(bytes,2);TrackingOutput sink=new TrackingOutput();
        PrintStream output=new PrintStream(sink,true,StandardCharsets.UTF_8);
        StableController.DiagnosticForwarder forwarder=new StableController.DiagnosticForwarder(source,output);
        assertTrue(forwarder.await(5000));assertArrayEquals(bytes,sink.toByteArray());
        assertTrue(source.closed);assertFalse(sink.closed);assertFalse(forwarder.consumerFailed());assertNull(forwarder.readFailure());
        output.write('!');assertEquals('!',sink.toByteArray()[bytes.length]);output.close();
    }

    @Test public void brokenDisplayStillDrainsAllChildDiagnosticsWithBoundedReads()throws Exception{
        byte[] bytes=new byte[200_000];Arrays.fill(bytes,(byte)'x');TrackingInput source=new TrackingInput(bytes,Integer.MAX_VALUE);
        PrintStream broken=new PrintStream(new OutputStream(){@Override public void write(int value)throws IOException{throw new IOException("PRIVATE_OUTPUT_DETAIL");}});
        StableController.DiagnosticForwarder forwarder=new StableController.DiagnosticForwarder(source,broken);
        assertTrue(forwarder.await(5000));assertTrue(forwarder.consumerFailed());assertNull(forwarder.readFailure());
        assertEquals(bytes.length,source.consumed);assertTrue(source.maximumRead<=8192);assertTrue(source.closed);broken.close();
    }

    @Test public void waitingForUnclosedDiagnosticsIsBoundedAndCanFinishLater()throws Exception{
        PipedInputStream source=new PipedInputStream();PipedOutputStream producer=new PipedOutputStream(source);
        TrackingOutput sink=new TrackingOutput();PrintStream destination=new PrintStream(sink);
        StableController.DiagnosticForwarder forwarder=new StableController.DiagnosticForwarder(source,destination);
        long start=System.nanoTime();assertFalse(forwarder.await(20));
        assertTrue("Waiting must be bounded",(System.nanoTime()-start)/1_000_000<1000);
        producer.write("tail\n".getBytes(StandardCharsets.UTF_8));producer.close();
        assertTrue(forwarder.await(5000));assertEquals("tail\n",sink.toString(StandardCharsets.UTF_8));assertFalse(sink.closed);destination.close();
    }

    @Test public void diagnosticReadErrorDoesNotExposeExceptionText()throws Exception{
        IOException failure=new IOException("PRIVATE_PROFILE_AND_STACK_DETAIL");
        TrackingOutput sink=new TrackingOutput();PrintStream destination=new PrintStream(sink);
        StableController.DiagnosticForwarder forwarder=new StableController.DiagnosticForwarder(new InputStream(){@Override public int read()throws IOException{throw failure;}},destination);
        assertTrue(forwarder.await(5000));assertSame(failure,forwarder.readFailure());
        assertEquals("spdctl: CONTROLLER_DIAGNOSTIC_READ_FAILED\n",sink.toString(StandardCharsets.UTF_8));destination.close();
    }

    @Test public void controllerOutputFailureIsSpecificAndDoesNotPrintPrivateCauseMessages()throws Exception{
        PrintStream broken=new PrintStream(new OutputStream(){@Override public void write(int value)throws IOException{throw new IOException("PRIVATE_OUTPUT_DETAIL");}});
        StableController.LaunchFailure failure=assertThrows(StableController.LaunchFailure.class,
                ()->StableController.writeFrame(broken,map("v",7,"id","t1.1","st","completed")));
        assertEquals("CONTROLLER_OUTPUT_FAILED",failure.code);
        assertTrue(StableController.launchDiagnostic(failure).startsWith("spdctl: CONTROLLER_OUTPUT_FAILED"));
        String launch=StableController.launchDiagnostic(new StableController.LaunchFailure("CONTROLLER_CHILD_START_FAILED",new IOException("PRIVATE_PATH")));
        assertEquals("spdctl: CONTROLLER_CHILD_START_FAILED (IOException)",launch);
        assertFalse(launch.contains("PRIVATE_PATH"));broken.close();
    }

    private static final class TrackingInput extends ByteArrayInputStream {
        final int fragment;boolean closed;int consumed,maximumRead;
        TrackingInput(byte[] bytes,int fragment){super(bytes);this.fragment=fragment;}
        @Override public synchronized int read(byte[] target,int offset,int length){
            maximumRead=Math.max(maximumRead,length);int n=super.read(target,offset,Math.min(length,fragment));if(n>0)consumed+=n;return n;
        }
        @Override public void close()throws IOException{closed=true;super.close();}
    }
    private static final class TrackingOutput extends ByteArrayOutputStream {
        boolean closed;
        @Override public void close()throws IOException{closed=true;super.close();}
    }
}
