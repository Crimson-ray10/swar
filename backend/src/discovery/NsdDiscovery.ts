import { EventEmitter } from 'node:events';
import dgram from 'node:dgram';
import os from 'node:os';
import { Peer, TransportType } from '../types.js';

export const SERVICE_TYPE = '_voicebridge._tcp';
export const SERVICE_NAME = 'VoiceBridge';
export const DISCOVERY_UDP_PORT = 8989;

export interface BeaconPayload {
  service: string;
  type: string;
  deviceId: string;
  name: string;
  primaryIp: string;
  ips: string[];
  ports: {
    wifi_lan: number;
    wifi_direct: number;
    bluetooth: number;
  };
  timestamp: number;
}

/**
 * Network Service Discovery (NSD) for Voice Bridge
 * Broadcasts local device presence over UDP (port 8989) on Wi-Fi LAN / Wi-Fi Direct
 * and discovers all other Voice Bridge nodes on the same network automatically.
 */
export class NsdDiscovery extends EventEmitter {
  private peers: Map<string, Peer> = new Map();
  private deviceId: string;
  private port: number;
  private isAdvertising: boolean = false;
  private isDiscovering: boolean = false;
  private advertiseInterval: NodeJS.Timeout | null = null;
  private cleanupInterval: NodeJS.Timeout | null = null;
  private broadcastSocket: dgram.Socket | null = null;
  private listenSocket: dgram.Socket | null = null;

  constructor(deviceId: string, port: number = 8988) {
    super();
    this.deviceId = deviceId;
    this.port = port;
  }

  /**
   * Get all active IPv4 addresses from local network adapters (Wi-Fi, Ethernet, P2P)
   */
  public getLocalIps(): string[] {
    const interfaces = os.networkInterfaces();
    const ips: string[] = [];

    for (const name of Object.keys(interfaces)) {
      const iface = interfaces[name];
      if (!iface) continue;
      for (const info of iface) {
        if (info.family === 'IPv4' && !info.internal) {
          ips.push(info.address);
        }
      }
    }

    return ips.length > 0 ? ips : ['127.0.0.1'];
  }

  /**
   * Start periodic UDP broadcast advertising device presence on local network
   */
  public async startAdvertising(): Promise<void> {
    if (this.isAdvertising) return;
    this.isAdvertising = true;

    try {
      this.broadcastSocket = dgram.createSocket({ type: 'udp4', reuseAddr: true });
      this.broadcastSocket.on('error', (err) => {
        // Log without crashing
        this.emit('broadcastError', err);
      });

      this.broadcastSocket.bind(0, () => {
        try {
          this.broadcastSocket?.setBroadcast(true);
        } catch {
          // ignore error enabling broadcast
        }
      });
    } catch {
      // ignore
    }

    const sendBeacon = () => {
      if (!this.broadcastSocket || !this.isAdvertising) return;

      const ips = this.getLocalIps();
      const primaryIp = ips[0] || '127.0.0.1';

      const payload: BeaconPayload = {
        service: SERVICE_NAME,
        type: SERVICE_TYPE,
        deviceId: this.deviceId,
        name: `VoiceBridge-${this.deviceId.substring(0, 6)}`,
        primaryIp,
        ips,
        ports: {
          wifi_lan: 8988,
          wifi_direct: 8990,
          bluetooth: 8992,
        },
        timestamp: Date.now(),
      };

      const message = Buffer.from(JSON.stringify(payload));

      try {
        // Broadcast to universal subnet broadcast
        this.broadcastSocket.send(message, 0, message.length, DISCOVERY_UDP_PORT, '255.255.255.255');
        // Also send to loopback for single-machine tests
        this.broadcastSocket.send(message, 0, message.length, DISCOVERY_UDP_PORT, '127.0.0.1');
      } catch {
        // ignore send errors
      }
    };

    // Send initial beacon immediately, then every 3 seconds
    sendBeacon();
    this.advertiseInterval = setInterval(sendBeacon, 3000);

    this.emit('advertisingStarted', {
      service: SERVICE_NAME,
      port: this.port,
      deviceId: this.deviceId,
    });
  }

  /**
   * Start listening for peer beacons over UDP port 8989
   */
  public async startDiscovery(): Promise<void> {
    if (this.isDiscovering) return;
    this.isDiscovering = true;
    this.emit('discoveryStarted');

    try {
      this.listenSocket = dgram.createSocket({ type: 'udp4', reuseAddr: true });

      this.listenSocket.on('error', (err) => {
        this.emit('discoveryError', err);
      });

      this.listenSocket.on('message', (msg, rinfo) => {
        try {
          const payload: BeaconPayload = JSON.parse(msg.toString());
          if (payload.service === SERVICE_NAME && payload.deviceId !== this.deviceId) {
            const peerIp = rinfo.address === '127.0.0.1' ? (payload.primaryIp || '127.0.0.1') : rinfo.address;

            this.registerPeer({
              id: payload.deviceId,
              name: payload.name || `VoiceBridge (${peerIp})`,
              address: peerIp,
              port: payload.ports?.wifi_lan || 8988,
              ports: payload.ports || {
                wifi_lan: 8988,
                wifi_direct: 8990,
                bluetooth: 8992,
              },
              transport: 'wifi_lan',
              lastSeen: Date.now(),
              deviceInfo: {
                protocolVersion: 1,
                deviceId: payload.deviceId,
                language: 'en',
                capabilities: { stt: true, tts: true, languages: 11 },
              },
            });
          }
        } catch {
          // ignore non-JSON or invalid packets
        }
      });

      this.listenSocket.bind({ port: DISCOVERY_UDP_PORT, exclusive: false }, () => {
        try {
          this.listenSocket?.setBroadcast(true);
        } catch {
          // ignore
        }
      });
    } catch {
      // ignore
    }

    // Always include a self-loopback peer option for single device offline verification
    this.registerPeer({
      id: `peer_${this.deviceId}_loopback`,
      name: `Local Loopback Node (${this.deviceId.substring(0, 6)})`,
      address: '127.0.0.1',
      port: this.port,
      ports: {
        wifi_lan: 8988,
        wifi_direct: 8990,
        bluetooth: 8992,
      },
      transport: 'wifi_lan',
      lastSeen: Date.now(),
      deviceInfo: {
        protocolVersion: 1,
        deviceId: this.deviceId,
        language: 'hi',
        capabilities: { stt: true, tts: true, languages: 11 },
      },
    });

    // Cleanup stale peers every 10s
    if (!this.cleanupInterval) {
      this.cleanupInterval = setInterval(() => {
        const now = Date.now();
        for (const [id, peer] of this.peers.entries()) {
          if (now - peer.lastSeen > 20000 && !peer.id.includes('loopback')) {
            this.peers.delete(id);
            this.emit('peerLost', peer);
          }
        }
      }, 10000);
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

    if (this.advertiseInterval) {
      clearInterval(this.advertiseInterval);
      this.advertiseInterval = null;
    }

    if (this.cleanupInterval) {
      clearInterval(this.cleanupInterval);
      this.cleanupInterval = null;
    }

    if (this.broadcastSocket) {
      try {
        this.broadcastSocket.close();
      } catch {}
      this.broadcastSocket = null;
    }

    if (this.listenSocket) {
      try {
        this.listenSocket.close();
      } catch {}
      this.listenSocket = null;
    }
  }
}
