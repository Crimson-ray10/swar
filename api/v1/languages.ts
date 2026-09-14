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
      sample_text: "मुझे तुरंत मदद चाहिए, यहाँ आग लगी है।"
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
      sample_text: "எனக்கு உடனடி உதவி தேவை, இங்கே அவசரநிலை உள்ளது."
    },
    {
      id: "te",
      name: "Telugu",
      native_name: "తెలుగు",
      script: "Telugu",
      unicode_range: [3072, 3199],
      mvp: true,
      asr_provider: "indic_conformer",
      asr_lang_code: "te",
      tts_provider: "indicf5",
      tts_lang_code: "te",
      normalizer: "indic_nfc",
      sample_text: "నాకు తక్షణ సహాయం కావాలి, ఇక్కడ అగ్ని ప్రమాదం జరిగింది."
    },
    {
      id: "en",
      name: "English (India)",
      native_name: "English",
      script: "Latin",
      unicode_range: [32, 126],
      mvp: true,
      asr_provider: "faster_whisper",
      asr_lang_code: "en",
      tts_provider: "piper",
      tts_lang_code: "en_US",
      normalizer: "standard_en",
      sample_text: "I need immediate assistance, there is an emergency here."
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
      sample_text: "मला तातडीने मदतीची गरज आहे, येथे आग लागली आहे."
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
      sample_text: "আমার অবিলম্বে সাহায্য দরকার, এখানে আগুন লেগেছে।"
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
      sample_text: "ನನಗೆ ತಕ್ಷಣ ಸಹಾಯ ಬೇಕು, ಇಲ್ಲಿ ತುರ್ತು ಪರಿಸ್ಥಿತಿ ಇದೆ."
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
      sample_text: "મને તાત્કાલિક મદદની જરૂર છે, અહીં કટોકટી છે."
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
      sample_text: "എനിക്ക് അടിയന്തിര സഹായം വേണം, ഇവിടെ തീപിടുത്തമുണ്ട്."
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
      sample_text: "ਮੈਨੂੰ ਤੁਰੰਤ ਮਦਦ ਚਾਹੀਦੀ ਹੈ, ਇੱਥੇ ਐਮਰਜੈਂਸੀ ਹੈ।"
    },
    {
      id: "ur",
      name: "Urdu",
      native_name: "اردو",
      script: "Arabic-Persian",
      unicode_range: [1536, 1791],
      mvp: false,
      asr_provider: "indic_conformer",
      asr_lang_code: "ur",
      tts_provider: "indicf5",
      tts_lang_code: "ur",
      normalizer: "indic_nfc",
      sample_text: "مجھے فوری مدد کی ضرورت ہے، یہاں آگ لگی ہے۔"
    }
  ];

  return res.status(200).json(languages);
}
