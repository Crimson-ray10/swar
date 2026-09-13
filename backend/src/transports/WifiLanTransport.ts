import { EventEmitter } from 'node:events';
import net from 'node:net';
import { FrameCodec, FrameDecoder } from '../protocol/FrameCodec.js';
import { ConnectionState, Peer, TransportType, VoiceMessage } from '../types.js';
import { VoiceTransport } from './VoiceTransport.js';

/**
 * WifiLanTransport
 * Real TCP socket implementation for local Wi-Fi / LAN communication.
 * Default port: 8988. Uses 4-byte big-endian framing.
 */
export class WifiLanTransport extends EventEmitter implements VoiceTransport {
  public readonly transportType: TransportType = 'wifi_lan';
  private port: number;
  private state: ConnectionState = 'DISCONNECTED';
  private server: net.Server | null = null;
  private activeSocket: net.Socket | null = null;
  private decoder: FrameDecoder = new FrameDecoder();
  private pingResolve: ((latencyMs: number) => void) | null = null;
  private pingStartTime: number = 0;

  constructor(port: number = 8988) {
    super();
    this.port = port;
    this.setupDecoder();
  }

  private setupDecoder(): void {
    this.decoder.on('message', (data: any, length: number) => {
      if (data.type === 'PING') {
        // Automatically respond with PONG
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

  /**
   * Start local TCP listener server
   */
  public async startServer(): Promise<void> {
    if (this.server) return;

    return new Promise((resolve) => {
      this.server = net.createServer((socket) => {
        this.handleIncomingConnection(socket);
      });

      this.server.on('error', (err) => {
        this.emit('error', err);
      });

      this.server.listen(this.port, '0.0.0.0', () => {
        resolve();
      });
    });
  }

  private handleIncomingConnection(socket: net.Socket): void {
    if (this.activeSocket) {
      this.activeSocket.destroy();
    }

    this.activeSocket = socket;
    this.setState('CONNECTING');

    socket.on('data', (chunk) => {
      this.decoder.push(chunk);
    });

    socket.on('close', () => {
      this.activeSocket = null;
      this.setState('DISCONNECTED');
    });

    socket.on('error', (err) => {
      this.emit('error', err);
      this.setState('DEGRADED');
    });

    this.setState('CONNECTED');
  }

  /**
   * Connect to peer over TCP
   */
  public async connect(peer: Peer): Promise<boolean> {
    this.setState('CONNECTING');
    const targetPort = peer.port || this.port;

    return new Promise((resolve) => {
      const socket = net.createConnection({ host: peer.address, port: targetPort }, () => {
        this.activeSocket = socket;
        this.setState('CONNECTED');
        resolve(true);
      });

      socket.on('data', (chunk) => {
        this.decoder.push(chunk);
      });

      socket.on('close', () => {
        this.activeSocket = null;
        this.setState('DISCONNECTED');
      });

      socket.on('error', (err) => {
        this.emit('error', err);
        this.setState('DISCONNECTED');
        resolve(false);
      });

      socket.setTimeout(3500, () => {
        socket.destroy();
        this.setState('DISCONNECTED');
        resolve(false);
      });
    });
  }

  public async disconnect(): Promise<void> {
    if (this.activeSocket) {
      this.activeSocket.destroy();
      this.activeSocket = null;
    }
    this.setState('DISCONNECTED');
  }

  public async send(message: VoiceMessage | object): Promise<boolean> {
    if (!this.activeSocket || this.activeSocket.destroyed) {
      return false;
    }

    const frame = FrameCodec.encode(message);

    return new Promise((resolve) => {
      this.activeSocket?.write(frame, (err) => {
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
    if (!this.activeSocket || this.activeSocket.destroyed) {
      return -1;
    }

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

  public async closeServer(): Promise<void> {
    await this.disconnect();
    if (this.server) {
      this.server.close();
      this.server = null;
    }
  }
}
