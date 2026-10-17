import { EventEmitter } from 'node:events';
import { ConnectionState, Peer, TransportType, VoiceMessage } from '../types.js';

/**
 * Base VoiceTransport abstraction
 * The voice pipeline only communicates with this abstraction.
 */
export interface VoiceTransport extends EventEmitter {
  readonly transportType: TransportType;

  connect(peer: Peer): Promise<boolean>;

  disconnect(): Promise<void>;

  send(message: VoiceMessage | object): Promise<boolean>;

  getConnectionState(): ConnectionState;

  ping(): Promise<number>;

  isAvailable(): Promise<boolean>;
}
