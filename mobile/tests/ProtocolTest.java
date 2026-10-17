package in.itantra.mobile;
import java.util.*;
import java.io.*;

public class ProtocolTest {
    interface Checked { void run() throws Exception; }
    static void check(boolean ok,String why) {if(!ok)throw new AssertionError(why);}
    static void rejects(Checked fn) throws Exception {try{fn.run();}catch(IOException expected){return;}throw new AssertionError("Expected rejection");}
    public static void main(String[] args) throws Exception {
        UUID session=UUID.randomUUID(); int count=0;
        for(String text:new String[]{"Help at the main gate.","मुझे तुरंत मदद चाहिए।","Main gate पर help चाहिए!","आग ".repeat(300),"🚑 Rescue"}) {
            var packet=ItpPacket.encode(session,42,"hinglish",text);
            var result=ItpPacket.decode(packet.wire());
            check(result.text().equals(text)&&result.session().equals(session)&&result.sequence()==42,"Unicode roundtrip");count++;
            for(int bit=0;bit<8;bit++) {byte[] damaged=packet.wire().clone();damaged[50]^=(byte)(1<<bit);var fixed=ItpPacket.decode(damaged);check(fixed.text().equals(text)&&fixed.correctedCodewords()==1,"FEC repair");count++;}
            byte[] doubleBit=packet.wire().clone();doubleBit[50]^=3;rejects(()->ItpPacket.decode(doubleBit));count++;
            byte[] crcBad=packet.wire().clone();crcBad[80]=(byte)ItpPacket.hammingEncode(0);crcBad[81]=(byte)ItpPacket.hammingEncode(0);rejects(()->ItpPacket.decode(crcBad));count++;
        }
        for(int n=0;n<16;n++) for(int bit=0;bit<8;bit++) check((ItpPacket.hammingDecode(ItpPacket.hammingEncode(n)^(1<<bit))&15)==n,"All nibbles");
        rejects(()->ItpPacket.decode(new byte[1])); rejects(()->ItpPacket.encode(session,0,"en","hi"));
        rejects(()->ItpPacket.encode(session,1,"xx","hi")); rejects(()->ItpPacket.encode(session,1,"en","x".repeat(9000)));
        System.out.println("PASS: "+count+" packet cases; all 128 nibble bit repairs; malformed/oversize/language rejection");
    }
}
