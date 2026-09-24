package in.itantra.mobile;

import android.content.Context;
import android.media.AudioManager;
import android.os.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Explicitly started diagnostics only. Stores measurements/conditions, never text or microphone audio. */
final class ResearchBenchmark {
    private final Context context;private JSONObject baseline,conditions;private long start;
    ResearchBenchmark(Context context){this.context=context;}
    synchronized void start(JSONObject metadata,JSONObject metrics)throws Exception{
        if(!"ACOUSTIC".equals(metrics.optString("transport")))throw new IOException("Benchmark requires acoustic transport");
        double distance=metadata.getDouble("distanceM");if(!Set.of(.2,.5,1.0,2.0,3.0,5.0).contains(distance))throw new IOException("Distance must be 0.2, 0.5, 1, 2, 3 or 5 metres");
        baseline=new JSONObject(metrics.toString());start=SystemClock.elapsedRealtime();
        AudioManager audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        conditions=Events.json("device",Build.MANUFACTURER+" "+Build.MODEL,"android",Build.VERSION.RELEASE,"distanceM",distance,
            "environment",metadata.optString("environment","unspecified"),"orientation",metadata.optString("orientation","unspecified"),"volumeStep",audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            "volumeMax",audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),"packetSizeRequested",metadata.optInt("packetSize",0),"sampleRate",metrics.opt("sampleRate"),"modulation",metrics.opt("modulation"),"fec",metrics.opt("fecScheme"),"rawConfiguredBps",metrics.opt("rawBps"));
    }
    synchronized JSONObject finish(JSONObject metrics)throws Exception{
        if(baseline==null)throw new IOException("Start a benchmark first");double seconds=Math.max(.001,(SystemClock.elapsedRealtime()-start)/1000.0);
        JSONObject result=new JSONObject(conditions.toString());result.put("elapsedSeconds",seconds);
        for(String key:List.of("packetsSent","packetsReceived","packetsLost","retransmissions","crcFailures","fecFailures","fecCorrected","sent","received","payloadBytes","txBytes","rxBytes"))result.put(key,metrics.optLong(key)-baseline.optLong(key));
        result.put("effectivePayloadBps",result.getLong("payloadBytes")*8/seconds);
        result.put("lastAckRttMs",metrics.opt("rttMs"));result.put("lastMessageLatencyMs",metrics.opt("packetLatencyMs"));
        result.put("packetSuccessRate",JSONObject.NULL); // Receiver/sender records must be joined; local reception count is not a denominator.
        result.put("snrDb",JSONObject.NULL);result.put("measurement","physical-session-counters; distance/environment supplied by operator");
        File directory=new File(context.getExternalFilesDir(null),"benchmarks");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create benchmark directory");
        String name="acoustic-"+System.currentTimeMillis();File json=new File(directory,name+".json"),csv=new File(directory,name+".csv");
        try(var out=new FileOutputStream(json)){out.write(result.toString(2).getBytes(StandardCharsets.UTF_8));}
        ArrayList<String> keys=new ArrayList<>();result.keys().forEachRemaining(keys::add);Collections.sort(keys);
        StringBuilder header=new StringBuilder(),row=new StringBuilder();for(String key:keys){if(header.length()>0){header.append(',');row.append(',');}header.append(quote(key));Object value=result.opt(key);row.append(quote(value==JSONObject.NULL?"":String.valueOf(value)));}
        try(var out=new FileOutputStream(csv)){out.write((header+"\n"+row+"\n").getBytes(StandardCharsets.UTF_8));}
        baseline=null;conditions=null;return Events.json("json",json.getAbsolutePath(),"csv",csv.getAbsolutePath(),"measurements",result);
    }
    private String quote(String text){return "\""+text.replace("\"","\"\"")+"\"";}
}
