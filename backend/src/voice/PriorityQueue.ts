import { EventEmitter } from 'node:events';
import { VoiceMessage } from '../types.js';

export interface QueueItem {
  message: VoiceMessage;
  receivedAt: number;
}

/**
 * PriorityQueue
 * Maintains NORMAL_QUEUE and ALERT_QUEUE.
 * When an ALERT message arrives, normal playback is paused, the ALERT message is played immediately,
 * and normal queue playback resumes afterwards.
 */
export class PriorityQueue extends EventEmitter {
  private normalQueue: QueueItem[] = [];
  private alertQueue: QueueItem[] = [];
  private isPlaying: boolean = false;
  private currentItem: QueueItem | null = null;

  /**
   * Push incoming VoiceMessage into appropriate priority queue
   */
  public enqueue(message: VoiceMessage): void {
    const item: QueueItem = {
      message,
      receivedAt: Date.now(),
    };

    if (message.type === 'ALERT') {
      this.alertQueue.push(item);
      this.emit('alertEnqueued', item);

      // If currently playing a normal message, preempt it
      if (this.currentItem && this.currentItem.message.type === 'NORMAL') {
        this.emit('preempt', this.currentItem);
        // Put preempted item back to the front of normal queue
        this.normalQueue.unshift(this.currentItem);
        this.currentItem = null;
        this.isPlaying = false;
      }
    } else {
      this.normalQueue.push(item);
      this.emit('normalEnqueued', item);
    }

    this.processNext();
  }

  /**
   * Dequeue and process the next message based on strict priority
   */
  public processNext(): QueueItem | null {
    if (this.isPlaying) {
      return null;
    }

    let nextItem: QueueItem | undefined;

    // ALERT messages have strict priority over NORMAL messages
    if (this.alertQueue.length > 0) {
      nextItem = this.alertQueue.shift();
    } else if (this.normalQueue.length > 0) {
      nextItem = this.normalQueue.shift();
    }

    if (nextItem) {
      this.currentItem = nextItem;
      this.isPlaying = true;
      this.emit('play', nextItem);
      return nextItem;
    }

    return null;
  }

  /**
   * Called when playback of the current item has finished
   */
  public onPlaybackComplete(): void {
    const finished = this.currentItem;
    this.currentItem = null;
    this.isPlaying = false;

    if (finished) {
      this.emit('playbackComplete', finished);
    }

    // Process remaining items
    this.processNext();
  }

  public getQueueLengths(): { normal: number; alert: number; isPlaying: boolean } {
    return {
      normal: this.normalQueue.length,
      alert: this.alertQueue.length,
      isPlaying: this.isPlaying,
    };
  }

  public clear(): void {
    this.normalQueue = [];
    this.alertQueue = [];
    this.currentItem = null;
    this.isPlaying = false;
  }
}
