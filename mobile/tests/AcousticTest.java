package in.itantra.mobile;

import java.util.*;
import java.io.*;

public final class AcousticTest {
    static int assertions;
    static void check(boolean ok,String why){assertions++;if(!ok)throw new AssertionError(why);}
    static final AcousticConfig C=AcousticConfig.robust();
    static void reject(byte[] wire)throws Exception{try{AcousticFrame.decode(wire,C);throw new AssertionError("Corruption accepted");}catch(IOException expected){assertions++;}}
    public static void main(String[] args)throws Exception {
        var frame=new AcousticFrame(AcousticFrame.Type.TEXT,UUID.randomUUID(),UUID.randomUUID(),104,"मुझे मदद चाहिए".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] wire=frame.encode(C);
        check(AcousticFrame.decode(wire,C).frame().sequence()==104,"Envelope roundtrip");
        for(int bit=0;bit<8;bit++){byte[] damaged=wire.clone();damaged[40]^=(byte)(1<<bit);check(AcousticFrame.decode(damaged,C).corrections()==1,"Outer SECDED");}
        byte[] damaged=wire.clone();damaged[50]^=3;check(AcousticFrame.decode(damaged,C).corrections()==1,"CRC-aided double-bit repair");
        damaged=wire.clone();for(int i=0;i<5;i++)damaged[50+i]^=3;reject(damaged);
        damaged=wire.clone();damaged[90]=(byte)ItpPacket.hammingEncode(5);reject(damaged);
        var tx=new AcousticModem(C,new AcousticModem.Listener(){public void event(String n){}public void packet(byte[] b){}});
        short[] signal=tx.modulate(wire);
        for(double snr:new double[]{30,15,8,3})for(int offset:new int[]{0,7,19,31}){
            Random rng=new Random(823+(long)snr+offset);short[] audio=new short[signal.length+offset+2000];
            double noise=32767*C.amplitude()/Math.sqrt(2)/Math.pow(10,snr/20);
            for(int i=0;i<signal.length;i++)audio[offset+i]=(short)Math.max(-32768,Math.min(32767,signal[i]+rng.nextGaussian()*noise));
            List<byte[]> packets=new ArrayList<>();List<String> events=new ArrayList<>();
            var rx=new AcousticModem(C,new AcousticModem.Listener(){public void event(String n){events.add(n);}public void packet(byte[] b){packets.add(b.clone());}});
            for(int p=0;p<audio.length;){int n=Math.min(1+rng.nextInt(537),audio.length-p);rx.accept(Arrays.copyOfRange(audio,p,p+n),n);p+=n;}
            check(packets.size()==1,"Packet detection SNR="+snr+" offset="+offset+" events="+events);
            check(Arrays.equals(packets.get(0),wire),"Bit recovery SNR="+snr+" offset="+offset);
            check(events.contains("SYNC_DETECTED"),"Sync event");
        }
        List<byte[]> falsePackets=new ArrayList<>();var rx=new AcousticModem(C,new AcousticModem.Listener(){public void event(String n){}public void packet(byte[] b){falsePackets.add(b.clone());}});
        Random rng=new Random(98);for(int i=0;i<500;i++){short[] noise=new short[320];for(int j=0;j<320;j++)noise[j]=(short)(rng.nextGaussian()*2000+3000*Math.sin(2*Math.PI*170*(i*320+j)/16000));rx.accept(noise,noise.length);}
        check(falsePackets.isEmpty(),"No packet in noise/harmonic tone");
        for(double drift:new double[]{-.001,0,.001}){
            short[] impaired=new short[(int)(signal.length*(1+drift))+2000];
            for(int i=0;i<impaired.length-2000;i++){
                double t=i/(1+drift);int left=(int)t;double fraction=t-left;
                double value=left+1<signal.length?signal[left]*(1-fraction)+signal[left+1]*fraction:0;
                double echo=left>=96&&left<signal.length?signal[left-96]*.18:0;
                impaired[i]=(short)Math.max(-2800,Math.min(2800,(value+echo)*.6));
            }
            List<byte[]> decoded=new ArrayList<>();var detector=new AcousticModem(C,new AcousticModem.Listener(){public void event(String n){}public void packet(byte[] b){decoded.add(b.clone());}});
            for(int p=0;p<impaired.length;p+=320){int n=Math.min(320,impaired.length-p);detector.accept(Arrays.copyOfRange(impaired,p,p+n),n);}
            check(decoded.size()==1&&Arrays.equals(decoded.get(0),wire),"Clipping + echo + clock drift "+drift);
        }
        testLink(false);testLink(true);testRetryExhaustion();testFragmentation();testNack();testNegotiation();
        System.out.println("PASS: "+assertions+" acoustic assertions (synthetic channel only; no handset/range claim)");
    }
    static class Node implements AcousticLink.Listener {
        final AcousticLink link;final ArrayDeque<AcousticFrame> tx=new ArrayDeque<>();final List<ItpPacket.Decoded> received=new ArrayList<>();
        Node(int id){link=new AcousticLink(C,new UUID(0,id),this,new Random(id));link.capabilities(7,7,1);}
        public void transmit(AcousticFrame f){tx.add(f);}public void event(String t,String s,long n){}public void received(ItpPacket.Decoded p){received.add(p);}
    }
    static void transfer(Node from,Node to,long now,boolean dropAck)throws Exception{
        if(!from.tx.isEmpty()){var f=from.tx.remove();from.link.transmitted(f,now);if(!(dropAck&&f.type()==AcousticFrame.Type.ACK))to.link.receive(f.encode(C),now+100);}
    }
    static void connect(Node a,Node b)throws Exception{
        a.link.start(0);b.link.start(0);
        for(long now=0;now<120000&&(!a.link.connected()||!b.link.connected());now+=100){a.link.tick(now,false);b.link.tick(now,false);transfer(a,b,now,false);transfer(b,a,now,false);}
        check(a.link.connected()&&b.link.connected(),"Acoustic handshake");check(a.link.session().equals(b.link.session()),"Shared session");check(a.link.mutuallySupportedVoiceMask()==7,"Language intersection");
    }
    static void testLink(boolean lostAck)throws Exception{
        Node a=new Node(1),b=new Node(2);connect(a,b);String text="main gate पर मदद चाहिए ".repeat(100);
        a.link.sendText(text,"hinglish",130000);
        for(long now=130000;now<260000&&a.link.messagesSent==0;now+=100){a.link.tick(now,false);b.link.tick(now,false);transfer(a,b,now,false);transfer(b,a,now,lostAck&&now<139000);}
        check(b.received.size()==1,"Deliver exactly once after lost ACK="+lostAck);check(b.received.get(0).text().equals(text),"Preserved ITP payload");check(a.link.messagesSent==1,"Acknowledged message");
        if(lostAck)check(a.link.retransmissions>0&&b.link.duplicates>0,"Retransmission and dedup");
        // Other sessions cannot inject data or ACK the current session.
        var foreign=new AcousticFrame(AcousticFrame.Type.SESSION_END,new UUID(0,99),a.link.session(),1,new byte[0]);a.link.receive(foreign.encode(C),270000);check(a.link.connected(),"Ignore other peer");
        b.link.sendText("reply","en",270000);for(long now=270000;now<290000&&b.link.messagesSent==0;now+=100){a.link.tick(now,false);b.link.tick(now,false);transfer(b,a,now,false);transfer(a,b,now,false);}check(a.received.size()==1,"Reverse direction");
    }
    static void testRetryExhaustion()throws Exception{
        Node a=new Node(1),b=new Node(2);connect(a,b);a.link.sendText("lost","en",130000);
        for(long now=130000;now<220000;now+=100){a.link.tick(now,false);while(!a.tx.isEmpty())a.link.transmitted(a.tx.remove(),now);}
        check(a.link.packetsLost==1,"Bounded retry exhaustion");check(a.link.messagesSent==0,"No fabricated delivery");
    }
    static void testFragmentation()throws Exception{
        Node a=new Node(1),b=new Node(2);connect(a,b);StringBuilder text=new StringBuilder();Random random=new Random(70);for(int i=0;i<2000;i++)text.append((char)(' '+random.nextInt(90)));
        a.link.sendText(text.toString(),"en",130000);
        for(long now=130000;now<290000&&a.link.messagesSent==0;now+=100){a.link.tick(now,false);b.link.tick(now,false);transfer(a,b,now,false);transfer(b,a,now,false);}
        check(a.link.messagesSent==1&&b.received.size()==1&&b.received.get(0).text().equals(text.toString()),"Multi-frame reassembly");
        check(a.link.packetsSent>8,"Fragmentation exercised");
    }
    static void testNack()throws Exception{
        Node a=new Node(1),b=new Node(2);connect(a,b);a.link.sendText("NACK recovery","en",130000);
        var f=a.tx.remove();a.link.transmitted(f,130000);byte[] broken=f.payload();broken[0]^=1;
        b.link.receive(new AcousticFrame(f.type(),f.device(),f.session(),f.sequence(),broken).encode(C),130100);
        check(b.tx.peek().type()==AcousticFrame.Type.NACK,"NACK invalid semantic metadata");transfer(b,a,130200,false);
        for(long now=131000;now<155000&&a.link.messagesSent==0;now+=100){a.link.tick(now,false);b.link.tick(now,false);transfer(a,b,now,false);transfer(b,a,now,false);}
        check(a.link.messagesSent==1&&b.received.size()==1,"NACK retransmit success");
        // A valid old frame with a new sequence is still rejected by fragment ordering/ITP ID.
        a.link.receive(new AcousticFrame(AcousticFrame.Type.ACK,new UUID(0,2),UUID.randomUUID(),f.sequence(),new byte[0]).encode(C),156000);
        check(a.link.messagesSent==1,"Foreign-session ACK ignored");
    }
    static void testNegotiation()throws Exception{
        Node a=new Node(1),b=new Node(2);a.link.capabilities(3,3,2);b.link.capabilities(1,1,1);connectWithoutAssertions(a,b);
        check(a.link.mutuallySupportedVoiceMask()==1&&a.link.negotiatedLanguage()==1,"Mutual language intersection");
        a.link.stop();check(!a.link.connected()&&a.link.session()==null,"Session cleanup");
    }
    static void connectWithoutAssertions(Node a,Node b)throws Exception{a.link.start(0);b.link.start(0);for(long now=0;now<120000&&(!a.link.connected()||!b.link.connected());now+=100){a.link.tick(now,false);b.link.tick(now,false);transfer(a,b,now,false);transfer(b,a,now,false);}check(a.link.connected()&&b.link.connected(),"Negotiation handshake");}
}
