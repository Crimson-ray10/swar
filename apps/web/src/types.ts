export interface LanguageSpec {
  id: string;
  name: string;
  native_name: string;
  script: string;
  unicode_range: number[];
  mvp: boolean;
  asr_provider: string;
  asr_lang_code: string;
  tts_provider: string;
  tts_lang_code: string;
  normalizer: string;
  sample_text: string;
}

export interface DeliveryEvent {
  sequence: number;
  status: 'delivered' | 'lost' | 'crc_fail' | 'recovered';
  sent_time_ms: number;
  delivery_time_ms: number;
  on_air_bits: number;
  measured_ber: number;
  is_parity: boolean;
}

export interface CriticalSpan {
  span_type: string;
  start_char: number;
  end_char: number;
  literal: string;
}

export interface RunReport {
  run_id: string;
  status: 'complete' | 'partial' | 'unrecoverable' | 'failed';
  input_info: {
    duration_ms: number;
    sample_rate_hz: number;
    channels: number;
  };
  transcript: {
    raw: string;
    normalized: string;
    language: string;
    asr_confidence: number | null;
    critical_spans: CriticalSpan[];
  };
  transport: {
    gross_rate_bps: number;
    wire_bits: number;
    source_bits: number;
    good_bits: number;
    actual_wire_bps: number;
    compression_ratio_vs_pcm: number;
    packet_count: number;
    lost_packets: number;
    crc_fail_count: number;
    recovered_packets: number;
    measured_ber: number;
    measured_per: number;
  };
  receiver: {
    status: 'exact' | 'partial' | 'unrecoverable';
    text: string | null;
    tts_status: string;
    missing_packet_sequences: number[];
    voice_label: string;
  };
  latency_ms: {
    capture: number;
    asr: number;
    encode: number;
    channel: number;
    decode: number;
    tts: number;
    end_to_end: number;
  };
  audio_output_base64: string | null;
  delivery_events: DeliveryEvent[];
  reproducibility: {
    seed: number;
    config_hash: string;
    tokenizer_sha256: string;
  };
  warnings: string[];
}
