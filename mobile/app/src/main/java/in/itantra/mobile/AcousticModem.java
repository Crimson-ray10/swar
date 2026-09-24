package in.itantra.mobile;

import java.io.*;
import java.util.Arrays;

/** Bounded streaming noncoherent BFSK modem. No Android, socket or semantic dependencies. */
public final class AcousticModem {
    public interface Listener { void event(String name); void packet(byte[] wire); }
    private final AcousticConfig c;
    private final Listener listener;
    private final double[][] sin,cos;
    private final double[] window;
    private final float[] buffer;
    private int size,scan,position,bitIndex,wireLength=-1;
    private byte[] wire;
    private boolean synced;
    private int lengthBits;
    private double dc,qualitySum;
    private int qualityCount;
    private long samplesSinceSync;
    private volatile int bestPreamble;
    public AcousticModem(AcousticConfig config,Listener listener) {
        c=config;this.listener=listener;
        sin=new double[2][c.samplesPerSymbol()];cos=new double[2][c.samplesPerSymbol()];
        window=new double[c.samplesPerSymbol()];
        int N=window.length,guard=6,taper=8;
        for(int n=0;n<N;n++){
            if(n<guard||n>=N-guard)window[n]=0;
            else if(n<guard+taper)window[n]=.5*(1-Math.cos(Math.PI*(n-guard)/taper));
            else if(n<=N-guard-taper)window[n]=1.0;
            else window[n]=.5*(1-Math.cos(Math.PI*(N-guard-n)/taper));
        }
        for(int k=0;k<2;k++)for(int n=0;n<c.samplesPerSymbol();n++) {
            double phase=2*Math.PI*(k==0?c.frequency0():c.frequency1())*n/c.sampleRate();
            sin[k][n]=Math.sin(phase);cos[k][n]=Math.cos(phase);
        }
        buffer=new float[(c.preambleBits()+64)*c.samplesPerSymbol()+4096];
    }
    public short[] modulate(byte[] wire) throws IOException {
        if(wire.length<1||wire.length>c.maxWireBytes())throw new IOException("Modem packet size");
        wire=interleave(wire,false);
        // A protected 16-bit wire length follows sync (4 SECDED bytes / 32 bits).
        byte[] length=AcousticFrame.protect(new byte[]{(byte)(wire.length>>>8),(byte)wire.length});
        int symbols=c.preambleBits()+32+32+wire.length*8;
        int gap=c.sampleRate()*c.silenceGapMs()/1000;
        short[] pcm=new short[gap*2+symbols*c.samplesPerSymbol()];
        int offset=gap;
        for(int i=0;i<symbols;i++) {
            int bit;
            if(i<c.preambleBits())bit=i&1;
            else if(i<c.preambleBits()+32)bit=(c.syncWord()>>>(31-(i-c.preambleBits())))&1;
            else {int j=i-c.preambleBits()-32;byte[] data=j<32?length:wire;if(j>=32)j-=32;bit=(data[j/8]>>>(7-j%8))&1;}
            for(int n=0;n<c.samplesPerSymbol();n++)pcm[offset++]=(short)Math.round(32767*c.amplitude()*sin[bit][n]);
        }
        return pcm;
    }
    /** Spread short acoustic bursts over distinct Hamming codewords (8-byte transpose blocks). */
    private static byte[] interleave(byte[] input,boolean inverse){
        byte[] output=new byte[input.length];
        for(int start=0;start<input.length;start+=8){int count=Math.min(8,input.length-start);
            for(int j=0;j<count*8;j++){
                int plainByte=j%count,plainBit=j/count;
                int sourceByte=inverse?j/8:plainByte,sourceBit=inverse?j%8:plainBit;
                int targetByte=inverse?plainByte:j/8,targetBit=inverse?plainBit:j%8;
                output[start+targetByte]|=(byte)(((input[start+sourceByte]>>>(7-sourceBit))&1)<<(7-targetBit));
            }
        }
        return output;
    }
    /** Called with arbitrary PCM chunk boundaries; processing/memory do not grow with stream length. */
    public void accept(short[] pcm,int length) {
        for(int i=0;i<length;i++) {
            if(size==buffer.length) { process(); compact(); if(size==buffer.length){reset();listener.event("TIMEOUT");} }
            // Slow DC blocker; matched frequency correlators provide the receive band selection.
            double x=pcm[i]/32768.0;dc += .001*(x-dc);buffer[size++]=(float)(x-dc);
        }
        process();compact();
    }
    private double confidence;
    private int bit(int offset) {
        double p0=0,p1=0,energy=0;
        for(int k=0;k<2;k++) {
            double a=0,b=0;
            for(int n=0;n<c.samplesPerSymbol();n++){double v=buffer[offset+n];a+=v*cos[k][n]*window[n];b+=v*sin[k][n]*window[n];if(k==0)energy+=v*v;}
            double p=a*a+b*b;if(k==0)p0=p;else p1=p;
        }
        confidence=Math.abs(p1-p0)/(p0+p1+1e-12);
        if(energy/c.samplesPerSymbol()<1e-8)confidence=0;
        return p1>p0?1:0;
    }
    private void process() {
        // Acquire on the last 32 alternating symbols, allowing the beginning to be lost during turnaround.
        int acquisitionBits=32;
        int n=c.samplesPerSymbol(),syncSamples=(acquisitionBits+32)*n;
        while(true) {
            if(!synced) {
                boolean found=false;
                while(scan+syncSamples<=size) {
                    // Reject most speech/noise with an inexpensive early preamble check.
                    int errors=0;double q=0;
                    int examined=0;
                    for(int i=0;i<acquisitionBits;i++) {examined++;if(bit(scan+i*n)!=(i&1)||confidence<.2)errors++;q+=confidence;if(errors>6)break;}
                    bestPreamble=Math.max(bestPreamble,examined-errors);
                    if(errors<=6) {
                        int sync=0;for(int i=0;i<32;i++)sync=(sync<<1)|bit(scan+(acquisitionBits+i)*n);
                        if(Integer.bitCount(sync^c.syncWord())<=1) {
                            // Symmetrically fine-tune symbol timing phase against the known sync word.
                            int best=scan;double bestScore=-1e9;
                            for(int delta=-n/8;delta<=n/8 && scan+delta>=0 && scan+delta+syncSamples<=size;delta++) {
                                int testSync=0;double score=0;
                                for(int i=0;i<32;i++) {
                                    int b=bit(scan+delta+(acquisitionBits+i)*n);
                                    testSync=(testSync<<1)|b;
                                    if(b==((c.syncWord()>>>(31-i))&1))score+=confidence;
                                    else score-=confidence*2.0;
                                }
                                if(Integer.bitCount(testSync^c.syncWord())<=1 && score>bestScore) {
                                    bestScore=score;best=scan+delta;
                                }
                            }
                            position=best+syncSamples;synced=true;bitIndex=0;lengthBits=0;wireLength=-1;samplesSinceSync=0;
                            listener.event("SIGNAL_DETECTED");listener.event("SYNC_DETECTED");found=true;break;
                        }
                    }
                    scan+=Math.max(1,n/8);
                }
                if(!found)return;
            }
            int timingProbe=Math.max(2,n/8);
            while(synced&&position+n+timingProbe<=size) {
                int value=bit(position);double q=confidence;
                // Early/late comparison corrects small sampling-clock drift; only at transitions.
                if(position>=timingProbe) {
                    int early=bit(position-timingProbe);double eq=early==value?confidence:0;
                    int late=bit(position+timingProbe);double lq=late==value?confidence:0;
                    int step=Math.max(1,n/64);
                    if(eq>lq+.025&&eq>q-.01)position-=step;
                    else if(lq>eq+.025&&lq>q-.01)position+=step;
                    value=bit(position);q=confidence;
                }
                qualitySum+=q;qualityCount++;position+=n;samplesSinceSync+=n;
                if(wireLength<0) {
                    lengthBits=(lengthBits<<1)|value;
                    if(++bitIndex==32)try {
                        int a=ItpPacket.hammingDecode((lengthBits>>>24)&255)&15,b=ItpPacket.hammingDecode((lengthBits>>>16)&255)&15;
                        int d=ItpPacket.hammingDecode((lengthBits>>>8)&255)&15,e=ItpPacket.hammingDecode(lengthBits&255)&15;
                        wireLength=(a<<12)|(b<<8)|(d<<4)|e;
                        if(wireLength<(AcousticFrame.HEADER_BYTES+4)*2||wireLength>c.maxWireBytes())throw new IOException("Length");
                        wire=new byte[wireLength];bitIndex=0;
                    }catch(IOException ex){
                        listener.event("FEC_FAILURE_LENGTH:0x"+Integer.toHexString(lengthBits));
                        reject("FEC_FAILURE");
                    }
                } else {
                    wire[bitIndex/8]|=(byte)(value<<(7-bitIndex%8));
                    if(++bitIndex==wireLength*8){byte[] result=interleave(wire,true);Arrays.fill(wire,(byte)0);synced=false;scan=position;wire=null;listener.packet(result);Arrays.fill(result,(byte)0);}
                }
                if(synced&&samplesSinceSync*1000/c.sampleRate()>c.packetTimeoutMs()){
                    listener.event("TIMEOUT_RECV:bits="+bitIndex+"/"+(wireLength*8));
                    reject("TIMEOUT");
                }
            }
            if(synced)return;
        }
    }
    private void reject(String name){synced=false;scan=position;wire=null;listener.event(name);listener.event("PACKET_INVALID");}
    private void compact(){int consumed=synced?Math.max(0,position-c.samplesPerSymbol()/8):scan;if(consumed>0){System.arraycopy(buffer,consumed,buffer,0,size-consumed);Arrays.fill(buffer,size-consumed,size,0);size-=consumed;scan=Math.max(0,scan-consumed);position-=consumed;}}
    public boolean receiving(){return synced;}
    /** Tone-separation confidence, NOT a calibrated SNR estimate. */
    public double signalConfidence(){return qualityCount==0?0:qualitySum/qualityCount;}
    public int bestPreamble(){return bestPreamble;}
    public void reset(){Arrays.fill(buffer,0);if(wire!=null)Arrays.fill(wire,(byte)0);size=scan=position=bitIndex=0;synced=false;wire=null;dc=0;}
}
