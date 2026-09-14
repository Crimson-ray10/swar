package in.itantra.mobile;

import android.content.Context;
import android.net.nsd.*;
import android.net.wifi.WifiManager;
import android.os.Build;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.security.SecureRandom;

/** Foreground, two-peer LAN transport. CONTROL and ITP are separate frame kinds. */
public final class LocalTransport {
    public interface Listener { void event(String type, JSONObject data); void received(ItpPacket.Decoded message); }
    private final Listener listener;
    private final NsdManager nsd;
    private final WifiManager.MulticastLock multicast;
    private final ExecutorService io=Executors.newCachedThreadPool();
    private final ExecutorService sender=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService clock=Executors.newSingleThreadScheduledExecutor();
    private final UUID session=UUID.randomUUID();
    private final Map<Long,Long> pending=new ConcurrentHashMap<>();
    private final LinkedHashSet<String> seen=new LinkedHashSet<>();
    private final Object writeLock=new Object();
    private volatile Socket socket;
    private volatile ServerSocket server;
    private volatile boolean connected=false,closed=false,hosting=false;
    private volatile String pin="",address="",peer="",state="DISCONNECTED";
    private volatile int port=8988;
    private long seq=0,sent=0,received=0,bytesSent=0,bytesReceived=0,sourceSent=0,payloadSent=0,crcFailures=0,fecFailures=0,corrected=0,duplicates=0,unacked=0,lastPacketBytes=0;
    private volatile long lastReceived=now(),nextSend=0,generation=0;
    private final long metricsStarted=now();
    private volatile int bitrate=2000;
    private volatile long rtt=-1;
    private int retry=0;
    private UUID remoteSession;
    private long remoteSequence=0;
    private NsdManager.RegistrationListener registration;
    private NsdManager.DiscoveryListener discovery;
    private final Set<String> resolving=ConcurrentHashMap.newKeySet();

    public LocalTransport(Context context,Listener listener) {
        this.listener=listener; nsd=(NsdManager)context.getSystemService(Context.NSD_SERVICE);
        WifiManager wifi=(WifiManager)context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        multicast=wifi.createMulticastLock("itantra-discovery");multicast.setReferenceCounted(false);
        clock.scheduleWithFixedDelay(this::tick,2,2,TimeUnit.SECONDS);
    }
    private static long now(){return android.os.SystemClock.elapsedRealtime();}
    static JSONObject json(Object... fields){JSONObject o=new JSONObject();try{for(int i=0;i<fields.length;i+=2)o.put((String)fields[i],fields[i+1]);}catch(JSONException e){throw new IllegalArgumentException(e);}return o;}
    private void event(String type,Object... fields){listener.event(type,json(fields));}
    private void state(String value){state=value;event("connection","state",value,"peer",peer);}
    public boolean isConnected(){return connected;}
    public void bitrate(int value){if(value==500||value==1000||value==2000||value==4000||value==8000||value==16000)bitrate=value;}

