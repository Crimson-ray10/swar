package in.itantra.mobile;

import android.speech.tts.Voice;
import org.json.*;
import java.util.*;

/** Local provider inventory. Unknown installation state never becomes OFFLINE_READY. */
public final class ModelManager {
    private final Set<String> installed=new HashSet<>();
    private int sttMask,ttsMask;private boolean queried;
    public void installedStt(List<String> languages){installed.clear();installed.addAll(languages);queried=true;sttMask=0;for(String tag:languages){if(tag.startsWith("hi"))sttMask|=1;if(tag.startsWith("en"))sttMask|=2;}if((sttMask&3)==3)sttMask|=4;}
    public void installedTts(Set<Voice> voices){ttsMask=0;if(voices!=null)for(Voice voice:voices)if(localVoice(voice)){String lang=voice.getLocale().getLanguage();if(lang.equals("hi"))ttsMask|=1;if(lang.equals("en"))ttsMask|=2;}if((ttsMask&3)==3)ttsMask|=4;}
    static boolean localVoice(Voice voice){return !voice.isNetworkConnectionRequired()&&(voice.getFeatures()==null||!voice.getFeatures().contains(android.speech.tts.TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED));}
    public int sttMask(){return sttMask;}public int ttsMask(){return ttsMask;}
    public JSONObject status(String language){int mask=switch(language){case "en"->2;case "hinglish"->4;default->1;};return Events.json("state",queried&&(sttMask&ttsMask&mask)!=0?"OFFLINE_READY":"MODEL_NOT_AVAILABLE",
        "sttInstalled",new JSONArray(installed),"sttMask",sttMask,"ttsMask",ttsMask,"ramBytes",JSONObject.NULL,"modelBytes",JSONObject.NULL,
        "provider","Android system on-device speech","ai4bharatInstalled",false,"hinglishValidated",false);}
}
