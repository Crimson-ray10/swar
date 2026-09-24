package in.itantra.mobile;
import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.util.concurrent.*;

/** Physical microphone/speaker instrumentation. USB carries diagnostics, not application packets. */
public final class AcousticDeviceTest extends Instrumentation {
    private Bundle args;private final BlockingQueue<String> events=new ArrayBlockingQueue<>(512);
    @Override public void onCreate(Bundle args){this.args=args==null?new Bundle():args;start();}
    private void report(String text){Bundle status=new Bundle();status.putString("stream",text+"\n");sendStatus(0,status);}
    @Override public void onStart(){Bundle result=new Bundle();boolean received=false,acked=false,sent=false,recognized=false,voiceStarted=false,passed=false;
        try{
            MainActivity.testObserver=(type,data)->{if(!type.equals("partial"))events.offer(type+" "+data);};
            MainActivity activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Thread.sleep(2000);String role=args.getString("role","receiver");
            activity.new Bridge().command("language","{\"language\":\"en\"}");
            activity.new Bridge().command("discover","{}");
            long deadline=SystemClock.elapsedRealtime()+Long.parseLong(args.getString("timeoutMs","240000"));
            while(SystemClock.elapsedRealtime()<deadline){
                String event=events.poll(1,TimeUnit.SECONDS);if(event==null)continue;report(event);
                if(event.startsWith("modemDiagnostic ")){
                    try{
                        JSONObject obj=new JSONObject(event.substring(16));
                        String dir=obj.optString("direction","").toUpperCase();
                        byte[] wire=android.util.Base64.decode(obj.optString("wire"),android.util.Base64.DEFAULT);
                        StringBuilder hex=new StringBuilder();
                        for(int i=0;i<Math.min(wire.length,32);i++)hex.append(String.format("%02X",wire[i]));
                        if(wire.length>32)hex.append("...");
                        try{
                            var dec=AcousticFrame.decode(wire,AcousticConfig.robust());
                            report("[DIAG "+dir+"] Type="+dec.frame().type()+" Seq="+dec.frame().sequence()+" Corrections="+dec.corrections()+" WireLen="+wire.length+" Hex="+hex);
                        }catch(Exception ex){
                            report("[DIAG "+dir+" FAIL] Err="+ex.getMessage()+" WireLen="+wire.length+" Hex="+hex);
                        }
                    }catch(Exception ignored){}
                }
                if(event.startsWith("received "))received=true;
                if(event.startsWith("recognized "))recognized=true;
                if(event.startsWith("delivery ")&&event.contains("Decoded by peer"))acked=true;
                if(role.equals("voice_sender")&&!voiceStarted&&event.startsWith("connection ")&&event.contains("\"CONNECTED\"")){
                    voiceStarted=true;activity.new Bridge().command("debugTransceiver","{}");report("VOICE: connected; microphone starts in 20 seconds.");Thread.sleep(20000);activity.new Bridge().command("start","{}");report("VOICE: capture requested; speak the reference phrase when Listening is reported.");
                    new Handler(Looper.getMainLooper()).postDelayed(()->activity.new Bridge().command("release","{}"),15000);
                }
                if(!sent&&((role.equals("sender")&&event.startsWith("connection ")&&event.contains("\"CONNECTED\""))||(role.equals("receiver")&&received))){
                    sent=true;
                    // Let receiver ACK drain and local TTS finish before sending the reply.
                    if(!role.equals("sender"))Thread.sleep(5000);
                    activity.new Bridge().command("send",Events.json("text",args.getString("text",role.equals("sender")?"A: help":"B: heard"),"language","en").toString());
                }
                if(received&&acked){passed=true;report("PASS: acoustic bidirectional ITP delivery and decode ACK");break;}
                if((role.equals("voice_sender")&&recognized&&acked)||(role.equals("voice_receiver")&&received)){passed=true;report("PASS: voice test endpoint "+role);Thread.sleep(5000);break;}
            }
            activity.new Bridge().command("capabilities","{}");Thread.sleep(1000);
            result.putString("stream",passed?"PASS: physical acoustic test endpoint\n":"INCOMPLETE: acoustic exchange not confirmed\n");
            finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
        }catch(Exception e){result.putString("stream","FAIL: "+e+"\n");finish(Activity.RESULT_CANCELED,result);}
        finally{MainActivity.testObserver=null;}
    }
}