    public void host(){
        io.execute(()->{
            if(server!=null){event("host","pin",pin,"addresses",addresses(),"port",port);return;}
            disconnect();hosting=true;pin=String.format(Locale.US,"%06d",new SecureRandom().nextInt(1000000));
            long cycle=generation;
            try{
            ServerSocket localServer=new ServerSocket(); localServer.setReuseAddress(true);localServer.bind(new InetSocketAddress(8988));server=localServer;
            advertise();state("DISCOVERING");event("host","pin",pin,"addresses",addresses(),"port",8988);
            while(!closed && server==localServer){Socket incoming=localServer.accept();if(socket!=null){incoming.close();continue;}attach(incoming,true);}
        }catch(Exception e){if(!closed&&cycle==generation){hosting=false;closeServer();state("FAILED");event("error","message","Cannot host: "+e.getMessage());}}});
    }
    public void connect(String host,int requestedPort,String code){
        if(!host.matches("(?:\\d{1,3}\\.){3}\\d{1,3}")||requestedPort<1||requestedPort>65535||!code.matches("\\d{6}")){event("error","message","Enter the host's local IPv4 address and six-digit joining code.");return;}
        try{InetAddress ip=InetAddress.getByName(host);if(!ip.isSiteLocalAddress()&&!ip.isLinkLocalAddress()){event("error","message","Only local-network addresses are accepted.");return;}}catch(Exception e){return;}
        disconnect();closeServer();hosting=false;address=host;port=requestedPort;pin=code;retry=0;connectInternal();
    }
    private void connectInternal(){long cycle=generation;io.execute(()->{if(closed||hosting||connected||address.isEmpty()||cycle!=generation)return;state(retry==0?"CONNECTING":"RECONNECTING");Socket next=new Socket();try{next.connect(new InetSocketAddress(address,port),5000);if(cycle!=generation){next.close();return;}attach(next,false);}catch(Exception e){try{next.close();}catch(Exception ignored){}if(cycle==generation)lost(null,e.getMessage());}});}

