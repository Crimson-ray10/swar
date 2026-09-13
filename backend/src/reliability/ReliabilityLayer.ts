import { EventEmitter } from 'node:events';
import { AckPayload, StoredMessage, VoiceMessage } from '../types.js';
import { MessageStore } from './MessageStore.js';

export interface PendingAck {
  storedMessage: StoredMessage;
  timer: NodeJS.Timeout;
  attempt: number;
}

/**
 * ReliabilityLayer
 * Handles ACK acknowledgement, retries, exponential backoff, and deduplication.
 */
export class ReliabilityLayer extends EventEmitter {
  private messageStore: MessageStore;
  private pendingAcks: Map<string, PendingAck> = new Map();
  private lastSentMessageId: string | null = null;
  private lastAckedMessageId: string | null = null;
  private maxRetries: number = 3;
  private initialTimeoutMs: number = 1200;

  constructor(messageStore: MessageStore, maxRetries: number = 3, initialTimeoutMs: number = 1200) {
    super();
    this.messageStore = messageStore;
    this.maxRetries = maxRetries;
    this.initialTimeoutMs = initialTimeoutMs;
  }

  public getLastSentMessageId(): string | null {
    return this.lastSentMessageId;
  }

  public getLastAckedMessageId(): string | null {
    return this.lastAckedMessageId;
  }

  /**
   * Track an outgoing VoiceMessage and start ACK timer
   */
  public trackSentMessage(message: VoiceMessage, sendFn: (msg: VoiceMessage) => Promise<boolean>): void {
    this.lastSentMessageId = message.messageId;
    const stored = this.messageStore.getMessage(message.messageId) || this.messageStore.saveMessage(message);
    this.messageStore.updateState(message.messageId, 'SENT');

    this.scheduleAckTimeout(stored, 1, sendFn);
  }

  private scheduleAckTimeout(
    stored: StoredMessage,
    attempt: number,
    sendFn: (msg: VoiceMessage) => Promise<boolean>
  ): void {
    const timeoutMs = this.initialTimeoutMs * Math.pow(1.5, attempt - 1);

    const timer = setTimeout(async () => {
      this.pendingAcks.delete(stored.message.messageId);

      if (attempt <= this.maxRetries) {
        this.messageStore.updateState(stored.message.messageId, 'RETRYING');
        this.emit('messageRetrying', { message: stored.message, attempt });

        const success = await sendFn(stored.message);
        if (success) {
          this.scheduleAckTimeout(stored, attempt + 1, sendFn);
        } else {
          this.emit('transportFallbackRequired', stored.message);
        }
      } else {
        this.messageStore.updateState(stored.message.messageId, 'FAILED');
        this.emit('messageFailed', { message: stored.message, attempts: attempt });
      }
    }, timeoutMs);

    this.pendingAcks.set(stored.message.messageId, {
      storedMessage: stored,
      timer,
      attempt,
    });
  }

  /**
   * Handle incoming ACK from peer
   */
  public handleAck(ack: AckPayload): boolean {
    const pending = this.pendingAcks.get(ack.messageId);
    if (pending) {
      clearTimeout(pending.timer);
      this.pendingAcks.delete(ack.messageId);
      this.lastAckedMessageId = ack.messageId;
      this.messageStore.updateState(ack.messageId, 'ACKED');
      this.emit('messageAcked', { messageId: ack.messageId, sequence: ack.sequence });
      return true;
    }
    return false;
  }

  /**
   * Create an ACK packet for an incoming VoiceMessage
   */
  public createAck(message: VoiceMessage): AckPayload {
    return {
      type: 'ACK',
      messageId: message.messageId,
      sequence: message.sequence,
    };
  }

  /**
   * Verify whether an incoming message is a duplicate
   */
  public isDuplicate(message: VoiceMessage): boolean {
    return this.messageStore.checkAndMarkDuplicate(message.messageId);
  }

  public clear(): void {
    for (const pending of this.pendingAcks.values()) {
      clearTimeout(pending.timer);
    }
    this.pendingAcks.clear();
  }
}
