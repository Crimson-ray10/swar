import { EventEmitter } from 'node:events';
import { NsdDiscovery } from '../discovery/NsdDiscovery.js';
import { FrameCodec } from '../protocol/FrameCodec.js';
import { HandshakeManager } from '../protocol/Handshake.js';
import { MessageStore } from '../reliability/MessageStore.js';
import { ReliabilityLayer } from '../reliability/ReliabilityLayer.js';
import { BluetoothTransport } from '../transports/BluetoothTransport.js';
import { VoiceTransport } from '../transports/VoiceTransport.js';
import { WifiDirectTransport } from '../transports/WifiDirectTransport.js';
import { WifiLanTransport } from '../transports/WifiLanTransport.js';
import {
  AckPayload,
  ConnectionState,
  DeviceInfo,
  Peer,
  SupervisorStatus,
  TransportType,
  VoiceMessage,
  VoiceMode,
} from '../types.js';

export const TRANSPORT_PRIORITY: TransportType[] = ['wifi_direct', 'wifi_lan', 'bluetooth'];

/**
 * ConnectivitySupervisor
 * Coordinates transports, heartbeat, state machine, and automatic failover recovery.
 */
export class ConnectivitySupervisor extends EventEmitter {
  private state: ConnectionState = 'DISCONNECTED';
  private activeTransportType: TransportType | null = null;
  private transports: Map<TransportType, VoiceTransport> = new Map();
  private discovery: NsdDiscovery;
  private messageStore: MessageStore;
  private reliabilityLayer: ReliabilityLayer;
  private handshakeManager: HandshakeManager;
  private selectedPeer: Peer | null = null;
  private voiceMode: VoiceMode = 'continuous';

  private heartbeatInterval: NodeJS.Timeout | null = null;
  private pingMs: number = 0;
  private stats = {
    messagesSent: 0,
    messagesReceived: 0,
    acksReceived: 0,
    retries: 0,
    drops: 0,
    wireBits: 0,
    rawPcmBitsEquivalent: 0,
  };

  constructor(
    localDeviceInfo: DeviceInfo,
    wifiLan: WifiLanTransport,
    wifiDirect: WifiDirectTransport,
    bluetooth: BluetoothTransport,
    discovery: NsdDiscovery,
    messageStore: MessageStore,
    reliabilityLayer: ReliabilityLayer
  ) {
    super();
    this.discovery = discovery;
    this.messageStore = messageStore;
    this.reliabilityLayer = reliabilityLayer;
    this.handshakeManager = new HandshakeManager(localDeviceInfo);

    this.transports.set('wifi_lan', wifiLan);
    this.transports.set('wifi_direct', wifiDirect);
    this.transports.set('bluetooth', bluetooth);

    this.setupTransportListeners();
    this.setupReliabilityListeners();
  }

  private setupTransportListeners(): void {
    for (const [type, transport] of this.transports.entries()) {
      transport.on('error', (_err) => {
        if (this.activeTransportType === type) {
          this.handleActiveTransportDrop(type);
        }
      });

      transport.on('message', (msg: any, byteLength: number) => {
        this.handleIncomingTransportMessage(type, msg, byteLength);
      });

      transport.on('stateChanged', (connState: ConnectionState) => {
        if (connState === 'CONNECTED' && this.state !== 'CONNECTED') {
          this.activeTransportType = type;
          this.setState('CONNECTED');
          this.startHeartbeat();
        } else if (this.activeTransportType === type) {
          if (connState === 'DISCONNECTED' || connState === 'DEGRADED') {
            this.handleActiveTransportDrop(type);
          }
        }
      });
    }
  }

  private setupReliabilityListeners(): void {
    this.reliabilityLayer.on('messageAcked', ({ messageId }) => {
      this.stats.acksReceived++;
      this.emit('ackReceived', messageId);
    });

    this.reliabilityLayer.on('messageRetrying', ({ attempt }) => {
      this.stats.retries++;
    });

    this.reliabilityLayer.on('transportFallbackRequired', (msg: VoiceMessage) => {
      this.triggerFallback(msg);
    });
  }

  private handleIncomingTransportMessage(type: TransportType, data: any, byteLength: number): void {
    if (this.activeTransportType !== type) {
      // Auto-lock to active incoming transport
      this.activeTransportType = type;
      this.setState('CONNECTED');
    }

    this.stats.wireBits += byteLength * 8;

    // 1. Handshake handling
    if (data.type === 'HELLO' || data.type === 'HELLO_ACK' || data.type === 'DEVICE_INFO' || data.type === 'READY') {
      const resp = this.handshakeManager.handleMessage(data);
      if (resp) {
        this.getActiveTransport()?.send(resp);
      }
      if (this.handshakeManager.isReady()) {
        this.setState('CONNECTED');
        this.emit('handshakeComplete', this.handshakeManager.getPeerInfo());
      }
      return;
    }

    // 2. ACK handling
    if (data.type === 'ACK') {
      this.reliabilityLayer.handleAck(data as AckPayload);
      return;
    }

    // 3. VoiceMessage handling
    if (data.messageId && data.text) {
      const voiceMessage = data as VoiceMessage;
      this.stats.messagesReceived++;

      // Deduplication check
      if (this.reliabilityLayer.isDuplicate(voiceMessage)) {
        this.emit('duplicateMessageIgnored', voiceMessage.messageId);
        return;
      }

      // Automatically send ACK back
      const ack = this.reliabilityLayer.createAck(voiceMessage);
      this.getActiveTransport()?.send(ack);

      // Equivalent raw PCM bits: ~75ms audio per char @ 16kHz 16-bit mono
      const approxDurationSec = Math.max(0.6, voiceMessage.text.length * 0.08);
      this.stats.rawPcmBitsEquivalent += Math.round(approxDurationSec * 16000 * 16);

      this.emit('voiceMessageReceived', voiceMessage);
    }
  }