    private void attach(Socket next,boolean accepted)throws IOException{
        next.setTcpNoDelay(true);next.setSoTimeout(10000);socket=next;connected=false;lastReceived=now();remoteSession=null;remoteSequence=0;state("CONNECTING");
        if(!accepted)control(json("type","HELLO","protocol",1,"session",session.toString(),"name",Build.MODEL,"pin",pin,"codec","utf8-deflate-hamming84","languages","hi,en,hinglish-experimental"));
        io.execute(()->{try{DataInputStream input=new DataInputStream(next.getInputStream());while(!closed&&socket==next){byte[] body=FrameIO.read(input);int length=body.length;synchronized(this){bytesReceived+=length+4;}lastReceived=now();if(body[0]==1)onControl(new JSONObject(new String(body,1,length-1,StandardCharsets.UTF_8)),accepted);else if(body[0]==2){if(!connected)throw new IOException("Data before handshake");onPacket(Arrays.copyOfRange(body,1,length));}else throw new IOException("Unknown frame kind");}}catch(Exception e){lost(next,e.getMessage());}});
    }
    private void onControl(JSONObject data,boolean accepted)throws Exception{
        String type=data.optString("type");
        if(!connected){
            if(accepted&&type.equals("HELLO")){
                if(data.optInt("protocol")!=1||!pin.equals(data.optString("pin"))||!data.optString("codec").equals("utf8-deflate-hamming84")){control(json("type","REJECT"));throw new IOException("Joining code or protocol mismatch");}
                remoteSession=UUID.fromString(data.getString("session"));peer=data.optString("name","Phone");
                control(json("type","READY","protocol",1,"session",session.toString(),"name",Build.MODEL,"codec","utf8-deflate-hamming84"));ready();return;
            }
            if(!accepted&&type.equals("READY")&&data.optInt("protocol")==1&&data.optString("codec").equals("utf8-deflate-hamming84")){remoteSession=UUID.fromString(data.getString("session"));peer=data.optString("name","Phone");ready();return;}
            throw new IOException(type.equals("REJECT")?"Joining code rejected":"Invalid handshake");
        }
        switch(type){
            case "PING" -> control(json("type","PONG","at",data.getLong("at")));
            case "PONG" -> rtt=Math.max(0,now()-data.getLong("at"));
            case "ACK" -> {long sequence=data.getLong("seq");Long start=pending.remove(sequence);if(start!=null){event("delivery","sequence",sequence,"state","Decoded by peer","ackMs",now()-start);}}
            case "BYE" -> throw new IOException("Peer disconnected");
            default -> throw new IOException("Unknown control message");
        }
    }
    private void ready(){connected=true;retry=0;state("CONNECTED");}
    private void onPacket(byte[] wire)throws IOException{
        ItpPacket.Decoded message;
        try{message=ItpPacket.decode(wire);}catch(IOException e){synchronized(this){if(e.getMessage().contains("CRC"))crcFailures++;else fecFailures++;}event("error","message","Packet rejected: "+e.getMessage());return;}
        if(!message.session().equals(remoteSession))throw new IOException("Session mismatch");
        String key=message.session()+":"+message.sequence();
        synchronized(this){if(seen.contains(key)){duplicates++;control(json("type","ACK","seq",message.sequence()));return;}if(message.sequence()<=remoteSequence)throw new IOException("Out-of-order sequence");remoteSequence=message.sequence();seen.add(key);if(seen.size()>1024)seen.remove(seen.iterator().next());received++;corrected+=message.correctedCodewords();}
        control(json("type","ACK","seq",message.sequence()));listener.received(message);
    }
    public void sendText(String text,String language){
        if(!connected){event("error","message","Connect to the other phone first.");return;}
        if(text==null||text.trim().isEmpty())return;
        Socket target=socket;
        sender.execute(()->{try{
            if(target!=socket||!connected)throw new IOException("Connection changed before transmission");
            long sequence; synchronized(this){sequence=++seq;}
            var packet=ItpPacket.encode(session,sequence,language,text.trim());
            long delay=Math.max(0,nextSend-now());if(delay>0)Thread.sleep(delay);
            if(target!=socket||!connected)throw new IOException("Connection lost while waiting for bitrate budget");
            nextSend=now()+Math.max(1,packet.payloadBytes()*8000L/bitrate);
            event("sent","text",text.trim(),"sequence",sequence,"packetBytes",packet.wire().length+5,"payloadBytes",packet.payloadBytes());
            pending.put(sequence,now());write((byte)2,packet.wire());
            synchronized(this){sent++;sourceSent+=packet.sourceBytes();payloadSent+=packet.payloadBytes();lastPacketBytes=packet.wire().length+5;}
        }catch(Exception e){event("error","message","Message not sent: "+e.getMessage());}});
    }
    private void control(JSONObject data)throws IOException{write((byte)1,data.toString().getBytes(StandardCharsets.UTF_8));}
    private void write(byte kind,byte[] bytes)throws IOException{synchronized(writeLock){Socket current=socket;if(current==null)throw new IOException("Disconnected");DataOutputStream out=new DataOutputStream(current.getOutputStream());FrameIO.write(out,kind,bytes);bytesSent+=bytes.length+5;}}
    private void tick(){try{
        if(connected){if(now()-lastReceived>9000){lost(socket,"Heartbeat timeout");return;}control(json("type","PING","at",now()));}
        for(var entry:pending.entrySet())if(now()-entry.getValue()>12000&&pending.remove(entry.getKey(),entry.getValue())){unacked++;event("delivery","sequence",entry.getKey(),"state","No decode acknowledgement; delivery uncertain");}
        double seconds=Math.max(1,(now()-metricsStarted)/1000.0);
        event("metrics","sent",sent,"received",received,"txBytes",bytesSent,"rxBytes",bytesReceived,"appTxBps",Math.round(bytesSent*8/seconds),"payloadBps",Math.round(payloadSent*8/seconds),"payloadBytes",payloadSent,"sourceBytes",sourceSent,"crcFailures",crcFailures,"fecFailures",fecFailures,"fecCorrected",corrected,"duplicates",duplicates,"unacknowledged",unacked,"rttMs",rtt,"configuredBps",bitrate,"lastPacketBytes",lastPacketBytes,"overheadBytes",bytesSent-payloadSent,"textCompression",payloadSent==0?0:Math.round(sourceSent*100.0/payloadSent)/100.0);
    }catch(Exception e){if(connected)lost(socket,e.getMessage());}}
    private synchronized void lost(Socket expected,String reason){
        if(expected!=null&&socket!=expected)return;Socket old=socket;socket=null;connected=false;if(old!=null)try{old.close();}catch(Exception ignored){}
        if(closed)return;event("error","message","Link: "+reason);
        if(!hosting&&!address.isEmpty()&&retry<4){retry++;state("RECONNECTING");long cycle=generation;clock.schedule(()->{if(cycle==generation&&!connected)connectInternal();},Math.min(8,retry*2),TimeUnit.SECONDS);}else state(hosting?"DISCOVERING":"DISCONNECTED");
    }
    public synchronized void disconnect(){generation++;address="";connected=false;hosting=false;closeServer();Socket old=socket;socket=null;if(old!=null)try{old.close();}catch(Exception ignored){}for(long id:pending.keySet())event("delivery","sequence",id,"state","Disconnected; delivery uncertain");pending.clear();state("DISCONNECTED");}
    private void advertise(){
        NsdServiceInfo info=new NsdServiceInfo();info.setServiceName("iTantra-"+Build.MODEL+"-"+session.toString().substring(0,4));info.setServiceType("_itantra._tcp.");info.setPort(8988);
        registration=new NsdManager.RegistrationListener(){public void onServiceRegistered(NsdServiceInfo i){}public void onRegistrationFailed(NsdServiceInfo i,int error){event("error","message","Discovery advertising unavailable; use host address.");}public void onServiceUnregistered(NsdServiceInfo i){}public void onUnregistrationFailed(NsdServiceInfo i,int e){}};
        try{nsd.registerService(info,NsdManager.PROTOCOL_DNS_SD,registration);}catch(Exception e){event("error","message","Use host address; discovery registration failed.");}
    }
    public void discover(){
        if(discovery!=null)return;
        try{multicast.acquire();discovery=new NsdManager.DiscoveryListener(){
            public void onDiscoveryStarted(String type){event("discovery","state","Scanning local network");}
            public void onServiceFound(NsdServiceInfo info){if(info.getServiceName().contains(session.toString().substring(0,4))||!resolving.add(info.getServiceName()))return;nsd.resolveService(info,new NsdManager.ResolveListener(){public void onResolveFailed(NsdServiceInfo i,int e){resolving.remove(i.getServiceName());}public void onServiceResolved(NsdServiceInfo i){resolving.remove(i.getServiceName());String host=i.getHost().getHostAddress();if(host!=null&&host.contains("."))event("peer","name",i.getServiceName(),"address",host,"port",i.getPort());}});}
            public void onServiceLost(NsdServiceInfo info){event("peerLost","name",info.getServiceName());}
            public void onDiscoveryStopped(String t){}public void onStartDiscoveryFailed(String t,int e){event("error","message","Discovery failed; use the host address shown on the other phone.");}public void onStopDiscoveryFailed(String t,int e){}
        };nsd.discoverServices("_itantra._tcp.",NsdManager.PROTOCOL_DNS_SD,discovery);}catch(Exception e){event("error","message","Discovery unavailable: "+e.getMessage());}
    }
    private JSONArray addresses(){JSONArray result=new JSONArray();try{for(NetworkInterface network:Collections.list(NetworkInterface.getNetworkInterfaces()))for(InetAddress ip:Collections.list(network.getInetAddresses()))if(ip instanceof Inet4Address&&!ip.isLoopbackAddress()&&ip.isSiteLocalAddress())result.put(ip.getHostAddress());}catch(Exception ignored){}return result;}
    private void closeServer(){ServerSocket old=server;server=null;if(old!=null)try{old.close();}catch(Exception ignored){}if(registration!=null){try{nsd.unregisterService(registration);}catch(Exception ignored){}registration=null;}}
    public void close(){closed=true;disconnect();closeServer();if(discovery!=null)try{nsd.stopServiceDiscovery(discovery);}catch(Exception ignored){}if(multicast.isHeld())multicast.release();sender.shutdownNow();io.shutdownNow();clock.shutdownNow();}
}


