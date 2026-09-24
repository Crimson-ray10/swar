package in.itantra.mobile;

/** A laboratory profile, not a measured handset bitrate/range claim. Both peers must match. */
public record AcousticConfig(int sampleRate, double frequency0, double frequency1,
        int samplesPerSymbol, double amplitude, int preambleBits, int syncWord,
        int maxPayload, int silenceGapMs, int maxRetries, int ackTimeoutMs, int packetTimeoutMs) {
    public static AcousticConfig robust() {
        return new AcousticConfig(16000, 2000, 3000, 160, .25, 64, 0x1ACFFC1D, 256, 250, 3, 20000, 60000);
    }
    public AcousticConfig {
        if (sampleRate < 8000 || sampleRate > 48000 || samplesPerSymbol < 16 || samplesPerSymbol > 480
                || frequency0 <= 0 || frequency1 <= frequency0 || frequency1 >= sampleRate / 2.0
                || amplitude <= 0 || amplitude > .8 || preambleBits < 32 || preambleBits > 128
                || maxPayload < 32 || maxPayload > 512 || silenceGapMs < 100 || silenceGapMs > 2000
                || maxRetries < 0 || maxRetries > 8 || ackTimeoutMs < 3000 || packetTimeoutMs < 1000)
            throw new IllegalArgumentException("Invalid acoustic configuration");
        // Orthogonal integer-cycle tones make noncoherent detection practical at this symbol rate.
        for (double f : new double[]{frequency0, frequency1}) {
            double cycles = f * samplesPerSymbol / sampleRate;
            if (Math.abs(cycles - Math.rint(cycles)) > 1e-6) throw new IllegalArgumentException("Use integer-cycle FSK tones");
        }
    }
    public double rawBitrate() { return sampleRate / (double)samplesPerSymbol; }
    public double bandwidthHz() { return frequency1 - frequency0 + 2 * rawBitrate(); }
    public String fecScheme() { return "Hamming(8,4)-SECDED"; }
    public String crcScheme() { return "CRC32"; }
    public int maxWireBytes() { return (AcousticFrame.HEADER_BYTES + maxPayload + 4) * 2; }
    public long airtimeMs(int wireBytes) { return silenceGapMs * 2L + Math.round((preambleBits + 32 + 32 + wireBytes * 8L) * 1000 / rawBitrate()); }
}
