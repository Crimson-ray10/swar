package in.itantra.mobile;

import android.os.ParcelFileDescriptor;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** 1200 ms look-behind covers acquisition at the conservative 100-symbol/s profile. */
final class SpeechPcmPipe implements AutoCloseable {
    final ParcelFileDescriptor read;
    private final OutputStream output;
    private final ArrayBlockingQueue<byte[]> queue=new ArrayBlockingQueue<>(128);
    private final ArrayDeque<byte[]> delay=new ArrayDeque<>();
    private volatile boolean closed,inputFinished;
    private final Thread writer;
    SpeechPcmPipe(Runnable failed)throws IOException {
        var pipe=ParcelFileDescriptor.createPipe();read=pipe[0];output=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
        writer=new Thread(()->{try{while(!closed){byte[] bytes=queue.poll(200,TimeUnit.MILLISECONDS);if(bytes!=null){output.write(bytes);Arrays.fill(bytes,(byte)0);}else if(inputFinished){output.close();break;}}}catch(Exception e){if(!closed)failed.run();}},"itantra-asr-pipe");writer.start();
    }
    synchronized void accept(short[] pcm,int count){
        if(closed||inputFinished)return;byte[] bytes=new byte[count*2];for(int i=0;i<count;i++){bytes[2*i]=(byte)pcm[i];bytes[2*i+1]=(byte)(pcm[i]>>>8);}delay.add(bytes);
        if(delay.size()>60&&!queue.offer(delay.remove()))close();
    }
    synchronized void releaseTail(){while(!delay.isEmpty()){byte[] bytes=delay.remove();if(!queue.offer(bytes))Arrays.fill(bytes,(byte)0);}inputFinished=true;}
    @Override public synchronized void close(){if(closed)return;closed=true;try{output.close();}catch(IOException ignored){}try{read.close();}catch(IOException ignored){}writer.interrupt();for(byte[] bytes:delay)Arrays.fill(bytes,(byte)0);delay.clear();for(byte[] bytes:queue)Arrays.fill(bytes,(byte)0);queue.clear();}
}
