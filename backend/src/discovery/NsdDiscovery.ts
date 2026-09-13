import { EventEmitter } from 'node:events';
import { Peer, TransportType } from '../types.js';

export const SERVICE_TYPE = '_voicebridge._tcp';
export const SERVICE_NAME = 'VoiceBridge';

/**
 * Network Service Discovery (NSD) for Voice Bridge
 * Advertises local device presence and discovers peers on the local network/P2P.
 */
export class NsdDiscovery extends EventEmitter {
  private peers: Map<string, Peer> = new Map();
  private deviceId: string;
  private port: number;
  private isAdvertising: boolean = false;
  private isDiscovering: boolean = false;
  private cleanupInterval: NodeJS.Timeout | null = null;

  constructor(deviceId: string, port: number = 8988) {
    super();
    this.deviceId = deviceId;
    this.port = port;
  }

  public async startAdvertising(): Promise<void> {
    this.isAdvertising = true;
    this.emit('advertisingStarted', {
      service: SERVICE_NAME,
      type: SERVICE_TYPE,
      port: this.port,
      deviceId: this.deviceId,
    });
  }

  public async startDiscovery(): Promise<void> {
    this.isDiscovering = true;
    this.emit('discoveryStarted');

    // Add local loopback peer for testing/simulation
    this.registerPeer({
      id: `peer_${this.deviceId}_loopback`,
      name: `Local VoiceBridge Node (${this.deviceId.substring(0, 6)})`,
      address: '127.0.0.1',
      port: this.port,
      transport: 'wifi_lan',
      lastSeen: Date.now(),
      deviceInfo: {
        protocolVersion: 1,
        deviceId: this.deviceId,
        language: 'hi',
        capabilities: { stt: true, tts: true, languages: 11 },
      },
    });

    // Cleanup stale peers every 15s
    if (!this.cleanupInterval) {
      this.cleanupInterval = setInterval(() => {
        const now = Date.now();
        for (const [id, peer] of this.peers.entries()) {
          if (now - peer.lastSeen > 35000 && !peer.id.includes('loopback')) {
            this.peers.delete(id);
            this.emit('peerLost', peer);
          }
        }
      }, 15000);
    }
  }

  public registerPeer(peer: Peer): void {
    const existing = this.peers.get(peer.id);
    this.peers.set(peer.id, { ...peer, lastSeen: Date.now() });

    if (!existing) {
      this.emit('peerFound', peer);
    } else {
      this.emit('peerUpdated', peer);
    }
  }

  public getDiscoveredPeers(transport?: TransportType): Peer[] {
    const all = Array.from(this.peers.values());
    if (transport) {
      return all.filter((p) => p.transport === transport);
    }
    return all;
  }

  public stop(): void {
    this.isAdvertising = false;
    this.isDiscovering = false;
    if (this.cleanupInterval) {
      clearInterval(this.cleanupInterval);
      this.cleanupInterval = null;
    }
  }
}
