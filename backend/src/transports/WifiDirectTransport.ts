import { EventEmitter } from 'node:events';
import net from 'node:net';
import { FrameCodec, FrameDecoder } from '../protocol/FrameCodec.js';
import { ConnectionState, Peer, TransportType, VoiceMessage } from '../types.js';
import { VoiceTransport } from './VoiceTransport.js';

/**
 * WifiDirectTransport
 * Simulates and drives Wi-Fi Direct P2P TCP socket networking.
 * Creates direct P2P socket connection with 4-byte framing and VoiceBridge protocol.
 */
export class WifiDirectTransport extends EventEmitter implements VoiceTransport {
  public readonly transportType: TransportType = 'wifi_direct';
  private port: number;
  private state: ConnectionState = 'DISCONNECTED';
  private server: net.Server | null = null;
  private socket: net.Socket | null = null;
  private decoder: FrameDecoder = new FrameDecoder();
  private pingResolve: ((latencyMs: number) => void) | null = null;
  private pingStartTime: number = 0;
  private isP2pGroupOwner: boolean = false;

  constructor(port: number = 8990) {
    super();
    this.port = port;
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

  public async startP2pGroup(isGroupOwner: boolean = true): Promise<void> {
    this.isP2pGroupOwner = isGroupOwner;
    if (isGroupOwner && !this.server) {
      return new Promise((resolve) => {
        this.server = net.createServer((sock) => {
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

        this.server.listen(this.port, '0.0.0.0', () => resolve());
      });
    }
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

      sock.setTimeout(3000, () => {
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
      }, 1200);
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
