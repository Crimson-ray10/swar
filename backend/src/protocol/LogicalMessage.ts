/** Shared semantic envelope; deliberately independent of physical/network transport. */
export interface ItantraMessage {
  type: 'ITANTRA_MESSAGE'; sessionId: string; sequence: number;
  language: 'hi' | 'en' | 'hinglish'; encoding: 'text_v1'; payload: string; timestamp: number;
}
export function parseLogicalMessage(value: unknown): ItantraMessage {
  const v = value as Partial<ItantraMessage> | null;
  if (!v || v.type !== 'ITANTRA_MESSAGE' || typeof v.sessionId !== 'string'
    || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v.sessionId)
    || !Number.isSafeInteger(v.sequence) || Number(v.sequence) < 1 || !['hi','en','hinglish'].includes(v.language ?? '')
    || v.encoding !== 'text_v1' || typeof v.payload !== 'string' || Buffer.byteLength(v.payload) < 1
    || Buffer.byteLength(v.payload) > 8192 || !Number.isSafeInteger(v.timestamp) || Number(v.timestamp) < 0) {
    throw new Error('Invalid ITANTRA_MESSAGE');
  }
  return { type:v.type,sessionId:v.sessionId,sequence:v.sequence!,language:v.language!,encoding:v.encoding,payload:v.payload,timestamp:v.timestamp! };
}
