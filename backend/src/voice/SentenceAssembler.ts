import { EventEmitter } from 'node:events';
import { VoiceMessage, VoiceMessageType, VoiceMode } from '../types.js';

/**
 * SentenceAssembler
 * Assembles speech fragments into finalized sentences ready for VoiceMessage packaging.
 * Handles both:
 * 1. Continuous Conversation Mode (pause-driven sentence finalization)
 * 2. Push-to-Talk (PTT) Mode (button-up finalization)
 */
export class SentenceAssembler extends EventEmitter {
  private mode: VoiceMode = 'continuous';
  private currentBuffer: string[] = [];
  private sequenceCounter: number = 0;
  private senderId: string;
  private language: string = 'hi';

  constructor(senderId: string, initialMode: VoiceMode = 'continuous') {
    super();
    this.senderId = senderId;
    this.mode = initialMode;
  }

  public setMode(mode: VoiceMode): void {
    if (this.mode !== mode) {
      this.mode = mode;
      this.emit('modeChanged', mode);
    }
  }

  public getMode(): VoiceMode {
    return this.mode;
  }

  public setLanguage(lang: string): void {
    this.language = lang;
  }

  /**
   * Append recognized partial or phrase segment
   */
  public pushPhrase(phrase: string): void {
    const clean = phrase.trim();
    if (clean.length > 0) {
      this.currentBuffer.push(clean);
      this.emit('partial', this.getCurrentText());
    }
  }

  public getCurrentText(): string {
    return this.currentBuffer.join(' ').trim();
  }

  /**
   * Finalizes the current sentence buffer into a VoiceMessage
   */
  public finalizeSentence(type: VoiceMessageType = 'NORMAL', overrideText?: string): VoiceMessage | null {
    const text = (overrideText ?? this.getCurrentText()).trim();
    if (text.length === 0) {
      return null;
    }

    this.sequenceCounter++;
    const messageId = `msg_${Date.now()}_${Math.random().toString(36).substring(2, 7)}`;

    const voiceMessage: VoiceMessage = {
      version: 1,
      messageId,
      senderId: this.senderId,
      language: this.language,
      type,
      sequence: this.sequenceCounter,
      timestamp: Date.now(),
      text,
    };

    this.currentBuffer = [];
    this.emit('sentenceFinalized', voiceMessage);
    return voiceMessage;
  }

  /**
   * Called on VAD pause detection in continuous mode
   */
  public onVadPause(): VoiceMessage | null {
    if (this.mode === 'continuous' && this.currentBuffer.length > 0) {
      return this.finalizeSentence('NORMAL');
    }
    return null;
  }

  /**
   * Called on PTT button release
   */
  public onPttRelease(type: VoiceMessageType = 'NORMAL', text?: string): VoiceMessage | null {
    return this.finalizeSentence(type, text);
  }

  public reset(): void {
    this.currentBuffer = [];
  }
}
