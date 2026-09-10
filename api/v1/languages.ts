export default function handler(req: any, res: any) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  const languages = [
    {
      id: "hi",
      name: "Hindi",
      native_name: "हिन्दी",
      script: "Devanagari",
      unicode_range: [2304, 2431],
      mvp: true,
      asr_provider: "indic_conformer",
      asr_lang_code: "hi",
      tts_provider: "indicf5",
      tts_lang_code: "hi",
      normalizer: "indic_nfc",
      sample_text: "मैं घर पहुँच गया हूँ और सब ठीक है।"
    },
    {
      id: "ta",
      name: "Tamil",
      native_name: "தமிழ்",
      script: "Tamil",
      unicode_range: [2944, 3071],
      mvp: true,
      asr_provider: "indic_conformer",
      asr_lang_code: "ta",
      tts_provider: "indicf5",
      tts_lang_code: "ta",
      normalizer: "indic_nfc",
      sample_text: "நான் நலமாக இருக்கிறேன், நன்றி."
    },
    {
      id: "en",
      name: "English",
      native_name: "English",
      script: "Latin",
      unicode_range: [32, 126],
      mvp: true,
      asr_provider: "faster_whisper",
      asr_lang_code: "en",
      tts_provider: "piper",
      tts_lang_code: "en_US",
      normalizer: "standard_en",
      sample_text: "I have arrived at the station safely."
    },
    {
      id: "bn",
      name: "Bengali",
      native_name: "বাংলা",
      script: "Bengali",
      unicode_range: [2432, 2559],
      mvp: false,
      asr_provider: "indic_conformer",
      asr_lang_code: "bn",
      tts_provider: "indicf5",
      tts_lang_code: "bn",
      normalizer: "indic_nfc",
      sample_text: "আমি ভালো আছি।"
    },
    {
      id: "te",
      name: "Telugu",
      native_name: "తెలుగు",
      script: "Telugu",
      unicode_range: [3072, 3199],
      mvp: false,
      asr_provider: "indic_conformer",
      asr_lang_code: "te",
      tts_provider: "indicf5",
      tts_lang_code: "te",
      normalizer: "indic_nfc",
      sample_text: "నేను క్షేమంగా ఉన్నాను. నమస్కారం."
    },
    {
      id: "mr",
      name: "Marathi",
      native_name: "मराठी",
      script: "Devanagari",
      unicode_range: [2304, 2431],
      mvp: false,
      asr_provider: "indic_conformer",
      asr_lang_code: "mr",
      tts_provider: "indicf5",
      tts_lang_code: "mr",
      normalizer: "indic_nfc",
      sample_text: "मी सुरक्षित पोहोचलो आहे. काळजी करू नका."
    },
    {
      id: "gu",
      name: "Gujarati",
      native_name: "ગુજરાતી",
      script: "Gujarati",
      unicode_range: [2688, 2815],
      mvp: false,
      asr_provider: "indic_conformer",
      asr_lang_code: "gu",
      tts_provider: "indicf5",
      tts_lang_code: "gu",
      normalizer: "indic_nfc",
      sample_text: "હું ઘરે પહોંચી ગયો છું."
    },
    {
      id: "kn",
      name: "Kannada",
      native_name: "ಕನ್ನಡ",
      script: "Kannada",
      unicode_range: [3200, 3327],
      mvp: false,
      asr_provider: "indic_conformer",
      asr_lang_code: "kn",
      tts_provider: "indicf5",
      tts_lang_code: "kn",
      normalizer: "indic_nfc",
      sample_text: "ನಾನು ಸುರಕ್ಷಿತವಾಗಿದ್ದೇನೆ. ನಮಸ್ಕಾರ."
    },
    {
      id: "ml",
      name: "Malayalam",
      native_name: "മലയാളം",
      script: "Malayalam",
      unicode_range: [3328, 3455],
      mvp: false,
      asr_provider: "indic_conformer",
      asr_lang_code: "ml",
      tts_provider: "indicf5",
      tts_lang_code: "ml",
      normalizer: "indic_nfc",
      sample_text: "ഞാൻ സുഖമായിരിക്കുന്നു. നന്ദി."
    },
    {
      id: "pa",
      name: "Punjabi",
      native_name: "ਪੰਜਾਬੀ",
      script: "Gurmukhi",
      unicode_range: [2560, 2687],
      mvp: false,
      asr_provider: "indic_conformer",
      asr_lang_code: "pa",
      tts_provider: "indicf5",
      tts_lang_code: "pa",
      normalizer: "indic_nfc",
      sample_text: "ਮੈਂ ਘਰ ਪਹੁੰਚ ਗਿਆ ਹਾਂ। ਸਤਿ ਸ੍ਰੀ ਅਕਾਲ।"
    }
  ];

  return res.status(200).json(languages);
}
