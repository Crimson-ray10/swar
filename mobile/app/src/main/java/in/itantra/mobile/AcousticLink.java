package in.itantra.mobile;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

/** Single-thread confined, clock-injected link state machine; also runs over a simulated channel. */
public final class AcousticLink {
    public interface Listener {
        void transmit(AcousticFrame frame);
        void event(String type, String value, long sequence);
        void received(ItpPacket.Decoded message);
    }
    private final AcousticConfig config;
    private final UUID device;
    private final Listener listener;
    private final Random random;
    private UUID session,peer;
    private String state="IDLE";
    private AcousticFrame pending;
    private AcousticFrame.Type expected;
    private int retries;
    private long deadline=Long.MAX_VALUE,nextHello,handshakeDeadline,sequence,messageSequence,lastSeen;
    private long sentAt,startedAt;
    private int sttMask,ttsMask,preferred=1,negotiatedMask=7,remoteStt,remoteTts;
    private final ArrayDeque<AcousticFrame> fragments=new ArrayDeque<>();
    private final LinkedHashSet<Long> seen=new LinkedHashSet<>();
    private static final class TemporaryBuffer extends ByteArrayOutputStream {
        @Override public synchronized void reset(){Arrays.fill(buf,(byte)0);super.reset();}
    }
    private final TemporaryBuffer assembly=new TemporaryBuffer();
    private long assemblyId=-1;private int assemblyIndex,assemblyCount;
    public long packetsSent,packetsReceived,retransmissions,duplicates,packetsLost,crcFailures,fecFailures,fecCorrections;
    public long messagesSent,messagesReceived,wireBytesSent,wireBytesReceived,payloadBytesAcked,sourceBytesSent;
    public long rttTotal,rttCount,lastRtt=-1,lastMessageLatency=-1;
    private int queuedPayload;private long queuedAt,lastReceivedSequence;

