package in.itantra.mobile;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class TransportTest {
    public static void main(String[] args)throws Exception{
        UUID session=UUID.randomUUID();String text="Main gate पर मदद चाहिए. Send help!";
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            CompletableFuture<Void> receiver=CompletableFuture.runAsync(()->{try(Socket peer=server.accept()){
                peer.setSoTimeout(3000);DataInputStream in=new DataInputStream(peer.getInputStream());DataOutputStream out=new DataOutputStream(peer.getOutputStream());
                byte[] hello=FrameIO.read(in);ProtocolTest.check(hello[0]==1,"Control kind");
                ProtocolTest.check(new String(hello,1,hello.length-1,StandardCharsets.UTF_8).equals("HELLO"),"Handshake frame");
                FrameIO.write(out,(byte)1,"READY".getBytes(StandardCharsets.UTF_8));
                byte[] data=FrameIO.read(in);ProtocolTest.check(data[0]==2,"ITP kind separate from control");
                var decoded=ItpPacket.decode(Arrays.copyOfRange(data,1,data.length));ProtocolTest.check(decoded.text().equals(text),"Mixed-language TCP delivery");
                FrameIO.write(out,(byte)1,Long.toString(decoded.sequence()).getBytes(StandardCharsets.UTF_8));
            }catch(Exception e){throw new CompletionException(e);}});
            try(Socket client=new Socket(InetAddress.getLoopbackAddress(),server.getLocalPort())){
                client.setSoTimeout(3000);DataOutputStream out=new DataOutputStream(client.getOutputStream());DataInputStream in=new DataInputStream(client.getInputStream());
                // Deliberately fragment the length and control payload byte-by-byte.
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();FrameIO.write(new DataOutputStream(bytes),(byte)1,"HELLO".getBytes(StandardCharsets.UTF_8));
                for(byte b:bytes.toByteArray()){out.writeByte(b);out.flush();}
                byte[] ready=FrameIO.read(in);ProtocolTest.check(new String(ready,1,ready.length-1,StandardCharsets.UTF_8).equals("READY"),"Ready received");
                FrameIO.write(out,(byte)2,ItpPacket.encode(session,7,"hinglish",text).wire());
                byte[] ack=FrameIO.read(in);ProtocolTest.check(new String(ack,1,ack.length-1,StandardCharsets.UTF_8).equals("7"),"Decode ACK");
            }
            receiver.get(5,TimeUnit.SECONDS);
        }
        for(int invalid:new int[]{-1,0,1,32769,Integer.MAX_VALUE}){
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();new DataOutputStream(bytes).writeInt(invalid);
            ProtocolTest.rejects(()->FrameIO.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
        }
        ProtocolTest.rejects(()->FrameIO.read(new DataInputStream(new ByteArrayInputStream(new byte[]{0,0,0,5,1,2}))));
        ByteArrayOutputStream coalesced=new ByteArrayOutputStream();DataOutputStream stream=new DataOutputStream(coalesced);
        FrameIO.write(stream,(byte)1,new byte[]{9});FrameIO.write(stream,(byte)2,new byte[]{8});
        DataInputStream all=new DataInputStream(new ByteArrayInputStream(coalesced.toByteArray()));
        ProtocolTest.check(FrameIO.read(all)[1]==9&&FrameIO.read(all)[1]==8,"Coalesced frames remain separate");
        System.out.println("PASS: real loopback TCP, fragmented handshake, Unicode ITP delivery, decode ACK, coalesced frames, invalid lengths and truncated EOF");
    }
}
