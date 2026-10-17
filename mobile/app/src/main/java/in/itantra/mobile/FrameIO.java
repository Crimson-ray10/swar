package in.itantra.mobile;
import java.io.*;

/** Bounded stream framing shared by the phone transport and loopback integration tests. */
public final class FrameIO {
    public static byte[] read(DataInputStream input)throws IOException{
        int length=input.readInt();if(length<2||length>32768)throw new IOException("Invalid frame length");
        byte[] frame=new byte[length];input.readFully(frame);return frame;
    }
    public static void write(DataOutputStream output,byte kind,byte[] payload)throws IOException{
        if((kind!=1&&kind!=2)||payload.length<1||payload.length+1>32768)throw new IOException("Invalid frame");
        output.writeInt(payload.length+1);output.writeByte(kind);output.write(payload);output.flush();
    }
}
