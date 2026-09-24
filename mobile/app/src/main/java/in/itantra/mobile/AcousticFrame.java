package in.itantra.mobile;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.zip.CRC32;

/** IAC/1 link envelope. All metadata + payload + CRC is SECDED protected. ITP/1 is unchanged. */
public record AcousticFrame(Type type, UUID device, UUID session, long sequence, byte[] payload) {
    public enum Type { HELLO, HELLO_ACK, CAPABILITY, CAPABILITY_ACK, SESSION_START, SESSION_END,
        PING, PONG, TEXT, VOICE_EVENT, CONTROL, ACK, NACK }
    public static final int HEADER_BYTES = 44;
    public record Decoded(AcousticFrame frame, int corrections) {}
    public AcousticFrame { payload = payload.clone(); }
    @Override public byte[] payload() { return payload.clone(); }

    public byte[] encode(AcousticConfig config) throws IOException {
        if (payload.length > config.maxPayload() || sequence < 0 || sequence > 0xffffffffL) throw new IOException("Frame bounds");
        ByteBuffer raw = ByteBuffer.allocate(HEADER_BYTES + payload.length + 4);
        raw.putShort((short)0x4941).put((byte)1).put((byte)type.ordinal());
        raw.putLong(device.getMostSignificantBits()).putLong(device.getLeastSignificantBits());
        raw.putLong(session.getMostSignificantBits()).putLong(session.getLeastSignificantBits());
        raw.putInt((int)sequence).putShort((short)payload.length).putShort((short)0).put(payload);
        CRC32 crc = new CRC32(); crc.update(raw.array(), 0, raw.position()); raw.putInt((int)crc.getValue());
        return protect(raw.array());
    }
    static byte[] protect(byte[] raw) {
        byte[] wire = new byte[raw.length * 2];
        for (int i=0;i<raw.length;i++) {
            wire[i*2]=(byte)ItpPacket.hammingEncode((raw[i] >>> 4) & 15);
            wire[i*2+1]=(byte)ItpPacket.hammingEncode(raw[i] & 15);
        }
        return wire;
    }
    public static Decoded decode(byte[] wire, AcousticConfig config) throws IOException {
        if (wire.length < (HEADER_BYTES+4)*2 || wire.length > config.maxWireBytes() || wire.length%2!=0) throw new IOException("Frame length");
        byte[] raw = new byte[wire.length/2]; int corrections=0;
        ArrayList<Integer> uncertain=new ArrayList<>();ArrayList<int[]> candidates=new ArrayList<>();
        for(int i=0;i<wire.length;i++) {
            int nibble;
            try{int decoded=ItpPacket.hammingDecode(wire[i]&255);nibble=decoded&15;corrections+=decoded>>>4;}
            catch(IOException failure){
                // Bounded CRC-aided list decoding for detected double-bit words. CRC must select one unique frame.
                if(uncertain.size()>=4)throw new IOException("FEC_FAILURE: list budget");
                int[] choices=new int[16];int count=0;
                for(int value=0;value<16;value++)if(Integer.bitCount(ItpPacket.hammingEncode(value)^(wire[i]&255))==2)choices[count++]=value;
                if(count==0)throw failure;uncertain.add(i);candidates.add(Arrays.copyOf(choices,count));nibble=choices[0];corrections++;
            }
            if(i%2==0)raw[i/2]=(byte)(nibble<<4);else raw[i/2]|=(byte)nibble;
        }
        if(!uncertain.isEmpty()){
            ArrayList<byte[]> matches=new ArrayList<>();recover(raw,uncertain,candidates,0,matches);
            if(matches.size()!=1)throw new IOException("FEC_FAILURE: no unique CRC-valid candidate");raw=matches.get(0);
        }
        CRC32 crc=new CRC32();crc.update(raw,0,raw.length-4);
        ByteBuffer in=ByteBuffer.wrap(raw);
        if((int)crc.getValue()!=in.getInt(raw.length-4))throw new IOException("CRC_FAILURE");
        if(in.getShort()!=(short)0x4941 || in.get()!=1)throw new IOException("Frame version");
        int kind=in.get()&255;if(kind>=Type.values().length)throw new IOException("Frame type");
        UUID device=new UUID(in.getLong(),in.getLong()),session=new UUID(in.getLong(),in.getLong());
        long sequence=Integer.toUnsignedLong(in.getInt());int size=in.getShort()&65535;
        if(in.getShort()!=0||size!=raw.length-HEADER_BYTES-4)throw new IOException("Frame metadata");
        byte[] payload=new byte[size];in.get(payload);
        return new Decoded(new AcousticFrame(Type.values()[kind],device,session,sequence,payload),corrections);
    }
    private static void recover(byte[] raw,ArrayList<Integer> positions,ArrayList<int[]> choices,int index,ArrayList<byte[]> matches){
        if(matches.size()>1)return;
        if(index==positions.size()){CRC32 crc=new CRC32();crc.update(raw,0,raw.length-4);if((int)crc.getValue()==ByteBuffer.wrap(raw).getInt(raw.length-4))matches.add(raw.clone());return;}
        int position=positions.get(index),at=position/2;
        for(int value:choices.get(index)){raw[at]=(byte)(position%2==0?(raw[at]&15)|(value<<4):(raw[at]&240)|value);recover(raw,positions,choices,index+1,matches);}
    }
}
