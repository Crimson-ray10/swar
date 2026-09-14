package in.itantra.mobile;
import android.app.*;
import android.os.*;
import android.content.*;
import org.json.*;
import java.util.concurrent.*;

/** ADB-only instrumentation for real handset launch/capability/TCP checks. Never enabled in release. */
public final class DeviceSmokeTest extends Instrumentation {
    private Bundle arguments;private final BlockingQueue<String> events=new LinkedBlockingQueue<>();
    @Override public void onCreate(Bundle args){arguments=args==null?new Bundle():args;start();}
    private void report(String text){Bundle status=new Bundle();status.putString("stream",text+"\n");sendStatus(0,status);}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            MainActivity.testObserver=(type,data)->{if(!type.equals("metrics")&&!type.equals("partial")&&!type.equals("capabilities"))events.offer(type+" "+data);else if(type.equals("capabilities"))events.offer("capabilities "+LocalTransport.json("onDeviceStt",data.optBoolean("onDeviceStt"),"offlineTtsVoiceCount",data.optJSONArray("offlineTtsVoices").length()));};
            Intent intent=new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            MainActivity activity=(MainActivity)startActivitySync(intent);
            Thread.sleep(1500);
            String role=arguments.getString("role","capabilities");
            String lang=arguments.getString("language","en");
            activity.new Bridge().command("language",LocalTransport.json("language",lang).toString());
            if(role.equals("host"))activity.new Bridge().command("host","{}");
            if(role.equals("prepare"))activity.new Bridge().command("downloadModel","{}");
            if(role.equals("client"))activity.new Bridge().command("connect",LocalTransport.json("address",arguments.getString("peer",""),"pin",arguments.getString("pin",""),"port",8988).toString());
            long deadline=SystemClock.elapsedRealtime()+(role.equals("capabilities")?8000:120000);
            boolean sent=false,received=false,acked=false,reported=false;
            while(SystemClock.elapsedRealtime()<deadline){
                String event=events.poll(1,TimeUnit.SECONDS);if(event==null){if(role.equals("prepare"))activity.new Bridge().command("capabilities","{}");continue;}report(event);
                if(role.equals("prepare")&&event.startsWith("modelSupport ")&&new JSONObject(event.substring(13)).getJSONArray("installed").toString().contains("hi-IN")){report("PASS: Hindi STT model installed");break;}
                if(!sent&&event.startsWith("connection ")&&event.contains("\"CONNECTED\"")){
                    sent=true;activity.new Bridge().command("send",LocalTransport.json("text","iTantra TEST from "+role+": मुख्य द्वार पर मदद चाहिए. Send help.","language",lang).toString());
                }
                if(event.startsWith("received "))received=true;
                if(event.startsWith("delivery ")&&event.contains("Decoded by peer"))acked=true;
                if(received&&acked&&!reported){reported=true;report("PASS: physical phone received peer text and outgoing text was decode-acknowledged.");if(!arguments.getString("interactive","false").equals("true")){Thread.sleep(8000);String remaining;while((remaining=events.poll())!=null)report(remaining);break;}else report("Ready for user microphone test; hold the app PTT button.");}
            }
            result.putString("stream",role.equals("capabilities")?"Capability inspection complete\n":received&&acked?"PASS: bidirectional physical TCP/ITP text exchange\n":"INCOMPLETE: bidirectional delivery not confirmed\n");
            finish(role.equals("capabilities")||received&&acked?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
        }catch(Exception e){result.putString("stream","FAIL: "+e+"\n");finish(Activity.RESULT_CANCELED,result);}
        finally{MainActivity.testObserver=null;}
    }
}
