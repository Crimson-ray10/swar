import { EventEmitter } from 'node:events';
import { MessageState, StoredMessage, TransportType, VoiceMessage } from '../types.js';

/**
 * MessageStore
 * Persists finalized messages locally before transmission.
 * Separates speech-recognition success from transport availability.
 */
export class MessageStore extends EventEmitter {
  private messages: Map<string, StoredMessage> = new Map();
  private processedMessageIds: Set<string> = new Set(); // For deduplication

  /**
   * Save a newly finalized message before transmission
   */
  public saveMessage(message: VoiceMessage): StoredMessage {
    const stored: StoredMessage = {
      message,
      state: 'CREATED',
      retries: 0,
      createdAt: Date.now(),
      updatedAt: Date.now(),
    };

    this.messages.set(message.messageId, stored);
    this.emit('messageSaved', stored);
    return stored;
  }

  public updateState(messageId: string, state: MessageState, transport?: TransportType): StoredMessage | null {
    const item = this.messages.get(messageId);
    if (!item) return null;

    item.state = state;
    item.updatedAt = Date.now();
    if (transport) {
      item.transportUsed = transport;
    }

    if (state === 'RETRYING') {
      item.retries++;
    }

    this.emit('stateChanged', item);
    return item;
  }

  public getMessage(messageId: string): StoredMessage | undefined {
    return this.messages.get(messageId);
  }

  public getAllMessages(): StoredMessage[] {
    return Array.from(this.messages.values()).sort((a, b) => b.createdAt - a.createdAt);
  }

  public getPendingMessages(): StoredMessage[] {
    return Array.from(this.messages.values()).filter(
      (m) => m.state === 'CREATED' || m.state === 'RETRYING' || m.state === 'SENT'
    );
  }

  /**
   * Deduplication check for receiving side
   * Returns true if already processed, false if fresh message
   */
  public checkAndMarkDuplicate(messageId: string): boolean {
    if (this.processedMessageIds.has(messageId)) {
      return true;
    }
    this.processedMessageIds.add(messageId);

    // Keep set bounded
    if (this.processedMessageIds.size > 2000) {
      const firstEntries = Array.from(this.processedMessageIds).slice(0, 500);
      firstEntries.forEach((id) => this.processedMessageIds.delete(id));
    }

    return false;
  }

  public clear(): void {
    this.messages.clear();
    this.processedMessageIds.clear();
  }
}
