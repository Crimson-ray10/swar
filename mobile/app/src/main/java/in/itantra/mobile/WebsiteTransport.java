package in.itantra.mobile;

import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Explicit HTTPS website mode. Never constructed by AcousticTransport; no fallback to other transports. */
public final class WebsiteTransport implements Transport {
    private final Listener listener;private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private final String client=UUID.randomUUID().toString();private volatile String endpoint="",token="",state="DISCONNECTED";
    private UUID session;private long cursor,sequence,generation;private volatile HttpURLConnection request;
    public WebsiteTransport(Listener listener){this.listener=listener;worker.scheduleWithFixedDelay(this::poll,1,1,TimeUnit.SECONDS);}
    private void state(String value){state=value;listener.event("connection",Events.json("state",value,"peer","Website relay","transport","WEBSITE"));}
    private void error(String value){listener.event("error",Events.json("message",value,"transport","WEBSITE"));}
    @Override public void connect(String address,int port,String pin,String callsign){
        disconnect();worker.execute(()->{try{
            URI uri=URI.create(address);if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()==null)throw new IOException("Paste the HTTPS room link including its #token from the website relay");
            String path=uri.getPath();if(!path.matches("/api/v1/itantra/rooms/[0-9a-f-]{36}"))throw new IOException("Invalid website room link");
            token=UUID.fromString(uri.getFragment()).toString();endpoint=new URI("https",null,uri.getHost(),uri.getPort(),path,null,null).toString();session=UUID.fromString(path.substring(path.lastIndexOf('/')+1));cursor=sequence=0;
            call("/join",Events.json("clientId",client));state("DISCOVERING");
        }catch(Exception e){endpoint="";error(e.getMessage());}});
    }
    private JSONObject call(String path,JSONObject body)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(endpoint+path).openConnection();request=c;c.setInstanceFollowRedirects(false);c.setConnectTimeout(5000);c.setReadTimeout(5000);c.setRequestProperty("Authorization","Bearer "+token);
        try{if(body!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            if(c.getResponseCode()!=200)throw new IOException("Website relay returned HTTP "+c.getResponseCode());
            try(InputStream in=c.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){if(bytes.size()+n>2*1024*1024)throw new IOException("Oversized relay response");bytes.write(buffer,0,n);}return new JSONObject(bytes.toString("UTF-8"));}
        }finally{c.disconnect();if(request==c)request=null;}
    }
    private void poll(){if(endpoint.isEmpty())return;long cycle=generation;try{
        JSONObject response=call("/messages?clientId="+client+"&after="+cursor,null);if(cycle!=generation)return;
        String next=response.getInt("clients")==2?"CONNECTED":"DISCOVERING";if(!state.equals(next))state(next);
        JSONArray messages=response.getJSONArray("messages");for(int i=0;i<messages.length();i++){var message=LogicalMessage.decode(messages.getJSONObject(i));if(!message.session().equals(session))throw new IOException("Wrong website session");listener.received(message);}
        cursor=response.getLong("cursor");
    }catch(Exception e){if(cycle==generation){error(e.getMessage());state("DEGRADED");}}}
    @Override public void sendText(String text,String language){worker.execute(()->{try{
        if(!isConnected())throw new IOException("Join the same relay room on both devices first");
        var itp=ItpPacket.decode(ItpPacket.encode(session,++sequence,language,text).wire());
        call("/messages",Events.json("clientId",client,"message",LogicalMessage.from(itp,System.currentTimeMillis())));
        listener.event("sent",Events.json("text",text,"sequence",sequence));listener.event("delivery",Events.json("sequence",sequence,"state","Queued by website relay; peer playback unconfirmed"));
    }catch(Exception e){error(e.getMessage());}});}
    @Override public void discover(){listener.event("notice",Events.json("message","Paste an HTTPS website room link in the connection field."));}
    @Override public void host(){discover();}@Override public void respondRequest(boolean accept){}@Override public void cancelRequest(){disconnect();}@Override public void callsign(String name){}@Override public void bitrate(int bps){}
    @Override public boolean isConnected(){return state.equals("CONNECTED");}
    @Override public String getState(){return state;}
    @Override public JSONObject getMetrics(){return Events.json("transport","WEBSITE");}
    @Override public void disconnect(){generation++;endpoint="";token="";HttpURLConnection c=request;if(c!=null)c.disconnect();state("DISCONNECTED");}
    @Override public void close(){disconnect();worker.shutdownNow();}
}
