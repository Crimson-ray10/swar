package in.itantra.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.speech.*;
import android.speech.tts.*;
import org.json.*;
import java.util.*;

/** Uses the strictly on-device recognizer factory; never silently falls back to a network recognizer. */
public final class SpeechEngine {
    public interface Listener {void event(String type,JSONObject data);void transcript(String text,String language);}
    private final Activity activity;private final Listener listener;private final Handler main=new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;private TextToSpeech tts;
    private boolean ttsReady=false,listening=false,conversation=false,playing=false,active=true,closed=false;
    private String language="hi",captureLanguage="hi",englishLocale="en-IN";private long speechStarted=0;private int errors=0;
    private final ArrayDeque<ItpPacket.Decoded> playback=new ArrayDeque<>();
    private Runnable watchdog;
    private String currentUtterance="";
    private final StringBuilder recognizedSegments=new StringBuilder();
    private AcousticTransport acoustic;
    private volatile SpeechPcmPipe pcmPipe;
    private volatile boolean acousticBlocked;
    private final ModelManager models=new ModelManager();
    public void acoustic(AcousticTransport transport){
        if(acoustic!=null)acoustic.speechGate(null);
        acoustic=transport;
        acousticBlocked=transport!=null;
        if(transport!=null)transport.speechGate(new AcousticTransport.SpeechGate(){
            public void blocked(boolean blocked){
                if(acousticBlocked==blocked)return;acousticBlocked=blocked;
                if(blocked){SpeechPcmPipe pipe=pcmPipe;if(pipe!=null)pipe.close();}
                main.post(()->{if(blocked&&listening)cancelCapture();else if(!blocked)restart();});
            }
            public void pcm(short[] pcm,int count){SpeechPcmPipe pipe=pcmPipe;if(pipe!=null&&!acousticBlocked)pipe.accept(pcm,count);}
        });
    }
    private void closePipe(){SpeechPcmPipe pipe=pcmPipe;pcmPipe=null;if(pipe!=null)pipe.close();if(acoustic!=null)acoustic.speechCapturing(false);}
    private void modelState(){models.installedTts(ttsReady?tts.getVoices():null);listener.event("models",models.status(language));if(acoustic!=null)acoustic.capabilities(models.sttMask(),models.ttsMask(),language.equals("hi")?1:language.equals("en")?2:4);}

