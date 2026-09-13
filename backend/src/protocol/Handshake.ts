import { DeviceInfo, HandshakeMessage } from '../types.js';

export type HandshakeStage = 'IDLE' | 'SENT_HELLO' | 'RECEIVED_HELLO' | 'EXCHANGING_INFO' | 'READY';

/**
 * Manages the Voice Bridge connection handshake:
 * HELLO -> HELLO_ACK -> DEVICE_INFO -> READY
 */
export class HandshakeManager {
  private stage: HandshakeStage = 'IDLE';
  private localInfo: DeviceInfo;
  private peerInfo: DeviceInfo | null = null;

  constructor(localInfo: DeviceInfo) {
    this.localInfo = localInfo;
  }

  public getStage(): HandshakeStage {
    return this.stage;
  }

  public getPeerInfo(): DeviceInfo | null {
    return this.peerInfo;
  }

  public isReady(): boolean {
    return this.stage === 'READY';
  }

  public createHello(): HandshakeMessage {
    this.stage = 'SENT_HELLO';
    return {
      type: 'HELLO',
      payload: {
        protocolVersion: this.localInfo.protocolVersion,
        timestamp: Date.now(),
      },
    };
  }

  public createHelloAck(): HandshakeMessage {
    return {
      type: 'HELLO_ACK',
      payload: {
        protocolVersion: this.localInfo.protocolVersion,
        timestamp: Date.now(),
      },
    };
  }

  public createDeviceInfo(): HandshakeMessage {
    return {
      type: 'DEVICE_INFO',
      payload: this.localInfo,
    };
  }

  public createReady(): HandshakeMessage {
    this.stage = 'READY';
    return {
      type: 'READY',
      payload: { timestamp: Date.now() },
    };
  }

  /**
   * Processes incoming handshake messages
   * Returns response message if one should be sent, or null
   */
  public handleMessage(msg: HandshakeMessage): HandshakeMessage | null {
    switch (msg.type) {
      case 'HELLO':
        this.stage = 'RECEIVED_HELLO';
        return this.createHelloAck();

      case 'HELLO_ACK':
        this.stage = 'EXCHANGING_INFO';
        return this.createDeviceInfo();

      case 'DEVICE_INFO':
        if (msg.payload) {
          this.peerInfo = msg.payload as DeviceInfo;
        }
        if (this.stage === 'RECEIVED_HELLO') {
          this.stage = 'EXCHANGING_INFO';
          return this.createDeviceInfo();
        }
        this.stage = 'READY';
        return this.createReady();

      case 'READY':
        this.stage = 'READY';
        return null;

      default:
        return null;
    }
  }

  public reset(): void {
    this.stage = 'IDLE';
    this.peerInfo = null;
  }
}
