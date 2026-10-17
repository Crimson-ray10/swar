import { EventEmitter } from 'node:events';

/**
 * Voice Bridge FrameCodec
 * Handles 4-byte big-endian framing to prevent TCP packet boundary / stream fragmentation issues.
 * Frame structure:
 * [4-byte Big-Endian Length (N)] + [N bytes JSON/Binary Payload]
 */
export class FrameCodec {
  /**
   * Encodes a payload into a framed Buffer
   */
  public static encode(data: Buffer | string | object): Buffer {
    let payloadBuf: Buffer;
    if (Buffer.isBuffer(data)) {
      payloadBuf = data;
    } else if (typeof data === 'string') {
      payloadBuf = Buffer.from(data, 'utf-8');
    } else {
      payloadBuf = Buffer.from(JSON.stringify(data), 'utf-8');
    }

    const frameHeader = Buffer.allocUnsafe(4);
    frameHeader.writeUInt32BE(payloadBuf.length, 0);

    return Buffer.concat([frameHeader, payloadBuf]);
  }
}

/**
 * Stream frame decoder that buffers incoming chunks and yields complete messages.
 */
export class FrameDecoder extends EventEmitter {
  private buffer: Buffer = Buffer.alloc(0);
  private expectedLength: number | null = null;

  /**
   * Push newly received chunk from socket / stream
   */
  public push(chunk: Buffer): void {
    this.buffer = Buffer.concat([this.buffer, chunk]);
    this.process();
  }

  private process(): void {
    while (true) {
      if (this.expectedLength === null) {
        if (this.buffer.length < 4) {
          // Need at least 4 bytes for length header
          return;
        }
        this.expectedLength = this.buffer.readUInt32BE(0);
        this.buffer = this.buffer.subarray(4);
      }

      if (this.buffer.length >= this.expectedLength) {
        const payload = this.buffer.subarray(0, this.expectedLength);
        this.buffer = this.buffer.subarray(this.expectedLength);
        const length = this.expectedLength;
        this.expectedLength = null;

        try {
          const parsed = JSON.parse(payload.toString('utf-8'));
          this.emit('message', parsed, length);
        } catch {
          this.emit('raw', payload, length);
        }
      } else {
        // Awaiting more bytes for this complete frame
        return;
      }
    }
  }

  public reset(): void {
    this.buffer = Buffer.alloc(0);
    this.expectedLength = null;
  }
}
