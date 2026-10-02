package in.itantra.mobile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.util.*;
import java.util.zip.*;

/** ITP/1 demo draft: lossless text, CRC32 and extended Hamming(8,4). No model claims. */
public final class ItpPacket {
    public static final int MAX_TEXT = 8192;
    public record Decoded(UUID session, long sequence, String language, String text,
                          boolean emergency,
                          int correctedCodewords, int sourceBytes, int payloadBytes) {
        /** Backward-compatible constructor for non-emergency callers. */
        public Decoded(UUID session, long sequence, String language, String text,
                       int correctedCodewords, int sourceBytes, int payloadBytes) {
            this(session, sequence, language, text, false, correctedCodewords, sourceBytes, payloadBytes);
        }

        /** Convenience accessor for non-emergency legacy callers. */
        public String text() { return text; }
        public String language() { return language; }
        public UUID session() { return session; }
        public long sequence() { return sequence; }
        public int correctedCodewords() { return correctedCodewords; }
    }
    public record Encoded(byte[] wire, int sourceBytes, int payloadBytes) {}

    /** Encode a normal (non-emergency) packet. */
    public static Encoded encode(UUID session, long sequence, String language, String text) throws IOException {
        return encode(session, sequence, language, text, false);
    }

    /** Encode a packet with optional emergency flag. */
    public static Encoded encode(UUID session, long sequence, String language, String text, boolean emergency) throws IOException {
        byte[] source = text.getBytes(StandardCharsets.UTF_8);
        if (source.length == 0 || source.length > MAX_TEXT || sequence < 1) throw new IOException("Invalid text size or sequence");
        int lang = switch(language) { case "hi" -> 1; case "en" -> 2; case "hinglish" -> 3; default -> throw new IOException("Unsupported language"); };
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream zip = new DeflaterOutputStream(compressed)) { zip.write(source); }
        boolean useZip = compressed.size() < source.length;
        byte[] payload = useZip ? compressed.toByteArray() : source;
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(body);
        out.writeInt(0x49545031); // ITP1
        out.writeLong(session.getMostSignificantBits()); out.writeLong(session.getLeastSignificantBits());
        out.writeLong(sequence); out.writeByte(lang); out.writeByte((useZip ? 1 : 0) | (emergency ? 0x80 : 0));
        out.writeInt(source.length); out.writeInt(payload.length); out.write(payload); out.flush();
        CRC32 crc = new CRC32(); crc.update(body.toByteArray()); out.writeInt((int)crc.getValue()); out.flush();
        byte[] raw = body.toByteArray(), fec = new byte[raw.length * 2];
        for (int i=0;i<raw.length;i++) { fec[2*i] = (byte)hammingEncode((raw[i]>>>4)&15); fec[2*i+1] = (byte)hammingEncode(raw[i]&15); }
        return new Encoded(fec, source.length, payload.length);
    }

    public static Decoded decode(byte[] wire) throws IOException {
        if (wire.length < 84 || wire.length > (MAX_TEXT + 42)*2 || wire.length % 2 != 0) throw new IOException("Invalid ITP length");
        byte[] raw = new byte[wire.length / 2]; int corrected = 0;
        for (int i=0;i<raw.length;i++) {
            int a=hammingDecode(wire[2*i]&255), b=hammingDecode(wire[2*i+1]&255);
            corrected += (a>>>4)+(b>>>4); raw[i]=(byte)(((a&15)<<4)|(b&15));
        }
        CRC32 crc = new CRC32(); crc.update(raw,0,raw.length-4);
        if ((int)crc.getValue() != ByteBuffer.wrap(raw,raw.length-4,4).getInt()) throw new IOException("CRC failure");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        if (in.readInt()!=0x49545031) throw new IOException("Unsupported ITP version");
        UUID session=new UUID(in.readLong(),in.readLong()); long sequence=in.readLong();
        String language=switch(in.readUnsignedByte()) {case 1->"hi";case 2->"en";case 3->"hinglish";default->throw new IOException("Invalid language");};
        int codecRaw=in.readUnsignedByte();
        boolean emergency = (codecRaw & 0x80) != 0;
        int codec = codecRaw & 0x7F;
        int sourceLength=in.readInt(), length=in.readInt();
        if(sequence<1 || codec>1 || sourceLength<1 || sourceLength>MAX_TEXT || length<1 || length!=raw.length-42) throw new IOException("Invalid ITP metadata");
        byte[] payload=new byte[length]; in.readFully(payload);
        byte[] source=payload;
        if(codec==1) {
            ByteArrayOutputStream inflated=new ByteArrayOutputStream();
            try(InflaterInputStream zip=new InflaterInputStream(new ByteArrayInputStream(payload))) {
                byte[] buffer=new byte[512]; int n;
                while((n=zip.read(buffer))!=-1) {if(inflated.size()+n>MAX_TEXT) throw new IOException("Oversized decompression"); inflated.write(buffer,0,n);}
            }
            source=inflated.toByteArray();
        }
        if(source.length!=sourceLength) throw new IOException("Source length mismatch");
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(source)).toString();
        return new Decoded(session,sequence,language,text,emergency,corrected,source.length,payload.length);
    }

    static int hammingEncode(int n) {
        int c=((n&1)<<2)|((n&2)<<3)|((n&4)<<3)|((n&8)<<3);
        c|=(Integer.bitCount(c&0x54)&1); c|=(Integer.bitCount(c&0x64)&1)<<1; c|=(Integer.bitCount(c&0x70)&1)<<3;
        return c|((Integer.bitCount(c)&1)<<7);
    }
    static int hammingDecode(int c) throws IOException {
        int syndrome=(Integer.bitCount(c&0x55)&1)|((Integer.bitCount(c&0x66)&1)<<1)|((Integer.bitCount(c&0x78)&1)<<2);
        boolean odd=(Integer.bitCount(c)&1)!=0; int repaired=0;
        if(syndrome!=0 && !odd) throw new IOException("Uncorrectable FEC codeword");
        if(odd) {c^=1<<(syndrome==0?7:syndrome-1); repaired=16;}
        return repaired|((c>>>2)&1)|((c>>>3)&2)|((c>>>3)&4)|((c>>>3)&8);
    }
}
