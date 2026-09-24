package in.itantra.mobile;

import android.content.Context;
import android.media.*;
import android.media.audiofx.*;
import android.os.SystemClock;
import java.io.IOException;
import java.util.Arrays;

/** One shared blocking microphone reader; cancellable, bounded AudioTrack writes. */
final class AcousticAudio implements AutoCloseable {
    interface Listener { void pcm(short[] pcm,int length); void failure(String message); }
    private final AcousticConfig config;private final Listener listener;private final AudioManager manager;
    private volatile boolean running,transmitting,playback;
    private volatile long quietUntil;
    private volatile AudioRecord recorder;private volatile AudioTrack track;
    private Thread reader;
    volatile double inputRms;volatile long capturedFrames;
    AcousticAudio(Context context,AcousticConfig config,Listener listener){this.config=config;this.listener=listener;manager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);}
    synchronized void start() throws IOException {
        if(running)return;
        int min=AudioRecord.getMinBufferSize(config.sampleRate(),AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(min<=0)throw new IOException("Microphone does not support the selected sample rate");
        int source="true".equals(manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED))?MediaRecorder.AudioSource.UNPROCESSED:MediaRecorder.AudioSource.VOICE_RECOGNITION;
        AudioRecord next=new AudioRecord(source,config.sampleRate(),AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min*2,config.sampleRate()/2));
        if(next.getState()!=AudioRecord.STATE_INITIALIZED){next.release();throw new IOException("Cannot initialize microphone");}
        AudioDeviceInfo microphone=builtIn(AudioManager.GET_DEVICES_INPUTS,AudioDeviceInfo.TYPE_BUILTIN_MIC);
        if(microphone==null||!next.setPreferredDevice(microphone)){next.release();throw new IOException("Built-in microphone routing is unavailable");}
        // Do not apply speech noise suppression/AGC to modem carriers.
        disableEffects(next.getAudioSessionId());
        try{next.startRecording();}catch(RuntimeException e){next.release();throw e;}
        recorder=next;running=true;
        reader=new Thread(()->{
            short[] pcm=new short[config.sampleRate()/50];
            try{while(running&&recorder==next){int n=next.read(pcm,0,pcm.length,AudioRecord.READ_BLOCKING);if(n<0)throw new IOException("AudioRecord error "+n);if(n>0){AudioDeviceInfo route=next.getRoutedDevice();if(route==null||route.getType()!=AudioDeviceInfo.TYPE_BUILTIN_MIC)throw new IOException("Capture is not routed to the built-in microphone");double sum=0;for(int i=0;i<n;i++)sum+=(double)pcm[i]*pcm[i];inputRms=Math.sqrt(sum/n)/32768;capturedFrames+=n;if(muted())Arrays.fill(pcm,0,n,(short)0);listener.pcm(pcm,n);}}}
            catch(Exception e){if(running)listener.failure("Acoustic capture stopped: "+e.getMessage());}
            finally{Arrays.fill(pcm,(short)0);}
        },"itantra-acoustic-pcm");reader.start();
    }
    private AudioDeviceInfo builtIn(int flags,int type){for(AudioDeviceInfo device:manager.getDevices(flags))if(device.getType()==type)return device;return null;}
    private void disableEffects(int id){
        // Effects are session scoped and immediately released; source selection is the primary control.
        try{if(AutomaticGainControl.isAvailable()){var e=AutomaticGainControl.create(id);if(e!=null){e.setEnabled(false);e.release();}}}catch(RuntimeException ignored){}
        try{if(NoiseSuppressor.isAvailable()){var e=NoiseSuppressor.create(id);if(e!=null){e.setEnabled(false);e.release();}}}catch(RuntimeException ignored){}
    }
    boolean muted(){return transmitting||playback||SystemClock.elapsedRealtime()<quietUntil;}
    void playback(boolean active){playback=active;quietUntil=SystemClock.elapsedRealtime()+500;}
    void transmit(short[] pcm) throws IOException,InterruptedException {
        if(!running)throw new IOException("Microphone receiver is stopped");
        transmitting=true;
        AudioTrack output=null;
        try{
            int min=AudioTrack.getMinBufferSize(config.sampleRate(),AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(min<=0)throw new IOException("Speaker does not support sample rate");
            output=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(config.sampleRate()).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(min,config.sampleRate()/5*2)).build();
            AudioDeviceInfo speaker=builtIn(AudioManager.GET_DEVICES_OUTPUTS,AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
            if(speaker==null||!output.setPreferredDevice(speaker))throw new IOException("Built-in speaker routing is unavailable");
            track=output;output.play();int offset=0;
            long deadline=SystemClock.elapsedRealtime()+pcm.length*1000L/config.sampleRate()+5000;
            while(offset<pcm.length){if(!running||track!=output)throw new IOException("Transmission cancelled");AudioDeviceInfo route=output.getRoutedDevice();if(route!=null&&route.getType()!=AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)throw new IOException("Audio is not routed to the built-in speaker");int written=output.write(pcm,offset,Math.min(320,pcm.length-offset),AudioTrack.WRITE_NON_BLOCKING);if(written<0)throw new IOException("AudioTrack write "+written);offset+=written;if(written==0)Thread.sleep(5);if(SystemClock.elapsedRealtime()>deadline)throw new IOException("Speaker timeout");}
            while(Integer.toUnsignedLong(output.getPlaybackHeadPosition())<pcm.length){if(!running||track!=output)throw new IOException("Transmission cancelled");if(SystemClock.elapsedRealtime()>deadline)throw new IOException("Speaker drain timeout");Thread.sleep(10);}
        }finally{if(output!=null){try{output.pause();output.flush();}catch(RuntimeException ignored){}output.release();}track=null;Arrays.fill(pcm,(short)0);transmitting=false;quietUntil=SystemClock.elapsedRealtime()+config.silenceGapMs();}
    }
    void stopTransmission(){AudioTrack old=track;track=null;if(old!=null)try{old.pause();old.flush();}catch(RuntimeException ignored){}}
    @Override public synchronized void close(){running=false;stopTransmission();AudioRecord old=recorder;recorder=null;if(old!=null){try{old.stop();}catch(RuntimeException ignored){}if(reader!=null)try{reader.join(1500);}catch(InterruptedException e){Thread.currentThread().interrupt();}old.release();}reader=null;}
}
