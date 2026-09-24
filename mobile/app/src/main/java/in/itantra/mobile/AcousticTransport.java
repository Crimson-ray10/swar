package in.itantra.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.*;

/** Default phone-to-phone transport. The only physical I/O is AcousticAudio. */
public final class AcousticTransport implements Transport {
    public interface SpeechGate { void blocked(boolean blocked); void pcm(short[] pcm,int count); }
    private final Activity activity;private final Listener listener;
    private final AcousticConfig config;private final AcousticAudio audio;private final AcousticModem modem;private final AcousticLink link;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private final ArrayDeque<AcousticFrame> outgoing=new ArrayDeque<>();
    private final ArrayDeque<ItpPacket.Decoded> received=new ArrayDeque<>();
    private volatile SpeechGate speechGate;private volatile boolean active,closed,ttsPlaying,speechCapturing;
    private volatile String state="DISCONNECTED";private volatile JSONObject metrics=Events.json("transport","ACOUSTIC");
    private volatile long receiveUntil;private volatile boolean signal;
    private int configuredBps=2000;private long nextTx,metricsAt;private final Random jitter=new java.security.SecureRandom();
    private volatile long generation;
    public AcousticTransport(Activity activity,Listener listener){this(activity,listener,AcousticConfig.robust());}
    public AcousticTransport(Activity activity,Listener listener,AcousticConfig config){
        this.activity=activity;this.listener=listener;this.config=config;
        var preferences=activity.getSharedPreferences("acoustic",0);String id=preferences.getString("deviceId",null);
        if(id==null){id=UUID.randomUUID().toString();preferences.edit().putString("deviceId",id).apply();}
        link=new AcousticLink(config,UUID.fromString(id),new AcousticLink.Listener(){
            public void transmit(AcousticFrame frame){if(outgoing.size()<16)outgoing.add(frame);else fail("Acoustic control queue full");}
            public void event(String type,String value,long sequence){
                if(type.equals("connection")){state=value;listener.event(type,Events.json("state",value,"peer",linkPeer(),"transport","ACOUSTIC"));}
                else if(type.equals("delivery"))listener.event(type,Events.json("state",value,"sequence",sequence));
                else if(type.equals("negotiated"))listener.event(type,Events.json("language",value.equals("1")?"hi":value.equals("2")?"en":"hinglish","voiceMask",link.mutuallySupportedVoiceMask()));
                else acoustic(value);
            }
            public void received(ItpPacket.Decoded message){if(received.size()<20)received.add(message);}
        },jitter);
        modem=new AcousticModem(config,new AcousticModem.Listener(){
            public void event(String name){if(name.equals("SIGNAL_DETECTED")){signal=true;receiveUntil=now()+config.packetTimeoutMs();gate(true);}if(name.equals("TIMEOUT")||name.equals("PACKET_INVALID")){signal=false;receiveUntil=now()+config.silenceGapMs();}acoustic(name);}
            public void packet(byte[] wire){byte[] owned=wire.clone();long cycle=generation;signal=false;receiveUntil=now()+config.silenceGapMs()*2L+200;worker.execute(()->{try{if(active&&cycle==generation){diagnostic("rx",owned);link.receive(owned,now());}}finally{Arrays.fill(owned,(byte)0);}});}
        });
        audio=new AcousticAudio(activity,config,new AcousticAudio.Listener(){
            public void pcm(short[] pcm,int length){synchronized(modem){modem.accept(pcm,length);}SpeechGate gate=speechGate;if(gate!=null&&!audioMuted()&&!signal&&now()>=receiveUntil)gate.pcm(pcm,length);}
            public void failure(String message){worker.execute(()->{fail(message);disconnect();});}
        });
        worker.scheduleWithFixedDelay(this::tick,50,50,TimeUnit.MILLISECONDS);
    }
    private boolean audioMuted(){return audio.muted();}
    private String linkPeer(){return link==null||link.peer()==null?"Nearby acoustic phone":"itn-"+link.peer().toString().substring(0,8);}
    private static long now(){return SystemClock.elapsedRealtime();}
    public void speechGate(SpeechGate gate){speechGate=gate;}
    public void capabilities(int stt,int tts,int preferred){if(!closed)worker.execute(()->link.capabilities(stt,tts,preferred));}
    public void speechCapturing(boolean value){speechCapturing=value;}
    public void playback(boolean value){ttsPlaying=value;audio.playback(value);gate(value);}
    private void gate(boolean value){SpeechGate gate=speechGate;if(gate!=null)gate.blocked(value);}
    private void acoustic(String name){android.util.Log.d("iTantra","[ACOUSTIC] "+name);listener.event("acoustic",Events.json("state",name,"transport","ACOUSTIC"));}
    private void fail(String message){listener.event("error",Events.json("message",message,"transport","ACOUSTIC"));}
    private void diagnostic(String direction,byte[] wire){if(MainActivity.testObserver!=null)listener.event("modemDiagnostic",Events.json("direction",direction,"wire",android.util.Base64.encodeToString(wire,android.util.Base64.NO_WRAP)));}
    @Override public void discover(){
        if(closed||active)return;
        if(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},7);fail("Allow microphone access, then scan again. Acoustic discovery uses sound.");return;}
        worker.execute(()->{try{audio.start();active=true;link.start(now());listener.event("discovery",Events.json("state","Listening for acoustic peers · keep both apps open","transport","ACOUSTIC"));}catch(Exception e){fail(e.getMessage());}});
    }
    private void tick(){
        if(closed||!active)return;
        try{
            boolean receiving=signal&&now()<receiveUntil;
            if(signal&&!receiving){signal=false;acoustic("TIMEOUT");}
            link.tick(now(),receiving||ttsPlaying||speechCapturing||!outgoing.isEmpty());
            if(!receiving&&!ttsPlaying&&now()>=receiveUntil&&now()>=nextTx&&!outgoing.isEmpty()){
                AcousticFrame frame=outgoing.remove();long cycle=generation;
                gate(true);acoustic("TRANSMITTING");
                synchronized(modem){modem.reset();}
                byte[] encoded=frame.encode(config);diagnostic("tx",encoded);audio.transmit(modem.modulate(encoded));Arrays.fill(encoded,(byte)0);
                if(cycle!=generation)return;
                link.transmitted(frame,now());nextTx=now()+config.silenceGapMs()+jitter.nextInt(350);
                acoustic(link.busy()?"WAITING_FOR_ACK":"LISTENING");
            }
            // Deliver TTS only AFTER queued ACK samples have left the speaker.
            if(outgoing.isEmpty()&&!received.isEmpty()&&!receiving){listener.received(received.remove());}
            if(!receiving&&!ttsPlaying&&outgoing.isEmpty()&&!link.busy()&&!audio.muted()&&now()>=receiveUntil)gate(false);
            if(now()-metricsAt>1000){metricsAt=now();publishMetrics();}
        }catch(Exception e){if(active){fail("Acoustic I/O: "+e.getMessage());disconnect();}}
    }
    private void publishMetrics(){
        double seconds=link.elapsedMs(now())/1000.0;
        metrics=Events.json("transport","ACOUSTIC","sent",link.messagesSent,"received",link.messagesReceived,"txBytes",link.wireBytesSent,"rxBytes",link.wireBytesReceived,
            "appTxBps",link.wireBytesSent*8/seconds,"payloadBps",link.payloadBytesAcked*8/seconds,"effectiveBps",link.payloadBytesAcked*8/seconds,
            "rawBps",config.rawBitrate(),"configuredBps",configuredBps,"crcFailures",link.crcFailures,"fecFailures",link.fecFailures,"fecCorrected",link.fecCorrections,
            "packetsSent",link.packetsSent,"packetsReceived",link.packetsReceived,"packetsLost",link.packetsLost,"retransmissions",link.retransmissions,"duplicates",link.duplicates,
            "rttMs",link.lastRtt,"averageRttMs",link.rttCount==0?JSONObject.NULL:link.rttTotal/link.rttCount,"packetLatencyMs",link.lastMessageLatency,
            "payloadBytes",link.payloadBytesAcked,"sourceBytes",link.sourceBytesSent,"overheadBytes",link.wireBytesSent-link.payloadBytesAcked,
            "fecScheme",config.fecScheme(),"outerFecExpansion",2,"sampleRate",config.sampleRate(),"modulation","BFSK","snrDb",JSONObject.NULL,"distanceM",JSONObject.NULL,
            "packetFailureRate",link.packetsReceived+link.crcFailures+link.fecFailures==0?JSONObject.NULL:(link.crcFailures+link.fecFailures)/(double)(link.packetsReceived+link.crcFailures+link.fecFailures),
            "retransmissionRate",link.packetsSent==0?JSONObject.NULL:link.retransmissions/(double)link.packetsSent,
            "signalConfidence",modem.signalConfidence(),"inputRms",audio.inputRms,"capturedFrames",audio.capturedFrames,"bestPreamble",modem.bestPreamble(),"sttLatencyMs",JSONObject.NULL,"ttsLatencyMs",JSONObject.NULL,"endToEndLatencyMs",JSONObject.NULL);
        listener.event("metrics",metrics);
    }
    @Override public void host(){discover();}
    @Override public void connect(String address,int port,String pin,String callsign){discover();}
    @Override public void respondRequest(boolean accept){if(!accept)disconnect();else discover();}
    @Override public void cancelRequest(){disconnect();}
    @Override public void callsign(String name){}
    @Override public void sendText(String text,String language){if(text==null||text.isBlank())return;worker.execute(()->{try{link.sendText(text.trim(),language,now());listener.event("sent",Events.json("text",text.trim(),"transport","ACOUSTIC"));gate(true);}catch(Exception e){fail(e.getMessage());}});}
    @Override public void bitrate(int bps){if(Set.of(500,1000,2000,4000,8000,16000).contains(bps))configuredBps=bps;listener.event("notice",Events.json("message","Requested payload budget "+configuredBps+" bps; modem profile remains "+config.rawBitrate()+" raw bps. Goodput is measured separately."));}
    @Override public boolean isConnected(){return state.equals("CONNECTED")||state.equals("DEGRADED");}
    @Override public String getState(){return state;}
    @Override public JSONObject getMetrics(){return metrics;}
    @Override public void disconnect(){active=false;generation++;audio.close();gate(true);worker.execute(()->{outgoing.clear();received.clear();link.stop();synchronized(modem){modem.reset();}});}
    @Override public void close(){disconnect();closed=true;worker.shutdown();}
}
