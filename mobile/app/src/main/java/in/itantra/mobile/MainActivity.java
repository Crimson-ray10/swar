package in.itantra.mobile;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.*;
import android.view.WindowManager;
import android.content.*;
import org.json.*;
import java.io.*;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.animation.OvershootInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.Spanned;

public final class MainActivity extends Activity {
    static volatile java.util.function.BiConsumer<String,JSONObject> testObserver;
    private WebView web;private LocalTransport transport;private SpeechEngine speech;private boolean loaded=false;
    private FrameLayout splash;
    private Typeface gravitas;
    
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xffffffff);
        getWindow().setNavigationBarColor(0xffffffff);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            View decor = getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decor.setSystemUiVisibility(flags);
        }
        
        try {
            gravitas = Typeface.createFromAsset(getAssets(), "GravitasOne.ttf");
        } catch(Exception e) {
            gravitas = Typeface.create("serif", Typeface.BOLD);
        }

        web=new WebView(this);
        final FrameLayout root=new FrameLayout(this);
        root.addView(web,new FrameLayout.LayoutParams(-1,-1));
        
        splash = new FrameLayout(this);
        splash.setBackgroundColor(0xFF000000);
        splash.setElevation(100f);
        root.addView(splash, new FrameLayout.LayoutParams(-1,-1));
        
        setContentView(root);
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
            @Override public void onPageFinished(WebView v,String url){
                loaded=true;
                event("device",LocalTransport.json("name",android.os.Build.MODEL,"android",android.os.Build.VERSION.RELEASE));
                speech.status();
                
                // Fetch exact rect of the web H1 text to overlay perfectly
                web.evaluateJavascript("(() => { const el = document.querySelector('.hero-brand-title'); if(!el) return '0,0,0,0'; const rect = el.getBoundingClientRect(); return rect.left + ',' + rect.top + ',' + rect.width + ',' + rect.height; })()", value -> {
                    if(splash == null || splash.getParent() == null) return;
                    try {
                        String clean = value.replace("\"", "");
                        String[] parts = clean.split(",");
                        if(parts.length == 4) {
                            float density = getResources().getDisplayMetrics().density;
                            float cssLeft = Float.parseFloat(parts[0]);
                            float cssTop = Float.parseFloat(parts[1]);
                            float cssWidth = Float.parseFloat(parts[2]);
                            float cssHeight = Float.parseFloat(parts[3]);
                            
                            float targetCenterX = (cssLeft + cssWidth / 2f) * density;
                            float targetCenterY = (cssTop + cssHeight / 2f) * density;
                            
                            startSplashAnimation(targetCenterX, targetCenterY, cssHeight * density);
                        } else {
                            // Fallback
                            float density = getResources().getDisplayMetrics().density;
                            startSplashAnimation(getResources().getDisplayMetrics().widthPixels / 2f, 300f * density, 56f * density);
                        }
                    } catch(Exception e) {
                        float density = getResources().getDisplayMetrics().density;
                        startSplashAnimation(getResources().getDisplayMetrics().widthPixels / 2f, 300f * density, 56f * density);
                    }
                });
            }
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
    
    private void startSplashAnimation(float finalX, float finalY, float fontSizePx) {
        float screenWidth = getResources().getDisplayMetrics().widthPixels;
        float screenHeight = getResources().getDisplayMetrics().heightPixels;
        float startX = screenWidth / 2f;
        float startY = screenHeight / 2f;
        String[] texts = {"ಲಿಂಕ್.", "ਲਿੰਕ.", "लिंक.", "லிங்க்.", "లింక్.", "লিংক.", "LinC."};
        // 3 above, 3 below
        float[] targetYsDp = {-240f, -160f, -80f, 80f, 160f, 240f};
        float[] targetXsDp = {-35f, 55f, -45f, 35f, -30f, 50f};
        float[] rotations = {-8f, 5f, -10f, 4f, -6f, 8f};
        
        float density = getResources().getDisplayMetrics().density;
        final TextView[] textViews = new TextView[texts.length];
        
        for(int i = 0; i < texts.length; i++) {
            TextView tv;
            if(i == texts.length - 1) { // Main English
                tv = new TextView(this);
                tv.setTextColor(0xFFFFFFFF);
                SpannableString span = new SpannableString(texts[i]);
                span.setSpan(new ForegroundColorSpan(0xFF10B981), span.length() - 1, span.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                tv.setText(span);
            } else {
                tv = new TextView(this) {
                    @Override protected void onDraw(android.graphics.Canvas canvas) {
                        android.content.res.ColorStateList states = getTextColors();
                        getPaint().setStyle(android.graphics.Paint.Style.STROKE);
                        getPaint().setStrokeWidth(5);
                        setTextColor(0xFFFFFFFF);
                        super.onDraw(canvas);
                        getPaint().setStyle(android.graphics.Paint.Style.FILL);
                        setTextColor(0xFF000000);
                        super.onDraw(canvas);
                        setTextColor(states);
                    }
                };
                tv.setText(texts[i]);
            }
            
            // Match the physical web font size as close as possible
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizePx);
            tv.setTypeface(gravitas);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setIncludeFontPadding(false);
            
            tv.setAlpha(0f);
            tv.setScaleX(0.5f);
            tv.setScaleY(0.5f);
            
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
            // Position absolutely by using top/left margins
            splash.addView(tv, lp);
            
            // Measure to center exactly at startX, startY
            tv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            tv.setTranslationX(startX - (tv.getMeasuredWidth() / 2f));
            tv.setTranslationY(startY - (tv.getMeasuredHeight() / 2f));
            
            textViews[i] = tv;
        }
        
        // --- PHASE 1: Main pops in ---
        TextView mainTv = textViews[6];
        mainTv.setAlpha(1f);
        mainTv.setScaleX(0.9f);
        mainTv.setScaleY(0.9f);
        
        ObjectAnimator p1_sX = ObjectAnimator.ofFloat(mainTv, "scaleX", 0.9f, 1.1f, 1.0f);
        ObjectAnimator p1_sY = ObjectAnimator.ofFloat(mainTv, "scaleY", 0.9f, 1.1f, 1.0f);
        AnimatorSet pop = new AnimatorSet();
        pop.playTogether(p1_sX, p1_sY);
        pop.setDuration(600);
        pop.setInterpolator(new OvershootInterpolator());
        
        // --- PHASE 2: Translations burst out ---
        AnimatorSet burst = new AnimatorSet();
        java.util.ArrayList<Animator> burstAnims = new java.util.ArrayList<>();
        for(int i = 0; i < 6; i++) {
            TextView tv = textViews[i];
            burstAnims.add(ObjectAnimator.ofFloat(tv, "alpha", 0f, 1f));
            burstAnims.add(ObjectAnimator.ofFloat(tv, "scaleX", 0.5f, 1.15f));
            burstAnims.add(ObjectAnimator.ofFloat(tv, "scaleY", 0.5f, 1.15f));
            // Add translation on top of the base centering
            float baseX = startX - (tv.getMeasuredWidth() / 2f);
            float baseY = startY - (tv.getMeasuredHeight() / 2f);
            burstAnims.add(ObjectAnimator.ofFloat(tv, "translationY", baseY, baseY + targetYsDp[i] * density));
            burstAnims.add(ObjectAnimator.ofFloat(tv, "translationX", baseX, baseX + targetXsDp[i] * density));
            burstAnims.add(ObjectAnimator.ofFloat(tv, "rotation", 0f, rotations[i]));
        }
        burst.playTogether(burstAnims);
        burst.setDuration(1000);
        burst.setInterpolator(new DecelerateInterpolator());
        
        // --- PHASE 3: Scale Up (Pop starts) & Translations fade out ---
        ObjectAnimator p3_sX_up = ObjectAnimator.ofFloat(mainTv, "scaleX", 1.0f, 1.25f);
        ObjectAnimator p3_sY_up = ObjectAnimator.ofFloat(mainTv, "scaleY", 1.0f, 1.25f);
        
        AnimatorSet fadeTrans = new AnimatorSet();
        java.util.ArrayList<Animator> fades = new java.util.ArrayList<>();
        for(int i = 0; i < 6; i++){
            fades.add(ObjectAnimator.ofFloat(textViews[i], "alpha", 1f, 0f));
        }
        fadeTrans.playTogether(fades);
        
        AnimatorSet outroPart1 = new AnimatorSet();
        outroPart1.playTogether(p3_sX_up, p3_sY_up, fadeTrans);
        outroPart1.setDuration(500);
        outroPart1.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        
        // --- PHASE 4: Scale Down & Translate & Delayed Fade out (Transition) ---
        ObjectAnimator p4_sX_down = ObjectAnimator.ofFloat(mainTv, "scaleX", 1.25f, 1.0f);
        ObjectAnimator p4_sY_down = ObjectAnimator.ofFloat(mainTv, "scaleY", 1.25f, 1.0f);
        p4_sX_down.setDuration(700);
        p4_sY_down.setDuration(700);
        
        float currentTransX = startX - (mainTv.getMeasuredWidth() / 2f);
        float currentTransY = startY - (mainTv.getMeasuredHeight() / 2f);
        float endTransX = finalX - (mainTv.getMeasuredWidth() / 2f);
        float endTransY = finalY - (mainTv.getMeasuredHeight() / 2f);
        
        ObjectAnimator p4_tX = ObjectAnimator.ofFloat(mainTv, "translationX", currentTransX, endTransX);
        ObjectAnimator p4_tY = ObjectAnimator.ofFloat(mainTv, "translationY", currentTransY, endTransY);
        p4_tX.setDuration(700);
        p4_tY.setDuration(700);
        
        ObjectAnimator mainFadeOut = ObjectAnimator.ofFloat(mainTv, "alpha", 1f, 0f);
        ObjectAnimator bgFadeOut = ObjectAnimator.ofFloat(splash, "alpha", 1f, 0f);
        mainFadeOut.setDuration(450);
        bgFadeOut.setDuration(450);
        mainFadeOut.setStartDelay(250); // Start fading only after coming back down a little bit
        bgFadeOut.setStartDelay(250);
        
        AnimatorSet outroPart2 = new AnimatorSet();
        outroPart2.playTogether(p4_sX_down, p4_sY_down, p4_tX, p4_tY, mainFadeOut, bgFadeOut);
        outroPart2.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        
        // Orchestrate all phases
        AnimatorSet fullSequence = new AnimatorSet();
        AnimatorSet intro = new AnimatorSet();
        intro.playSequentially(pop, burst);
        
        AnimatorSet outro = new AnimatorSet();
        outro.playSequentially(outroPart1, outroPart2);
        outro.setStartDelay(1000); // Wait 1 second before doing the final disappear sequence
        
        fullSequence.playSequentially(intro, outro);
        fullSequence.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                ((FrameLayout)splash.getParent()).removeView(splash);
            }
        });
        fullSequence.start();
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