  public getActiveTransport(): VoiceTransport | null {
    if (!this.activeTransportType) return null;
    return this.transports.get(this.activeTransportType) || null;
  }

  public getState(): ConnectionState {
    return this.state;
  }

  private setState(newState: ConnectionState): void {
    if (this.state !== newState) {
      this.state = newState;
      this.emit('stateChanged', newState);
    }
  }

  public setVoiceMode(mode: VoiceMode): void {
    this.voiceMode = mode;
    this.emit('modeChanged', mode);
  }

  public getStatus(): SupervisorStatus {
    const avail: Record<TransportType, boolean> = {
      wifi_direct: true,
      wifi_lan: true,
      bluetooth: true,
    };

    return {
      state: this.state,
      activeTransport: this.activeTransportType,
      availableTransports: avail,
      selectedPeer: this.selectedPeer,
      mode: this.voiceMode,
      stats: {
        pingMs: this.pingMs,
        ...this.stats,
      },
    };
  }

  /**
   * Connect to a selected peer using priority order
   */
  public async connectToPeer(peer: Peer, preferredTransport?: TransportType): Promise<boolean> {
    this.selectedPeer = peer;
    this.setState('CONNECTING');

    const priorityList = preferredTransport
      ? [preferredTransport, ...TRANSPORT_PRIORITY.filter((t) => t !== preferredTransport)]
      : TRANSPORT_PRIORITY;

    for (const transportType of priorityList) {
      const transport = this.transports.get(transportType);
      if (!transport) continue;

      const targetPort =
        peer.ports?.[transportType] ||
        (transportType === 'wifi_lan'
          ? (peer.port || 8988)
          : transportType === 'wifi_direct'
          ? (peer.ports?.wifi_direct || 8990)
          : (peer.ports?.bluetooth || 8992));

      const peerForTransport: Peer = {
        ...peer,
        port: targetPort,
        transport: transportType,
      };

      const success = await transport.connect(peerForTransport);
      if (success) {
        this.activeTransportType = transportType;
        this.setState('HANDSHAKING');

        // Start handshake
        const hello = this.handshakeManager.createHello();
        await transport.send(hello);

        this.startHeartbeat();
        this.setState('CONNECTED');
        return true;
      }
    }

    this.setState('DISCONNECTED');
    return false;
  }

  /**
   * Send VoiceMessage over active transport with reliability tracking
   */
  public async sendVoiceMessage(message: VoiceMessage): Promise<boolean> {
    const transport = this.getActiveTransport();
    if (!transport || this.state !== 'CONNECTED') {
      // Persist in store for transmission once reconnected
      this.messageStore.saveMessage(message);
      this.emit('messageBufferedOffline', message);
      return false;
    }

    this.stats.messagesSent++;
    const wireBytes = FrameCodec.encode(message).length;
    this.stats.wireBits += wireBytes * 8;

    this.reliabilityLayer.trackSentMessage(message, async (msg) => {
      const active = this.getActiveTransport();
      return active ? active.send(msg) : false;
    });

    return transport.send(message);
  }

  private async handleActiveTransportDrop(droppedType: TransportType): Promise<void> {
    this.stats.drops++;
    this.setState('RECONNECTING');
    this.emit('transportDropped', droppedType);

    // Try fallback transports in priority order
    const remaining = TRANSPORT_PRIORITY.filter((t) => t !== droppedType);
    for (const nextType of remaining) {
      const transport = this.transports.get(nextType);
      if (transport && this.selectedPeer) {
        const ok = await transport.connect(this.selectedPeer);
        if (ok) {
          this.activeTransportType = nextType;
          this.setState('CONNECTED');
          this.emit('transportRecovered', nextType);

          // Flush any pending un-ACKed messages
          await this.flushPendingMessages();
          return;
        }
      }
    }

    this.setState('DISCONNECTED');
  }

  private async triggerFallback(failedMessage: VoiceMessage): Promise<void> {
    if (this.activeTransportType) {
      await this.handleActiveTransportDrop(this.activeTransportType);
    }
  }

  private async flushPendingMessages(): Promise<void> {
    const pending = this.messageStore.getPendingMessages();
    for (const item of pending) {
      await this.sendVoiceMessage(item.message);
    }
  }

  private startHeartbeat(): void {
    if (this.heartbeatInterval) clearInterval(this.heartbeatInterval);

    this.heartbeatInterval = setInterval(async () => {
      const transport = this.getActiveTransport();
      if (transport && this.state === 'CONNECTED') {
        const ping = await transport.ping();
        if (ping >= 0) {
          this.pingMs = ping;
        } else {
          // Heartbeat failed
          this.handleActiveTransportDrop(this.activeTransportType!);
        }
      }
    }, 1000);
  }

  public async disconnect(): Promise<void> {
    if (this.heartbeatInterval) {
      clearInterval(this.heartbeatInterval);
      this.heartbeatInterval = null;
    }

    const transport = this.getActiveTransport();
    if (transport) {
      await transport.disconnect();
    }

    this.activeTransportType = null;
    this.handshakeManager.reset();
    this.reliabilityLayer.clear();
    this.setState('DISCONNECTED');
  }
}
