package in.itantra.mobile;

import android.app.*;
import android.content.*;
import android.os.*;
import android.speech.*;
import android.speech.tts.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Synthetic local-TTS fixture isolates supplied-audio recognition; never a human accuracy benchmark. */
public final class SpeechDeviceTest extends Instrumentation {
    private final Handler main=new Handler(Looper.getMainLooper());
    private TextToSpeech tts; private SpeechRecognizer recognizer;
    private Bundle args; private ParcelFileDescriptor[] pipe;
    private void report(String value){Bundle b=new Bundle();b.putString("stream",value+"\n");sendStatus(0,b);}
    @Override public void onCreate(Bundle args){this.args=args;start();}
    @Override public void onStart(){Bundle result=new Bundle();File fixture=new File(getTargetContext().getCacheDir(),"synthetic-speech-probe.wav");
        try{
            startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(2000);
            CountDownLatch initialized=new CountDownLatch(1),synthesized=new CountDownLatch(1),recognized=new CountDownLatch(1);
            ByteArrayOutputStream pcm=new ByteArrayOutputStream();int[] format={0,0,0};String[] hypothesis={null};
            main.post(()->tts=new TextToSpeech(getTargetContext(),status->{report("TTS initialization="+status);initialized.countDown();}));
            if(!initialized.await(15,TimeUnit.SECONDS))throw new IOException("TTS initialization timeout");
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
                public void onStart(String id){}public void onDone(String id){synthesized.countDown();}
                public void onError(String id){report("TTS failed");synthesized.countDown();}
                public void onBeginSynthesis(String id,int rate,int encoding,int channels){format[0]=rate;format[1]=encoding;format[2]=channels;}
                public void onAudioAvailable(String id,byte[] bytes){synchronized(pcm){pcm.write(bytes,0,bytes.length);}}
            });
            Voice voice=null;for(Voice v:tts.getVoices())if(ModelManager.localVoice(v)&&v.getLocale().getLanguage().equals("en")){voice=v;break;}
            if(voice==null)throw new IOException("No installed English TTS voice");tts.setVoice(voice);
            tts.synthesizeToFile("Can you hear me?",new Bundle(),fixture,"fixture");
            if(!synthesized.await(20,TimeUnit.SECONDS)||pcm.size()==0)throw new IOException("No synthetic PCM");
            report("Synthetic fixture bytes="+pcm.size()+" rate="+format[0]+" encoding="+format[1]+" channels="+format[2]);
            pipe=ParcelFileDescriptor.createPipe();
            main.post(()->{
                recognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(getTargetContext());
                recognizer.setRecognitionListener(new RecognitionListener(){
                    public void onReadyForSpeech(Bundle b){report("Recognizer ready");}public void onBeginningOfSpeech(){}
                    public void onRmsChanged(float rms){}public void onBufferReceived(byte[] b){}public void onEndOfSpeech(){}
                    public void onError(int code){report("Recognition error="+code);recognized.countDown();}
                    public void onResults(Bundle b){report("Final keys="+b.keySet());var r=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);hypothesis[0]=r==null?null:r.toString();report("Synthetic hypothesis="+hypothesis[0]);recognized.countDown();}
                    public void onSegmentResults(Bundle b){var r=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);hypothesis[0]=r==null?null:r.toString();report("Final segment="+hypothesis[0]);}
                    public void onEndOfSegmentedSession(){report("Segmented session ended");recognized.countDown();}
                    public void onPartialResults(Bundle b){report("Partial="+b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION));}public void onEvent(int type,Bundle b){}
                });
                Intent request=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE,args.getString("locale","en-GB"))
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE,pipe[0])
                    .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION,RecognizerIntent.EXTRA_AUDIO_SOURCE)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,format[0])
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,format[1])
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT,format[2]);
                recognizer.startListening(request);
            });
            byte[] bytes=pcm.toByteArray();Thread.sleep(1000);
            try(OutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])){
                int chunk=Math.max(2,format[0]*format[2]*2/50);
                for(int i=0;i<bytes.length;i+=chunk){out.write(bytes,i,Math.min(chunk,bytes.length-i));Thread.sleep(20);}
            }
            recognized.await(25,TimeUnit.SECONDS);
            result.putString("stream","SYNTHETIC ONLY: "+(hypothesis[0]==null?"no result":hypothesis[0])+"\n");finish(Activity.RESULT_OK,result);
        }catch(Exception e){result.putString("stream","PROBE FAILED: "+e+"\n");finish(Activity.RESULT_CANCELED,result);}
        finally{fixture.delete();main.post(()->{if(recognizer!=null)recognizer.destroy();if(tts!=null)tts.shutdown();});if(pipe!=null)for(var fd:pipe)try{fd.close();}catch(IOException ignored){}}
    }
}