    public SpeechEngine(Activity activity,Listener listener){
        this.activity=activity;this.listener=listener;
        tts=new TextToSpeech(activity,status->{ttsReady=status==TextToSpeech.SUCCESS;status();});
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
            public void onStart(String id){main.post(()->event("speech","state","Playing received speech"));}
            public void onDone(String id){main.post(()->finishPlayback(id));}
            public void onError(String id){main.post(()->{event("error","message","Offline TTS failed. Received text remains visible.");finishPlayback(id);});}
        });
    }
    private void event(String type,Object... values){listener.event(type,Events.json(values));}
    public void language(String value){if(Set.of("hi","en","hinglish").contains(value)){stopConversation();cancelCapture();language=value;status();}else event("error","message","This ITP version supports Hindi, English and experimental Hinglish only.");}
    private String locale(){
        return switch(language){
            case "en" -> englishLocale;
            case "hinglish" -> "en-IN";
            default -> "hi-IN";
        };
    }
    private Intent intent(){
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale())
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.getPackageName())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L);
        if(Build.VERSION.SDK_INT >= 33){
            String extraAdditional = "android.speech.extra.ADDITIONAL_LANGUAGES";
            if("hinglish".equals(language)){
                intent.putExtra(extraAdditional, new String[]{"hi-IN","en-IN"});
            }else if("hi".equals(language)){
                intent.putExtra(extraAdditional, new String[]{"en-IN"});
            }else{
                intent.putExtra(extraAdditional, new String[]{"hi-IN"});
            }
        }
        return intent;
    }
    private boolean prepare(){
        if(!SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)){event("error","message","This phone has no available on-device recognizer. Voice cannot run offline here. Typed messages still work.");return false;}
        if(recognizer==null){recognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(activity);recognizer.setRecognitionListener(new RecognitionListener(){
            public void onReadyForSpeech(Bundle p){event("speech","state","Listening · on-device");}
            public void onBeginningOfSpeech(){speechStarted=SystemClock.elapsedRealtime();event("speech","state","SPEECH_STARTED");}
            public void onRmsChanged(float rms){}public void onBufferReceived(byte[] b){}
            public void onEndOfSpeech(){event("speech","state","SPEECH_ENDED · recognizing");}
            public void onError(int code){
                boolean wasListening=listening;listening=false;clearWatchdog();closePipe();
                if(!wasListening)return;
                String reason=switch(code){case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED->"Selected language is not supported by this phone's offline recognizer.";case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE->"Offline language model is missing. Use Prepare offline model while internet is available.";case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS->"Microphone permission is required.";case SpeechRecognizer.ERROR_NO_MATCH->"No speech recognized; try speaking again.";case SpeechRecognizer.ERROR_SPEECH_TIMEOUT->"No speech detected.";case SpeechRecognizer.ERROR_RECOGNIZER_BUSY->"Recognizer busy. Wait and try again.";default->"On-device recognition error "+code+". No cloud fallback was used.";};
                event("speech","state","Idle");if(code!=SpeechRecognizer.ERROR_SPEECH_TIMEOUT)event("error","message",reason);
                if(code==SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED||code==SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE||code==SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS||++errors>3)stopConversation();
                playNext();restart();
            }
            public void onResults(Bundle bundle){
                if(!listening)return;listening=false;clearWatchdog();closePipe();errors=0;
                ArrayList<String> results=bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if((results==null||results.isEmpty())&&recognizedSegments.length()>0)results=new ArrayList<>(List.of(recognizedSegments.toString()));
                float[] scores=bundle.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
                String best=null;
                if(results!=null&&!results.isEmpty()){
                    int bestIdx=0;
                    if(scores!=null&&scores.length==results.size()){
                        float maxScore=-1f;
                        for(int i=0;i<scores.length;i++){
                            if(scores[i]>maxScore&&!results.get(i).isBlank()){
                                maxScore=scores[i];bestIdx=i;
                            }
                        }
                    }
                    best=results.get(bestIdx).trim();
                }
                if(best!=null&&!best.isBlank()){
                    event("recognized","text",best,"recognitionMs",speechStarted>0?SystemClock.elapsedRealtime()-speechStarted:JSONObject.NULL);
                    listener.transcript(best,captureLanguage);
                }else event("error","message","No speech recognized; no substitute text was sent.");
                event("speech","state","Idle");playNext();restart();
            }
            public void onSegmentResults(Bundle bundle){
                if(!listening)return;
                ArrayList<String> results=bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if(results!=null&&!results.isEmpty()&&!results.get(0).isBlank()){
                    if(recognizedSegments.length()>0)recognizedSegments.append(' ');
                    recognizedSegments.append(results.get(0).trim());
                    // Only completed segments are retained; partial hypotheses are never transmitted.
                    event("partial","text",recognizedSegments.toString());
                    if(conversation||recognizedSegments.length()>=4000)release();
                }
            }
            public void onEndOfSegmentedSession(){onResults(new Bundle());}
            public void onPartialResults(Bundle b){ArrayList<String> r=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);if(listening&&r!=null&&!r.isEmpty())event("partial","text",r.get(0));}
            public void onEvent(int type,Bundle b){}
        });}
        return true;
    }
    public void status(){
        modelState();
        JSONArray voices=new JSONArray();if(ttsReady&&tts.getVoices()!=null)for(Voice v:tts.getVoices())if(!v.isNetworkConnectionRequired()&&Set.of("hi","en").contains(v.getLocale().getLanguage()))voices.put(v.getLocale().toLanguageTag()+" · "+v.getName());
        event("capabilities","onDeviceStt",SpeechRecognizer.isOnDeviceRecognitionAvailable(activity),"offlineTtsVoices",voices,"language",language,"model","Android on-device recognizer","vad","Platform speech boundaries","hinglish","Experimental; quality not benchmarked","ttsEngine","Android installed offline voice");
        if(Build.VERSION.SDK_INT>=33&&prepare()){
            recognizer.checkRecognitionSupport(intent(),activity.getMainExecutor(),new RecognitionSupportCallback(){
                public void onSupportResult(RecognitionSupport support){java.util.List<String> installed=support.getInstalledOnDeviceLanguages();models.installedStt(installed);modelState();if(installed.contains("en-IN"))englishLocale="en-IN";else for(String tag:installed)if(tag.startsWith("en-")){englishLocale=tag;break;}event("modelSupport","installed",new JSONArray(installed),"pending",new JSONArray(support.getPendingOnDeviceLanguages()),"available",new JSONArray(support.getSupportedOnDeviceLanguages()),"englishLocale",englishLocale);}
                public void onError(int e){event("modelSupport","notice","Language support query unavailable ("+e+"); verify with offline speech test.");}
            });
        }
    }
    public void downloadModel(){if(acoustic!=null&&acoustic.isConnected()){event("error","message","Disconnect acoustic mode before explicitly preparing models online.");return;}if(Build.VERSION.SDK_INT>=33&&prepare()){recognizer.triggerModelDownload(intent());event("notice","message","Requested the selected offline speech model. This preparation step may need internet and a system dialog. Recheck capabilities after download.");}else event("error","message","Offline model download is unavailable on this device.");}
    public void start(){
        if(closed||!active||listening)return;
        if(acoustic!=null&&(acousticBlocked||!acoustic.isConnected())){event("notice","message","Acoustic channel is busy; wait for listening state.");return;}
        if(acoustic!=null&&Build.VERSION.SDK_INT<33){event("error","message","Shared acoustic microphone speech requires Android 13+ and a recognizer supporting injected PCM. Typed acoustic messages remain available.");stopConversation();return;}
        if(playing){event("notice","message","Wait for received speech to finish, or tap Stop playback before talking.");return;}
        if(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},7);event("notice","message","Allow microphone access, then press and hold again.");return;}
        if(!prepare()){stopConversation();return;}
        try{captureLanguage=language;speechStarted=0;recognizedSegments.setLength(0);listening=true;Intent request=intent();
            if(acoustic!=null){
                pcmPipe=new SpeechPcmPipe(()->main.post(()->{cancelCapture();stopConversation();event("error","message","On-device recognizer cannot consume shared microphone PCM; no alternate recognizer was used.");}));
                request.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE,pcmPipe.read)
                    .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION,RecognizerIntent.EXTRA_AUDIO_SOURCE)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT,1)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,16000);
                acoustic.speechCapturing(true);
            }
            recognizer.startListening(request);event("speech","state","Starting microphone");watchdog=()->{if(listening){cancelCapture();event("error","message","Recognition timed out. Try a shorter phrase.");restart();}};main.postDelayed(watchdog,30000);}catch(Exception e){listening=false;closePipe();event("error","message","Cannot start offline recognition: "+e.getMessage());}
    }
    public void release(){if(listening&&recognizer!=null){SpeechPcmPipe released=pcmPipe;if(released!=null){released.releaseTail();main.postDelayed(()->{if(listening&&recognizer!=null&&pcmPipe==released)recognizer.stopListening();},1400);}else recognizer.stopListening();event("speech","state","Microphone released · finalizing");}}
    public void conversation(boolean enabled){conversation=enabled;errors=0;event("conversation","enabled",enabled);if(enabled)start();else release();}
    private void stopConversation(){conversation=false;event("conversation","enabled",false);}
    private void restart(){if(conversation&&active&&!playing&&!listening&&!acousticBlocked)main.postDelayed(()->{if(conversation&&active&&!playing&&!listening&&!acousticBlocked)start();},700);}
    private void clearWatchdog(){if(watchdog!=null)main.removeCallbacks(watchdog);watchdog=null;}
    public void cancelCapture(){listening=false;clearWatchdog();closePipe();if(recognizer!=null)recognizer.cancel();event("speech","state","Idle");}
    public void receive(ItpPacket.Decoded message){if(playback.size()>=20){event("error","message","Playback queue full. Read received text.");return;}playback.add(message);playNext();}
    private void playNext(){
        if(!active||closed||playing||playback.isEmpty())return;
        // Incoming speech preempts ongoing capture to prevent microphone/TTS feedback.
        if(listening){cancelCapture();event("notice","message","Incoming speech paused your microphone. Repeat any unfinished phrase afterward.");}
        ItpPacket.Decoded message=playback.remove();
        if(!ttsReady){event("error","message","TTS engine is not ready. Read received text.");restart();return;}
        boolean hasDevanagari=message.text().codePoints().anyMatch(cp->Character.UnicodeBlock.of(cp)==Character.UnicodeBlock.DEVANAGARI);
        String target;
        if("hinglish".equalsIgnoreCase(message.language())){
            target = hasDevanagari ? "hi" : "en";
        }else if("en".equalsIgnoreCase(message.language())){
            target = "en";
        }else if("hi".equalsIgnoreCase(message.language())){
            target = "hi";
        }else{
            target = hasDevanagari ? "hi" : "en";
        }
        Voice selected=null;
        if(tts.getVoices()!=null){
            for(Voice v:tts.getVoices()){
                if(ModelManager.localVoice(v)&&v.getLocale().getLanguage().equals(target)){
                    if("IN".equalsIgnoreCase(v.getLocale().getCountry())){
                        selected=v;
                        break;
                    }
                    if(selected==null)selected=v;
                }
            }
        }
        if(selected!=null){
            if(tts.setVoice(selected)==TextToSpeech.ERROR){event("error","message","Installed offline TTS voice could not be loaded.");restart();return;}
        }else{
            event("error","message","Offline TTS voice is missing for "+target+". Read the received text; no network voice was selected.");restart();return;
        }
        playing=true;if(acoustic!=null)acoustic.playback(true);currentUtterance=UUID.randomUUID().toString();
        if(tts.speak(message.text(),TextToSpeech.QUEUE_FLUSH,null,currentUtterance)==TextToSpeech.ERROR){finishPlayback(currentUtterance);event("error","message","TTS playback failed; received text is displayed.");}
    }
    private void finishPlayback(String id){if(!id.equals(currentUtterance))return;playing=false;if(acoustic!=null)acoustic.playback(false);currentUtterance="";event("speech","state","Idle");main.postDelayed(()->{playNext();restart();},700);}
    public void stopPlayback(){currentUtterance="";if(tts!=null)tts.stop();playing=false;if(acoustic!=null)acoustic.playback(false);playback.clear();event("speech","state","Idle");restart();}
    public void pause(){active=false;stopConversation();cancelCapture();stopPlayback();}
    public void resume(){active=true;}
    public void close(){closed=true;pause();main.removeCallbacksAndMessages(null);if(recognizer!=null)recognizer.destroy();if(tts!=null)tts.shutdown();}
}
