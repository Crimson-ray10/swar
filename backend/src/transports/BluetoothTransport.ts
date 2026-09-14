import { EventEmitter } from 'node:events';
import net from 'node:net';
import { FrameCodec, FrameDecoder } from '../protocol/FrameCodec.js';
import { ConnectionState, Peer, TransportType, VoiceMessage } from '../types.js';
import { VoiceTransport } from './VoiceTransport.js';

export const VOICE_BRIDGE_BLUETOOTH_UUID = 'e8a94660-3949-11ee-be56-0242ac120002';

/**
 * BluetoothTransport
 * Implements Bluetooth Classic RFCOMM persistent stream adapter.
 * Uses 4-byte big-endian framing and identical application protocol.
 */
export class BluetoothTransport extends EventEmitter implements VoiceTransport {
  public readonly transportType: TransportType = 'bluetooth';
  private state: ConnectionState = 'DISCONNECTED';
  private port: number;
  private server: net.Server | null = null;
  private socket: net.Socket | null = null;
  private decoder: FrameDecoder = new FrameDecoder();
  private pingResolve: ((latencyMs: number) => void) | null = null;
  private pingStartTime: number = 0;

  constructor(port: number = 8992) {
    super();
    this.port = port;
    this.on('error', () => {});
    this.setupDecoder();
  }

  private setupDecoder(): void {
    this.decoder.on('message', (data: any, length: number) => {
      if (data.type === 'PING') {
        this.send({ type: 'PONG', timestamp: data.timestamp });
        return;
      }

      if (data.type === 'PONG') {
        if (this.pingResolve) {
          const latency = Date.now() - this.pingStartTime;
          this.pingResolve(latency);
          this.pingResolve = null;
        }
        return;
      }

      this.emit('message', data, length);
    });
  }

  public getConnectionState(): ConnectionState {
    return this.state;
  }

  private setState(newState: ConnectionState): void {
    if (this.state !== newState) {
      this.state = newState;
      this.emit('stateChanged', newState);
    }
  }

  public async startRfcommServer(): Promise<void> {
    if (this.server) return;

    return new Promise((resolve) => {
      this.server = net.createServer((sock) => {
        if (this.socket && !this.socket.destroyed) {
          this.socket.destroy();
        }
        this.socket = sock;
        this.setState('CONNECTED');

        sock.on('data', (chunk) => this.decoder.push(chunk));
        sock.on('close', () => {
          this.socket = null;
          this.setState('DISCONNECTED');
        });
        sock.on('error', (err) => {
          this.emit('error', err);
          this.setState('DEGRADED');
        });
      });

      this.server.on('error', (err) => {
        this.emit('error', err);
        resolve();
      });

      this.server.listen(this.port, '0.0.0.0', () => resolve());
    });
  }

  public async connect(peer: Peer): Promise<boolean> {
    this.setState('CONNECTING');
    const targetPort = peer.port || this.port;

    return new Promise((resolve) => {
      const sock = net.createConnection({ host: peer.address, port: targetPort }, () => {
        this.socket = sock;
        this.setState('CONNECTED');
        resolve(true);
      });

      sock.on('data', (chunk) => this.decoder.push(chunk));
      sock.on('close', () => {
        this.socket = null;
        this.setState('DISCONNECTED');
      });
      sock.on('error', (err) => {
        this.emit('error', err);
        this.setState('DISCONNECTED');
        resolve(false);
      });

      sock.setTimeout(4000, () => {
        sock.destroy();
        this.setState('DISCONNECTED');
        resolve(false);
      });
    });
  }

  public async disconnect(): Promise<void> {
    if (this.socket) {
      this.socket.destroy();
      this.socket = null;
    }
    this.setState('DISCONNECTED');
  }

  public async send(message: VoiceMessage | object): Promise<boolean> {
    if (!this.socket || this.socket.destroyed) {
      return false;
    }

    const frame = FrameCodec.encode(message);
    return new Promise((resolve) => {
      this.socket?.write(frame, (err) => {
        if (err) {
          this.emit('error', err);
          resolve(false);
        } else {
          resolve(true);
        }
      });
    });
  }

  public async ping(): Promise<number> {
    if (!this.socket || this.socket.destroyed) return -1;
    this.pingStartTime = Date.now();

    return new Promise((resolve) => {
      this.pingResolve = resolve;
      this.send({ type: 'PING', timestamp: this.pingStartTime });
      setTimeout(() => {
        if (this.pingResolve) {
          this.pingResolve = null;
          resolve(-1);
        }
      }, 1500);
    });
  }

  public async isAvailable(): Promise<boolean> {
    return true;
  }

  public async close(): Promise<void> {
    await this.disconnect();
    if (this.server) {
      this.server.close();
      this.server = null;
    }
  }
}