    public AcousticLink(AcousticConfig config,UUID device,Listener listener,Random random) {
        this.config=config;this.device=device;this.listener=listener;this.random=random;
    }
    public void capabilities(int stt,int tts,int preferred){this.sttMask=stt&7;this.ttsMask=tts&7;this.preferred=preferred&7;}
    public String state(){return state;}
    public boolean connected(){return state.equals("CONNECTED")||state.equals("DEGRADED");}
    public UUID session(){return session;}
    public UUID peer(){return peer;}
    public boolean busy(){return pending!=null||!fragments.isEmpty();}
    public long elapsedMs(long now){return Math.max(1,now-startedAt);}
    private void state(String value){state=value;listener.event("connection",value,0);}
    public void start(long now){stop();session=UUID.randomUUID();startedAt=now;nextHello=now+random.nextInt(1800);state("DISCOVERING");}
    public void stop(){pending=null;fragments.clear();seen.clear();peer=null;session=null;assembly.reset();assemblyId=-1;lastReceivedSequence=0;state("DISCONNECTED");}
    private AcousticFrame frame(AcousticFrame.Type type,byte[] payload){if(++sequence>0xffffffffL)throw new IllegalStateException("Sequence exhausted; restart application");return new AcousticFrame(type,device,session,sequence,payload);}
    private void emit(AcousticFrame frame){listener.transmit(frame);}
    private void reliable(AcousticFrame frame,AcousticFrame.Type reply){pending=frame;expected=reply;retries=0;deadline=Long.MAX_VALUE;emit(frame);}
    /** ACK clock begins after AudioTrack playback has drained, never at enqueue time. */
    public void transmitted(AcousticFrame f,long now) throws IOException {
        packetsSent++;wireBytesSent+=f.encode(config).length;
        if(pending!=null&&f.sequence()==pending.sequence()){sentAt=now;deadline=now+config.ackTimeoutMs()+random.nextInt(700);}
    }
    public void tick(long now,boolean channelBusy){
        if(session==null||channelBusy)return;
        if(state.equals("DISCOVERING")&&now>=nextHello){emit(frame(AcousticFrame.Type.HELLO,capabilityBytes()));nextHello=now+config.ackTimeoutMs()+config.airtimeMs(136)+random.nextInt(3500);}
        if(state.equals("HANDSHAKING")&&pending==null&&now>handshakeDeadline){peer=null;state("DISCOVERING");nextHello=now+random.nextInt(3000);}
        if(pending!=null&&now>=deadline){
            if(retries++>=config.maxRetries()) {
                packetsLost++;long failed=pending.sequence();pending=null;fragments.clear();
                listener.event("delivery","Delivery uncertain; acoustic retries exhausted",failed);
                if(connected())state("DEGRADED");else {peer=null;state("DISCOVERING");nextHello=now+random.nextInt(3000);}
            } else {retransmissions++;listener.event("acoustic","RETRYING",pending.sequence());deadline=Long.MAX_VALUE;emit(pending);}
        }
        // Peers can be busy recognizing/speaking; do not use a LAN-scale heartbeat deadline.
        if(connected()&&!busy()&&now-lastSeen>120000){state("DEGRADED");reliable(frame(AcousticFrame.Type.PING,new byte[0]),AcousticFrame.Type.PONG);lastSeen=now;}
    }
    private byte[] capabilityBytes(){
        return ByteBuffer.allocate(20).put((byte)1).put((byte)1).put((byte)7).put((byte)sttMask).put((byte)ttsMask).put((byte)preferred)
            .putShort((short)config.maxPayload()).putInt(config.sampleRate()).putShort((short)config.samplesPerSymbol())
            .putShort((short)config.frequency0()).putShort((short)config.frequency1()).put((byte)1).put((byte)1).array();
    }
    private boolean negotiate(byte[] payload){
        if(payload.length!=20)return false;ByteBuffer b=ByteBuffer.wrap(payload);
        if(b.get()!=1||b.get()!=1)return false;int languages=b.get()&7;remoteStt=b.get()&7;remoteTts=b.get()&7;b.get();
        if(languages==0||(b.getShort()&65535)!=config.maxPayload()||b.getInt()!=config.sampleRate()||(b.getShort()&65535)!=config.samplesPerSymbol()
            ||(b.getShort()&65535)!=(int)config.frequency0()||(b.getShort()&65535)!=(int)config.frequency1()||b.get()!=1||b.get()!=1)return false;
        negotiatedMask=languages;return true;
    }
    public int mutuallySupportedVoiceMask(){return negotiatedMask&sttMask&ttsMask&remoteStt&remoteTts;}
    public int negotiatedLanguage(){int available=mutuallySupportedVoiceMask();if(available==0)available=negotiatedMask;return (available&preferred)!=0?Integer.lowestOneBit(available&preferred):Integer.lowestOneBit(available);}
    public void receive(byte[] wire,long now){
        AcousticFrame f;
        try{var decoded=AcousticFrame.decode(wire,config);f=decoded.frame();fecCorrections+=decoded.corrections();}
        catch(IOException e){if(e.getMessage().contains("CRC")){crcFailures++;listener.event("acoustic","CRC_FAILURE",0);}else {fecFailures++;listener.event("acoustic","FEC_FAILURE",0);}listener.event("acoustic","PACKET_INVALID",0);return;}
        if(session==null||f.device().equals(device))return;
        packetsReceived++;wireBytesReceived+=wire.length;listener.event("acoustic","PACKET_RECEIVED",f.sequence());
        listener.event("acoustic","FRAME_"+f.type().name(),f.sequence());
        if(f.type()==AcousticFrame.Type.HELLO){
            if(connected()||pending!=null||!negotiate(f.payload()))return;
            // Canonical initiator breaks simultaneous discovery symmetry. IDs are random install UUIDs.
            if(device.compareTo(f.device())<0){nextHello=Math.min(nextHello,now+random.nextInt(1200));return;}
            if(peer!=null&&!peer.equals(f.device()))return;
            peer=f.device();session=f.session();lastSeen=now;state("HANDSHAKING");handshakeDeadline=now+300000;
            emit(new AcousticFrame(AcousticFrame.Type.HELLO_ACK,device,session,f.sequence(),capabilityBytes()));return;
        }
        if(f.type()==AcousticFrame.Type.HELLO_ACK&&state.equals("DISCOVERING")&&session.equals(f.session())&&negotiate(f.payload())){
            peer=f.device();lastSeen=now;state("NEGOTIATING");reliable(frame(AcousticFrame.Type.CAPABILITY,capabilityBytes()),AcousticFrame.Type.CAPABILITY_ACK);return;
        }
        if(peer==null||!peer.equals(f.device())||!session.equals(f.session()))return;
        lastSeen=now;
        if(f.type()==AcousticFrame.Type.CAPABILITY&&(state.equals("HANDSHAKING")||connected())){
            if(negotiate(f.payload()))emit(new AcousticFrame(AcousticFrame.Type.CAPABILITY_ACK,device,session,f.sequence(),capabilityBytes()));return;
        }
        if(f.type()==AcousticFrame.Type.SESSION_START&&(state.equals("HANDSHAKING")||connected())){
            ack(f);if(!connected()){state("CONNECTED");listener.event("negotiated",Integer.toString(negotiatedLanguage()),0);}return;
        }
        if(pending!=null&&f.sequence()==pending.sequence()&&f.type()==expected){
            if(expected==AcousticFrame.Type.CAPABILITY_ACK&&!negotiate(f.payload()))return;
            AcousticFrame done=pending;pending=null;lastRtt=Math.max(0,now-sentAt);rttTotal+=lastRtt;rttCount++;
            switch(done.type()){
                case CAPABILITY -> reliable(frame(AcousticFrame.Type.SESSION_START,new byte[0]),AcousticFrame.Type.ACK);
                case SESSION_START -> {state("CONNECTED");listener.event("negotiated",Integer.toString(negotiatedLanguage()),0);}
                case TEXT -> {if(fragments.isEmpty()){messagesSent++;payloadBytesAcked+=queuedPayload;lastMessageLatency=now-queuedAt;listener.event("delivery","Decoded by peer",messageSequence);state("CONNECTED");}else sendNext();}
                case PING -> state("CONNECTED");
                default -> {}
            }
            return;
        }
        if(f.type()==AcousticFrame.Type.NACK&&pending!=null&&f.sequence()==pending.sequence()){deadline=now+300+random.nextInt(1000);return;}
        if(!connected())return;
        switch(f.type()){
            case TEXT -> receiveFragment(f);
            case PING -> emit(new AcousticFrame(AcousticFrame.Type.PONG,device,session,f.sequence(),new byte[0]));
            case SESSION_END -> stop();
            default -> {}
        }
    }
    private void ack(AcousticFrame frame){emit(new AcousticFrame(AcousticFrame.Type.ACK,device,session,frame.sequence(),new byte[0]));}
    private void receiveFragment(AcousticFrame f){
        if(seen.contains(f.sequence())){duplicates++;ack(f);return;}
        if(f.sequence()<=lastReceivedSequence)return;
        try{
            byte[] data=f.payload();if(data.length<=12)throw new IOException("Fragment size");ByteBuffer b=ByteBuffer.wrap(data);
            long id=b.getLong();int index=b.getShort()&65535,count=b.getShort()&65535;
            if(count<1||count>128||index>=count)throw new IOException("Fragment bounds");
            if(index==0&&id!=assemblyId){assembly.reset();assemblyId=id;assemblyIndex=0;assemblyCount=count;}
            if(id!=assemblyId||index!=assemblyIndex||count!=assemblyCount||assembly.size()+b.remaining()>(ItpPacket.MAX_TEXT+42)*2)throw new IOException("Fragment order");
            // Validate the final ITP before recording receipt or acknowledging delivery.
            byte[] chunk=new byte[b.remaining()];b.get(chunk);byte[] candidate=new byte[assembly.size()+chunk.length];
            System.arraycopy(assembly.toByteArray(),0,candidate,0,assembly.size());System.arraycopy(chunk,0,candidate,assembly.size(),chunk.length);
            ItpPacket.Decoded message=null;
            if(index==count-1){message=ItpPacket.decode(candidate);if(!message.session().equals(session)||message.sequence()!=id)throw new IOException("ITP session");}
            assembly.write(chunk);assemblyIndex++;seen.add(f.sequence());lastReceivedSequence=f.sequence();if(seen.size()>1024)seen.remove(seen.iterator().next());ack(f);
            if(message!=null){messagesReceived++;fecCorrections+=message.correctedCodewords();assembly.reset();listener.received(message);}
            Arrays.fill(candidate,(byte)0);Arrays.fill(chunk,(byte)0);
        }catch(IOException e){listener.event("acoustic","PACKET_INVALID",f.sequence());emit(new AcousticFrame(AcousticFrame.Type.NACK,device,session,f.sequence(),new byte[0]));}
    }
    public void sendText(String text,String language,long now)throws IOException {
        if(!connected())throw new IOException("Acoustic session is not connected");if(busy())throw new IOException("Wait for the current acoustic message acknowledgement");
        int mask=switch(language){case "hi"->1;case "en"->2;case "hinglish"->4;default->0;};if((negotiatedMask&mask)==0)throw new IOException("Language not negotiated");
        var encoded=ItpPacket.encode(session,++messageSequence,language,text);
        byte[] wire=encoded.wire();int capacity=config.maxPayload()-12,count=(wire.length+capacity-1)/capacity;
        for(int index=0;index<count;index++){
            int start=index*capacity,length=Math.min(capacity,wire.length-start);
            byte[] data=ByteBuffer.allocate(12+length).putLong(messageSequence).putShort((short)index).putShort((short)count).put(wire,start,length).array();
            fragments.add(frame(AcousticFrame.Type.TEXT,data));
        }
        sourceBytesSent+=encoded.sourceBytes();queuedPayload=encoded.payloadBytes();queuedAt=now;sendNext();
    }
    private void sendNext(){reliable(fragments.remove(),AcousticFrame.Type.ACK);}
    public AcousticFrame goodbye(){return session==null?null:frame(AcousticFrame.Type.SESSION_END,new byte[0]);}
}
