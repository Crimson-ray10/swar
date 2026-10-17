/**
 * Core protocol and type definitions for Voice Bridge
 * Derived from voice-bridge-implementation-plan.md
 */

export type VoiceMessageType = 'NORMAL' | 'ALERT';

export interface VoiceMessage {
  version: number;
  messageId: string;
  senderId: string;
  language: string;
  type: VoiceMessageType;
  sequence: number;
  timestamp: number;
  text: string;
}

export type MessageState = 
  | 'CREATED'
  | 'SENT'
  | 'ACKED'
  | 'FAILED'
  | 'RETRYING'
  | 'DELIVERED'
  | 'PLAYED';

export interface StoredMessage {
  message: VoiceMessage;
  state: MessageState;
  retries: number;
  createdAt: number;
  updatedAt: number;
  transportUsed?: TransportType;
}

export type ConnectionState = 
  | 'DISCONNECTED'
  | 'DISCOVERING'
  | 'CONNECTING'
  | 'HANDSHAKING'
  | 'CONNECTED'
  | 'DEGRADED'
  | 'RECONNECTING';

export type TransportType = 'wifi_direct' | 'wifi_lan' | 'bluetooth';

export interface DeviceCapabilities {
  stt: boolean;
  tts: boolean;
  languages: number;
}

export interface DeviceInfo {
  protocolVersion: number;
  deviceId: string;
  language: string;
  capabilities: DeviceCapabilities;
}

export interface Peer {
  id: string;
  name: string;
  address: string;
  port?: number;
  ports?: {
    wifi_lan?: number;
    wifi_direct?: number;
    bluetooth?: number;
  };
  transport: TransportType;
  lastSeen: number;
  deviceInfo?: DeviceInfo;
}

export type VoiceMode = 'ptt' | 'continuous';

export interface SupervisorStatus {
  state: ConnectionState;
  activeTransport: TransportType | null;
  availableTransports: Record<TransportType, boolean>;
  selectedPeer: Peer | null;
  mode: VoiceMode;
  stats: {
    pingMs: number;
    messagesSent: number;
    messagesReceived: number;
    acksReceived: number;
    retries: number;
    drops: number;
    wireBits: number;
    rawPcmBitsEquivalent: number;
  };
}

export interface HandshakeMessage {
  type: 'HELLO' | 'HELLO_ACK' | 'DEVICE_INFO' | 'READY';
  payload?: Record<string, any>;
}

export interface AckPayload {
  type: 'ACK';
  messageId: string;
  sequence: number;
}

export interface PingPongPayload {
  type: 'PING' | 'PONG';
  timestamp: number;
}

export interface SupportedLanguage {
  id: string;
  name: string;
  native_name: string;
  script: string;
  sample_text: string;
}

export interface WebClientInfo {
  id: string;
  type: 'mobile' | 'desktop' | 'tablet';
  name: string;
  ip: string;
  connectedAt: number;
}

