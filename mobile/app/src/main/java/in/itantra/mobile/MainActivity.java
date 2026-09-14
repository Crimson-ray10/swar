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
    private WebView web;private LocalTransport transport;private SpeechEngine speech;private boolean loaded=false;
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
            @Override public void onPageFinished(WebView v,String url){loaded=true;event("device",LocalTransport.json("name",android.os.Build.MODEL,"android",android.os.Build.VERSION.RELEASE));speech.status();}
        });
        transport=new LocalTransport(this,new LocalTransport.Listener(){
            public void event(String type,JSONObject data){MainActivity.this.event(type,data);}
            public void received(ItpPacket.Decoded message){event("received",LocalTransport.json("text",message.text(),"language",message.language(),"sequence",message.sequence(),"fecCorrected",message.correctedCodewords()));runOnUiThread(()->speech.receive(message));}
        });
        speech=new SpeechEngine(this,new SpeechEngine.Listener(){
            public void event(String type,JSONObject data){MainActivity.this.event(type,data);}
            public void transcript(String text,String language){transport.sendText(text,language);}
        });
        web.loadUrl("https://app.itantra.local/");
    }
    private void event(String type,JSONObject data){if(testObserver!=null)testObserver.accept(type,data);runOnUiThread(()->{if(loaded&&web!=null)web.evaluateJavascript("window.onNative("+JSONObject.quote(type)+","+data.toString()+")",null);});}
    public final class Bridge {
        @JavascriptInterface public void command(String action,String encoded){runOnUiThread(()->{try{
            JSONObject data=new JSONObject(encoded);
            switch(action){
                case "host" -> transport.host();
                case "discover" -> transport.discover();
                case "connect" -> transport.connect(data.optString("address"),data.optInt("port",8988),data.optString("pin"),data.optString("callsign"));
                case "respondConnection" -> transport.respondRequest(data.optBoolean("accept",false));
                case "cancelRequest" -> transport.cancelRequest();
                case "callsign" -> transport.callsign(data.optString("callsign"));
                case "disconnect" -> {speech.pause();speech.resume();transport.disconnect();}
                case "send" -> transport.sendText(data.optString("text"),data.optString("language","hi"));
                case "start" -> {if(transport.isConnected())speech.start();else event("error",LocalTransport.json("message","Connect to the other phone before speaking."));}
                case "release" -> speech.release();
                case "cancel" -> speech.cancelCapture();
                case "conversation" -> {boolean enabled=data.optBoolean("enabled");if(!enabled||transport.isConnected())speech.conversation(enabled);else event("error",LocalTransport.json("message","Connect before starting conversation."));}
                case "stopPlayback" -> speech.stopPlayback();
                case "language" -> speech.language(data.optString("language","hi"));
                case "bitrate" -> transport.bitrate(data.optInt("bps",2000));
                case "capabilities" -> speech.status();
                case "downloadModel" -> speech.downloadModel();
                case "ttsSettings" -> startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
                case "wifiSettings" -> startActivity(new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS));
                default -> throw new IllegalArgumentException("Unknown action");
            }
        }catch(Exception e){event("error",LocalTransport.json("message",e.getMessage()==null?"Action unavailable":e.getMessage()));}});}
    }
    @Override protected void onPause(){super.onPause();if(speech!=null)speech.pause();}
    @Override protected void onResume(){super.onResume();if(speech!=null)speech.resume();}
    @Override protected void onDestroy(){loaded=false;if(speech!=null)speech.close();if(transport!=null)transport.close();if(web!=null){web.removeJavascriptInterface("iTantra");web.destroy();web=null;}super.onDestroy();}
}
