package in.itantra.mobile;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.*;
import android.view.WindowManager;
import android.content.*;
import org.json.*;
import java.io.*;

public final class MainActivity extends Activity {
    static volatile java.util.function.BiConsumer<String,JSONObject> testObserver;
    private WebView web;private Transport transport;private SpeechEngine speech;private boolean loaded=false;
    private Transport.Listener transportListener;
    private ResearchBenchmark benchmark;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xffffffff);
        getWindow().setNavigationBarColor(0xffffffff);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.view.View decor = getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility() | android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decor.setSystemUiVisibility(flags);
        }
        web=new WebView(this);
        android.widget.FrameLayout root=new android.widget.FrameLayout(this);
        root.addView(web,new android.widget.FrameLayout.LayoutParams(-1,-1));setContentView(root);
        root.setOnApplyWindowInsetsListener((view,insets)->{android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.ime());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        web.setBackgroundColor(0xffffffff);
        web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);web.getSettings().setBlockNetworkLoads(true);
        web.addJavascriptInterface(new Bridge(),"iTantra");
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                if("https://app.itantra.local/".equals(request.getUrl().toString()))try{return new WebResourceResponse("text/html","UTF-8",getAssets().open("index.html"));}catch(IOException ignored){}
                return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
            }
            @Override public void onPageFinished(WebView v,String url){loaded=true;event("device",Events.json("name",android.os.Build.MODEL,"android",android.os.Build.VERSION.RELEASE));speech.status();}
        });
        transportListener=new Transport.Listener(){
            public void event(String type,JSONObject data){MainActivity.this.event(type,data);}
            public void received(ItpPacket.Decoded message){event("received",Events.json("text",message.text(),"language",message.language(),"sequence",message.sequence(),"fecCorrected",message.correctedCodewords()));runOnUiThread(()->speech.receive(message));}
        };
        transport=new AcousticTransport(this,transportListener);
        benchmark=new ResearchBenchmark(this);
        speech=new SpeechEngine(this,new SpeechEngine.Listener(){
            public void event(String type,JSONObject data){MainActivity.this.event(type,data);}
            public void transcript(String text,String language){transport.sendText(text,language);}
        });
        speech.acoustic((AcousticTransport)transport);
        web.loadUrl("https://app.itantra.local/");
    }
    private void event(String type,JSONObject data){if(testObserver!=null)testObserver.accept(type,data);runOnUiThread(()->{if(loaded&&web!=null)web.evaluateJavascript("window.onNative("+JSONObject.quote(type)+","+data.toString()+")",null);});}
    public final class Bridge {
        @JavascriptInterface public void command(String action,String encoded){runOnUiThread(()->{try{
            JSONObject data=new JSONObject(encoded);
            switch(action){
                case "host" -> transport.host();
                case "debugTransceiver" -> {if(testObserver!=null)web.evaluateJavascript("switchTab('viewTransceiver')",null);}
                case "transport" -> selectTransport(data.optString("transport","ACOUSTIC"));
                case "benchmarkStart" -> {if((getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)==0)throw new IllegalStateException("Debug build required");benchmark.start(data,transport.getMetrics());}
                case "benchmarkEnd" -> event("benchmark",benchmark.finish(transport.getMetrics()));
                case "discover" -> transport.discover();
                case "connect" -> transport.connect(data.optString("address"),data.optInt("port",8988),data.optString("pin"),data.optString("callsign"));
                case "respondConnection" -> transport.respondRequest(data.optBoolean("accept",false));
                case "cancelRequest" -> transport.cancelRequest();
                case "callsign" -> transport.callsign(data.optString("callsign"));
                case "disconnect" -> {speech.pause();transport.disconnect();speech.resume();}
                case "send" -> transport.sendText(data.optString("text"),data.optString("language","hi"));
                case "start" -> {if(transport.isConnected())speech.start();else event("error",Events.json("message","Connect to the other phone before speaking."));}
                case "release" -> speech.release();
                case "cancel" -> speech.cancelCapture();
                case "conversation" -> {boolean enabled=data.optBoolean("enabled");if(!enabled||transport.isConnected())speech.conversation(enabled);else event("error",Events.json("message","Connect before starting conversation."));}
                case "stopPlayback" -> {speech.stopPlayback();if(transport instanceof AcousticTransport)transport.disconnect();}
                case "language" -> speech.language(data.optString("language","hi"));
                case "bitrate" -> transport.bitrate(data.optInt("bps",2000));
                case "capabilities" -> speech.status();
                case "downloadModel" -> speech.downloadModel();
                case "ttsSettings" -> startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
                case "wifiSettings" -> {if(transport instanceof AcousticTransport)event("notice",Events.json("message","Acoustic mode uses the microphone and speaker. Wi-Fi settings are unnecessary."));else startActivity(new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS));}
                default -> throw new IllegalArgumentException("Unknown action");
            }
        }catch(Exception e){event("error",Events.json("message",e.getMessage()==null?"Action unavailable":e.getMessage()));}});}
    }
    private void selectTransport(String value){
        if(!value.equals("ACOUSTIC")&&!value.equals("WIFI_LAN")&&!value.equals("WEBSITE"))throw new IllegalArgumentException("Unsupported transport");
        speech.pause();transport.close();
        transport=switch(value){case "ACOUSTIC"->new AcousticTransport(this,transportListener);case "WIFI_LAN"->new LocalTransport(this,transportListener);default->new WebsiteTransport(transportListener);};
        speech.acoustic(transport instanceof AcousticTransport?(AcousticTransport)transport:null);speech.resume();speech.status();
        event("transport",Events.json("transport",value));
    }
    @Override protected void onPause(){super.onPause();if(speech!=null)speech.pause();if(transport instanceof AcousticTransport)transport.pause();}
    @Override protected void onResume(){super.onResume();if(speech!=null)speech.resume();}
    @Override protected void onDestroy(){loaded=false;if(speech!=null)speech.close();if(transport!=null)transport.close();if(web!=null){web.removeJavascriptInterface("iTantra");web.destroy();web=null;}super.onDestroy();}
}
