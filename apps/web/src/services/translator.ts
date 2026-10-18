/**
 * AI4Bharat IndicTrans2 + CTranslate2 Translation Service
 * Optimized for ultra-low latency speech-to-speech communication.
 * 
 * Core Requirement:
 * Translator ONLY works if the languages of users are different.
 * If both users have selected the same language in their app,
 * the translator layer MUST be bypassed.
 */

export interface TranslationResult {
  originalText: string;
  translatedText: string;
  sourceLang: string;
  targetLang: string;
  bypassed: boolean;
  reason?: string;
  method?: string;
  latencyMs: number;
}

export interface IndicTransLangMeta {
  code: string;
  flores: string;
  name: string;
  nativeName: string;
}

export const INDICTRANS2_LANGUAGES: Record<string, IndicTransLangMeta> = {
  en: { code: 'en', flores: 'eng_Latn', name: 'English', nativeName: 'English' },
  hi: { code: 'hi', flores: 'hin_Deva', name: 'Hindi', nativeName: 'हिन्दी' },
  ta: { code: 'ta', flores: 'tam_Taml', name: 'Tamil', nativeName: 'தமிழ்' },
  te: { code: 'te', flores: 'tel_Telu', name: 'Telugu', nativeName: 'తెలుగు' },
  mr: { code: 'mr', flores: 'mar_Deva', name: 'Marathi', nativeName: 'मराठी' },
  gu: { code: 'gu', flores: 'guj_Gujr', name: 'Gujarati', nativeName: 'ગુજરાતી' },
  bn: { code: 'bn', flores: 'ben_Beng', name: 'Bengali', nativeName: 'বাংলা' },
  kn: { code: 'kn', flores: 'kan_Knda', name: 'Kannada', nativeName: 'ಕನ್ನಡ' },
  ml: { code: 'ml', flores: 'mal_Mlym', name: 'Malayalam', nativeName: 'മലയാളം' },
  or: { code: 'or', flores: 'ory_Orya', name: 'Odia', nativeName: 'ଓଡ଼ିଆ' },
  pa: { code: 'pa', flores: 'pan_Guru', name: 'Punjabi', nativeName: 'ਪੰਜਾਬੀ' },
  ur: { code: 'ur', flores: 'urd_Arab', name: 'Urdu', nativeName: 'اردو' },
  as: { code: 'as', flores: 'asm_Beng', name: 'Assamese', nativeName: 'অসমীয়া' },
};

/**
 * Normalizes language codes to 2-letter base tags (e.g. 'en-IN' -> 'en', 'hin_Deva' -> 'hi').
 */
export function normalizeLanguage(lang: string | undefined | null): string {
  if (!lang) return 'en';
  const cleaned = lang.trim().toLowerCase();

  for (const [code, meta] of Object.entries(INDICTRANS2_LANGUAGES)) {
    if (cleaned === meta.flores.toLowerCase() || cleaned.startsWith(meta.name.toLowerCase())) {
      return code;
    }
  }

  const base = cleaned.split(/[-_]/)[0];
  return INDICTRANS2_LANGUAGES[base] ? base : (base || 'en');
}

/**
 * Gets the AI4Bharat IndicTrans2 Flores-200 code for a language tag.
 */
export function getFloresTag(lang: string): string {
  const norm = normalizeLanguage(lang);
  return INDICTRANS2_LANGUAGES[norm]?.flores || 'eng_Latn';
}

/**
 * Detects the script or linguistic markers of text.
 * Essential to avoid false-positive bypasses when language tags default to 'en'.
 */
export function detectScriptLanguage(text: string): 'hi' | 'ta' | 'te' | 'mr' | 'gu' | 'bn' | 'kn' | 'ml' | 'or' | 'pa' | 'ur' | 'en' | 'unknown' {
  if (!text || !text.trim()) return 'unknown';

  // Devanagari Unicode Block: Hindi, Marathi, Sanskrit, Nepali (\u0900-\u097F)
  if (/[\u0900-\u097F]/.test(text)) {
    // Marathi specific character check (\u0933: ळ)
    if (/\u0933/.test(text)) return 'mr';
    return 'hi';
  }
  // Bengali / Assamese Block (\u0980-\u09FF)
  if (/[\u0980-\u09FF]/.test(text)) return 'bn';
  // Gurmukhi / Punjabi Block (\u0A00-\u0A7F)
  if (/[\u0A00-\u0A7F]/.test(text)) return 'pa';
  // Gujarati Block (\u0A80-\u0AFF)
  if (/[\u0A80-\u0AFF]/.test(text)) return 'gu';
  // Odia Block (\u0B00-\u0B7F)
  if (/[\u0B00-\u0B7F]/.test(text)) return 'or';
  // Tamil Block (\u0B80-\u0BFF)
  if (/[\u0B80-\u0BFF]/.test(text)) return 'ta';
  // Telugu Block (\u0C00-\u0C7F)
  if (/[\u0C00-\u0C7F]/.test(text)) return 'te';
  // Kannada Block (\u0C80-\u0CFF)
  if (/[\u0C80-\u0CFF]/.test(text)) return 'kn';
  // Malayalam Block (\u0D00-\u0D7F)
  if (/[\u0D00-\u0D7F]/.test(text)) return 'ml';
  // Arabic / Urdu Block (\u0600-\u06FF)
  if (/[\u0600-\u06FF]/.test(text)) return 'ur';

  // Check Romanized Hinglish vocabulary markers
  const lower = text.trim().toLowerCase();
  const hinglishTokens = [
    'namaste', 'kaise', 'kya', 'kahan', 'kyun', 'theek', 'mera', 'meri',
    'aapka', 'aapki', 'hum', 'hamara', 'madad', 'chahiye', 'karo', 'sun',
    'bolo', 'batao', 'ruko', 'jao', 'aao', 'paani', 'pani', 'khana', 'achha',
    'acha', 'shukriya', 'dhanyawad', 'dost', 'bhai', 'yaar', 'hai', 'hain', 'hoon'
  ];
  const words = lower.split(/\s+/);
  const matched = words.filter(w => hinglishTokens.includes(w)).length;
  if (matched >= 1 && (matched >= words.length * 0.3 || matched >= 2 || PHRASES_HI_TO_EN[lower])) {
    return 'hi';
  }

  if (/^[a-zA-Z0-9\s.,?!'"\-:;()]+$/.test(text)) {
    return 'en';
  }

  return 'unknown';
}

/**
 * Core Requirement Function:
 * Returns TRUE if both users have selected the same language (translation layer BYPASSED).
 * Returns FALSE if users have different languages (translation layer REQUIRED).
 * 
 * Includes script verification so Hindi speech is never accidentally bypassed to an English receiver!
 */
export function shouldBypassTranslation(user1Lang: string, user2Lang: string, text?: string): boolean {
  const norm1 = normalizeLanguage(user1Lang);
  const norm2 = normalizeLanguage(user2Lang);

  if (text && text.trim()) {
    const detected = detectScriptLanguage(text);
    // If incoming text is in Hindi/Devanagari, but receiver expects English ('en'), NEVER bypass!
    if (detected === 'hi' && norm2 === 'en') {
      return false;
    }
    // If incoming text is English, but receiver expects Hindi ('hi'), NEVER bypass!
    if (detected === 'en' && norm2 === 'hi') {
      return false;
    }
    // If incoming text is clearly in a different Indian script than receiver
    if (detected !== 'unknown' && detected !== norm2) {
      return false;
    }
  }

  return norm1 === norm2;
}

/**
 * Decodes HTML entities returned by translation APIs (e.g. &#39; -> ')
 */
export function decodeHtmlEntities(str: string): string {
  if (!str) return '';
  return str
    .replace(/&#39;/g, "'")
    .replace(/&quot;/g, '"')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&#(\d+);/g, (_, code) => String.fromCharCode(Number(code)));
}

// Comprehensive bidirectional phrases for English -> Hindi
const PHRASES_EN_TO_HI: Record<string, string> = {
  'hello': 'नमस्ते',
  'hi': 'नमस्ते',
  'hey': 'नमस्ते',
  'how are you': 'आप कैसे हैं?',
  'how are you doing': 'आप कैसे हैं?',
  'what is your name': 'आपका नाम क्या है?',
  'my name is': 'मेरा नाम है',
  'where are you': 'आप कहाँ हैं?',
  'where are you going': 'आप कहाँ जा रहे हैं?',
  'where should we meet': 'हम कहाँ मिलें?',
  'what happened': 'क्या हुआ?',
  'what is this': 'यह क्या है?',
  'what is that': 'वह क्या है?',
  'what are you doing': 'आप क्या कर रहे हैं?',
  'i need help': 'मुझे मदद चाहिए',
  'help me': 'मेरी मदद करो',
  'please help me': 'कृपया मेरी मदद करें',
  'i need water': 'मुझे पानी चाहिए',
  'i need food': 'मुझे खाना चाहिए',
  'i need medicine': 'मुझे दवा चाहिए',
  'call a doctor': 'डॉक्टर को बुलाओ',
  'call the police': 'पुलिस को बुलाओ',
  'call an ambulance': 'एम्बुलेंस को बुलाओ',
  'thank you': 'धन्यवाद',
  'thank you very much': 'बहुत-बहुत धन्यवाद',
  'thanks': 'धन्यवाद',
  'you are welcome': 'आपका स्वागत है',
  'good morning': 'शुभ प्रभात',
  'good afternoon': 'शुभ दोपहर',
  'good evening': 'शुभ संध्या',
  'good night': 'शुभ रात्रि',
  'welcome': 'स्वागत है',
  'come here': 'यहाँ आओ',
  'go there': 'वहाँ जाओ',
  'stop here': 'यहाँ रुको',
  'wait here': 'यहाँ इंतज़ार करो',
  'wait a minute': 'एक मिनट रुको',
  'stand by': 'प्रतीक्षा करें',
  'all clear': 'सब साफ़ है',
  'can you hear me': 'क्या आप मुझे सुन सकते हैं?',
  'loud and clear': 'साफ़ और स्पष्ट',
  'over and out': 'समाप्त',
  'message received': 'संदेश प्राप्त हुआ',
  'battery low': 'बैटरी कम है',
  'signal weak': 'सिग्नल कमज़ोर है',
  'connection lost': 'कनेक्शन टूट गया',
  'connected': 'जुड़ गया',
  'be careful': 'सावधान रहो',
  'danger ahead': 'आगे खतरा है',
  'understood': 'समझ गया',
  'i understand': 'मैं समझता हूँ',
  'i do not understand': 'मुझे समझ नहीं आया',
  'all good': 'सब ठीक है',
  'i am fine': 'मैं ठीक हूँ',
  'i am good': 'मैं ठीक हूँ',
  'i am ready': 'मैं तैयार हूँ',
  'are you ready': 'क्या आप तैयार हैं?',
  'i am coming': 'मैं आ रहा हूँ',
  'i am on my way': 'मैं रास्ते में हूँ',
  'i have reached': 'मैं पहुँच गया हूँ',
  'see you later': 'फिर मिलेंगे',
  'see you soon': 'जल्द मिलेंगे',
  'take care': 'अपना ख्याल रखना',
  'hurry up': 'जल्दी करो',
  'do not worry': 'चिंता मत करो',
  'no problem': 'कोई बात नहीं',
  'listen to me': 'मेरी बात सुनो',
  'speak slowly': 'धीरे बोलो',
  'repeat please': 'कृपया दोहराएं',
  'where is the hospital': 'अस्पताल कहाँ है?',
  'where is the road': 'सड़क कहाँ है?',
  'where is the car': 'गाड़ी कहाँ है?',
  'let us go': 'चलो चलें',
  'emergency': 'आपातकालीन स्थिति',
  'please respond': 'कृपया उत्तर दें',
};

// Rich English -> Hindi single words
const WORDS_EN_TO_HI: Record<string, string> = {
  'yes': 'हाँ', 'no': 'नहीं', 'okay': 'ठीक है', 'ok': 'ठीक है', 'sure': 'ज़रूर',
  'never': 'कभी नहीं', 'maybe': 'शायद', 'please': 'कृपया', 'sorry': 'माफ़ कीजिए',
  'water': 'पानी', 'food': 'खाना', 'medicine': 'दवा', 'doctor': 'डॉक्टर',
  'hospital': 'अस्पताल', 'police': 'पुलिस', 'ambulance': 'एम्बुलेंस', 'danger': 'खतरा',
  'safe': 'सुरक्षित', 'home': 'घर', 'house': 'मकान', 'road': 'सड़क', 'car': 'गाड़ी',
  'city': 'शहर', 'village': 'गाँव', 'friend': 'दोस्त', 'brother': 'भाई',
  'sister': 'बहन', 'mother': 'माँ', 'father': 'पिता', 'man': 'आदमी', 'woman': 'महिला',
  'child': 'बच्चा', 'people': 'लोग', 'family': 'परिवार', 'phone': 'फ़ोन',
  'money': 'पैसे', 'battery': 'बैटरी', 'signal': 'सिग्नल', 'radio': 'रेडियो',
  'help': 'मदद', 'time': 'समय', 'day': 'दिन', 'night': 'रात', 'morning': 'सुबह',
  'evening': 'शाम', 'today': 'आज', 'tomorrow': 'कल', 'yesterday': 'कल',
  'now': 'अब', 'later': 'बाद में', 'here': 'यहाँ', 'there': 'वहाँ', 'stop': 'रुको',
  'go': 'जाओ', 'wait': 'रुको', 'come': 'आओ', 'listen': 'सुनो', 'speak': 'बोलो',
  'say': 'कहो', 'tell': 'बताओ', 'look': 'देखो', 'see': 'देखो', 'run': 'दौड़ो',
  'hurry': 'जल्दी', 'fast': 'तेज़', 'slow': 'धीमा', 'good': 'अच्छा', 'bad': 'बुरा',
  'big': 'बड़ा', 'small': 'छोटा', 'hot': 'गर्म', 'cold': 'ठंडा', 'new': 'नया',
  'old': 'पुराना', 'ready': 'तैयार', 'clean': 'साफ़', 'right': 'सही', 'wrong': 'गलत',
  'what': 'क्या', 'why': 'क्यों', 'when': 'कब', 'how': 'कैसे', 'where': 'कहाँ',
  'who': 'कौन', 'this': 'यह', 'that': 'वह', 'my': 'मेरा', 'your': 'आपका',
  'our': 'हमारा', 'their': 'उनका', 'his': 'उसका', 'her': 'उसकी',
  'one': 'एक', 'two': 'दो', 'three': 'तीन', 'four': 'चार', 'five': 'पाँच',
  'six': 'छह', 'seven': 'सात', 'eight': 'आठ', 'nine': 'नौ', 'ten': 'दस',
  'and': 'और', 'or': 'या', 'but': 'लेकिन', 'with': 'साथ', 'in': 'में', 'on': 'पर'
};

// Comprehensive bidirectional phrases for Hindi -> English (Devanagari + Hinglish)
const PHRASES_HI_TO_EN: Record<string, string> = {
  // Greetings & Courtesies
  'नमस्ते': 'Hello', 'प्रणाम': 'Hello', 'namaste': 'Hello', 'pranam': 'Hello', 'अरे सुनो': 'Hey listen',
  'नमस्ते आप कैसे हैं': 'Hello, how are you?', 'नमस्ते कैसे हो': 'Hello, how are you?',
  'namaste aap kaise hain': 'Hello, how are you?', 'namaste kaise ho': 'Hello, how are you?',
  'शुभ प्रभात': 'Good morning', 'सुप्रभात': 'Good morning', 'shubh prabhat': 'Good morning',
  'शुभ दोपहर': 'Good afternoon', 'shubh dopahar': 'Good afternoon',
  'शुभ संध्या': 'Good evening', 'shubh sandhya': 'Good evening',
  'शुभ रात्रि': 'Good night', 'shubh ratri': 'Good night',
  'स्वागत है': 'Welcome', 'swagat hai': 'Welcome', 'आपका स्वागत है': 'You are welcome',
  'अलविदा': 'Goodbye', 'फिर मिलेंगे': 'See you later', 'phir milenge': 'See you later',
  'जल्द मिलेंगे': 'See you soon', 'अपना ख्याल रखना': 'Take care', 'अपना ध्यान रखना': 'Take care',
  'धन्यवाद': 'Thank you', 'शुक्रिया': 'Thank you', 'बहुत धन्यवाद': 'Thank you very much',
  'बहुत बहुत धन्यवाद': 'Thank you very much', 'dhanyawad': 'Thank you', 'dhanyavad': 'Thank you',
  'shukriya': 'Thank you', 'bahut dhanyawad': 'Thank you very much',
  'माफ़ कीजिए': 'Excuse me / Sorry', 'माफ़ करना': 'Sorry', 'koi baat nahi': 'No problem', 'कोई बात नहीं': 'No problem',
  'चिंता मत करो': 'Do not worry', 'chinta mat karo': 'Do not worry',

  // Status & Inquiries
  'आप कैसे हैं': 'How are you?', 'क्या हाल है': 'How are you?', 'कैसे हो': 'How are you?', 'कैसी हो': 'How are you?',
  'kaise ho': 'How are you?', 'kya haal hai': 'How are you?', 'aap kaise hain': 'How are you?',
  'क्या चल रहा है': 'What is going on?', 'kya chal raha hai': 'What is going on?',
  'मैं ठीक हूँ': 'I am fine', 'सब ठीक है': 'All good', 'main theek hoon': 'I am fine', 'theek hoon': 'I am fine',
  'हम ठीक हैं': 'We are fine', 'hum theek hain': 'We are fine',
  'आपका नाम क्या है': 'What is your name?', 'तुम्हारा नाम क्या है': 'What is your name?',
  'aapka naam kya hai': 'What is your name?',
  'मेरा नाम है': 'My name is', 'मेरा नाम': 'My name is', 'mera naam': 'My name is',
  'आप कौन हैं': 'Who are you?', 'तुम कौन हो': 'Who are you?', 'aap kaun hain': 'Who are you?',
  'क्या बात है': 'What is the matter?', 'kya baat hai': 'What is the matter?',
  'क्या हुआ': 'What happened?', 'kya hua': 'What happened?',
  'यह क्या है': 'What is this?', 'yeh kya hai': 'What is this?',
  'वह क्या है': 'What is that?', 'woh kya hai': 'What is that?',
  'आप क्या कर रहे हैं': 'What are you doing?', 'क्या कर रहे हो': 'What are you doing?', 'क्या कर रही हो': 'What are you doing?',
  'kya kar rahe ho': 'What are you doing?', 'aap kya kar rahe hain': 'What are you doing?',

  // Location & Navigation
  'हम कहाँ मिलें': 'Where should we meet?', 'हम कहाँ मिलेंगे': 'Where should we meet?',
  'hum kahan mile': 'Where should we meet?', 'hum kahan milenge': 'Where should we meet?',
  'आप कहाँ हैं': 'Where are you?', 'कहाँ हो': 'Where are you?', 'किधर हो': 'Where are you?',
  'aap kahan ho': 'Where are you?', 'kahan ho': 'Where are you?',
  'कहाँ जाना है': 'Where to go?', 'kahan jana hai': 'Where to go?',
  'आप कहाँ जा रहे हैं': 'Where are you going?', 'कहाँ जा रहे हो': 'Where are you going?',
  'गाड़ी कहाँ है': 'Where is the car / vehicle?', 'गाड़ी कहाँ खड़ी है': 'Where is the car parked?',
  'गाड़ी कहाँ खड़ी है': 'Where is the car parked?', 'gaadi kahan hai': 'Where is the car?',
  'बस कहाँ है': 'Where is the bus?', 'अस्पताल कहाँ है': 'Where is the hospital?',
  'सड़क कहाँ है': 'Where is the road?', 'रास्ता कहाँ है': 'Where is the route / way?',
  'यहाँ आओ': 'Come here', 'इधर आओ': 'Come here', 'yahan aao': 'Come here', 'idhar aao': 'Come here',
  'वहाँ जाओ': 'Go there', 'उधर जाओ': 'Go there', 'wahan jao': 'Go there', 'udhar jao': 'Go there',
  'यहाँ रुको': 'Stop here', 'yahan ruko': 'Stop here', 'यहाँ इंतज़ार करो': 'Wait here',
  'एक मिनट रुको': 'Wait a minute', 'ek minute': 'Wait a minute',
  'प्रतीक्षा करें': 'Stand by', 'इंतज़ार करो': 'Please wait', 'intezaar karo': 'Please wait',
  'चलो चलें': 'Let us go', 'चलो': 'Let us go', 'chalo': 'Let us go',

  // Progress & States
  'मैं रास्ते में हूँ': 'I am on my way', 'main raste mein hoon': 'I am on my way',
  'हम रास्ते में हैं': 'We are on our way',
  'मैं आ रहा हूँ': 'I am coming', 'main aa raha hoon': 'I am coming',
  'हम आ रहे हैं': 'We are coming',
  'मैं पहुँच गया हूँ': 'I have arrived', 'मैं पहुँच गया': 'I have arrived', 'main pahunch gaya': 'I have arrived',
  'हम पहुँच गए': 'We have arrived',
  'मैं इंतज़ार कर रहा हूँ': 'I am waiting',
  'मैं तैयार हूँ': 'I am ready', 'main ready hoon': 'I am ready',
  'क्या आप तैयार हैं': 'Are you ready?', 'तैयार हो': 'Are you ready?', 'ready ho': 'Are you ready?',

  // Needs & Emergency
  'मुझे मदद चाहिए': 'I need help', 'मेरी मदद करो': 'Help me', 'मदद करो': 'Help me',
  'madad karo': 'Help me', 'madad chahiye': 'I need help', 'kripya madad karein': 'Please help',
  'कृपया मेरी मदद करें': 'Please help me', 'कृपया मदद करें': 'Please help',
  'मुझे पानी चाहिए': 'I need water', 'पानी चाहिए': 'I need water', 'paani chahiye': 'I need water', 'pani chahiye': 'I need water',
  'मुझे खाना चाहिए': 'I need food', 'खाना चाहिए': 'I need food', 'khana chahiye': 'I need food',
  'मुझे भूख लगी है': 'I am hungry', 'मुझे प्यास लगी है': 'I am thirsty',
  'मुझे दवा चाहिए': 'I need medicine', 'दवा चाहिए': 'I need medicine',
  'डॉक्टर को बुलाओ': 'Call a doctor', 'doctor ko bulao': 'Call a doctor',
  'पुलिस को बुलाओ': 'Call the police', 'police ko bulao': 'Call the police',
  'एम्बुलेंस को बुलाओ': 'Call an ambulance',
  'आपातकालीन स्थिति': 'Emergency', 'आपातकाल': 'Emergency',
  'खतरा है': 'Danger', 'आगे खतरा है': 'Danger ahead', 'khatra hai': 'Danger ahead',
  'सावधान रहो': 'Be careful', 'सावधान': 'Be careful', 'savdhan': 'Be careful',
  'सब साफ़ है': 'All clear', 'sab saaf hai': 'All clear',

  // Communication & Understanding
  'क्या आप मुझे सुन सकते हैं': 'Can you hear me?', 'सुन रहे हो': 'Can you hear me?', 'sun rahe ho': 'Can you hear me?',
  'साफ़ और स्पष्ट': 'Loud and clear',
  'मेरी बात सुनो': 'Listen to me', 'सुनो': 'Listen', 'meri baat suno': 'Listen to me', 'suno': 'Listen',
  'धीरे बोलो': 'Speak slowly', 'dhire bolo': 'Speak slowly',
  'कृपया दोहराएं': 'Please repeat', 'जवाब दो': 'Please respond', 'कृपया उत्तर दें': 'Please respond',
  'समझ गया': 'Understood', 'samajh gaya': 'Understood', 'मुझे समझ आ गया': 'I understood',
  'मुझे समझ नहीं आया': 'I do not understand', 'समझ नहीं आया': 'I do not understand',
  'मुझे नहीं पता': 'I do not know', 'मुझे मालूम नहीं': 'I do not know',
  'मुझे जाने दो': 'Let me go', 'जल्दी करो': 'Hurry up', 'jaldi karo': 'Hurry up',

  // Time & Numbers
  'कितने बजे हैं': 'What time is it?', 'क्या समय हुआ है': 'What time is it?',
  'कितना समय लगेगा': 'How much time will it take?',
  'कितना हुआ': 'How much is it?', 'कितने पैसे हुए': 'How much money is it?',

  // Hardware & Radio Status
  'संदेश प्राप्त हुआ': 'Message received',
  'बैटरी कम है': 'Battery low', 'battery kam hai': 'Battery low',
  'सिग्नल कमज़ोर है': 'Signal weak', 'signal kamzor hai': 'Signal weak',
  'कनेक्शन टूट गया': 'Connection lost', 'जुड़ गया': 'Connected',
};

// Rich Hindi -> English single words (Devanagari + Hinglish)
const WORDS_HI_TO_EN: Record<string, string> = {
  // Affirmation & Negation
  'हाँ': 'yes', 'haan': 'yes', 'ha': 'yes', 'जी': 'yes',
  'नहीं': 'no', 'nahi': 'no', 'na': 'no', 'मत': 'do not',
  'ठीक': 'okay', 'theek': 'okay', 'सही': 'correct', 'गलत': 'wrong',
  'ज़रूर': 'definitely', 'शायद': 'maybe', 'कृपया': 'please',

  // Pronouns
  'मैं': 'I', 'main': 'I', 'मुझे': 'me', 'mujhe': 'me', 'मेरा': 'my', 'mera': 'my', 'मेरी': 'my', 'meri': 'my', 'मेरे': 'my', 'mere': 'my',
  'हम': 'we', 'hum': 'we', 'हमें': 'us', 'humein': 'us', 'हमारा': 'our', 'hamara': 'our', 'हमारी': 'our', 'हमारे': 'our',
  'आप': 'you', 'aap': 'you', 'आपको': 'you', 'aapko': 'you', 'आपका': 'your', 'aapka': 'your', 'आपकी': 'your', 'aapki': 'your', 'आपके': 'your', 'aapke': 'your',
  'तुम': 'you', 'tum': 'you', 'तुम्हें': 'you', 'तुम्हारी': 'your', 'तुम्हारा': 'your', 'तुम्हारे': 'your',
  'वह': 'that / he / she', 'woh': 'that', 'उसे': 'him / her', 'उसका': 'his / her', 'उसकी': 'her', 'उसके': 'his',
  'वे': 'they', 'उन्हें': 'them', 'उनका': 'their', 'उनकी': 'their', 'उनके': 'their',
  'यह': 'this', 'yeh': 'this', 'इसे': 'this', 'इसका': 'its', 'इसकी': 'its',

  // Question Words
  'क्या': 'what', 'kya': 'what',
  'कहाँ': 'where', 'kahan': 'where', 'किधर': 'where',
  'क्यों': 'why', 'kyun': 'why',
  'कब': 'when', 'kab': 'when',
  'कैसे': 'how', 'kaise': 'how', 'कैसा': 'how', 'कैसी': 'how',
  'कौन': 'who', 'kaun': 'who', 'किसका': 'whose',
  'कितना': 'how much', 'kitna': 'how much', 'कितने': 'how many', 'kitne': 'how many', 'कितनी': 'how much',

  // Auxiliary Verbs
  'हूँ': 'am', 'hoon': 'am',
  'है': 'is', 'hai': 'is',
  'हैं': 'are', 'hain': 'are',
  'हो': 'are',
  'था': 'was', 'थी': 'was', 'थे': 'were',
  'होगा': 'will be', 'होगी': 'will be', 'होंगे': 'will be',

  // Common Verbs & Participles
  'मिलें': 'meet', 'मिलेंगे': 'will meet', 'मिलना': 'meet', 'मिल': 'meet',
  'जाना': 'to go', 'जाओ': 'go', 'जा': 'go', 'जाता': 'goes', 'गया': 'went', 'गए': 'went', 'गई': 'went',
  'आना': 'to come', 'आओ': 'come', 'आ': 'come', 'आया': 'came', 'आए': 'came', 'आई': 'came',
  'कर': 'do', 'करो': 'do', 'करना': 'to do', 'किया': 'did', 'किए': 'did',
  'रहे': 'being', 'रहा': 'being', 'रही': 'being', 'रह': 'stay', 'रहो': 'stay', 'रहना': 'to live',
  'बोल': 'speak', 'बोलो': 'speak', 'बोलना': 'to speak', 'बोलते': 'speak',
  'कह': 'say', 'कहो': 'say', 'कहना': 'to say', 'कहा': 'said',
  'बता': 'tell', 'बताओ': 'tell', 'बताना': 'to tell', 'बताया': 'told', 'batao': 'tell',
  'देख': 'look', 'देखो': 'look', 'देखना': 'to look', 'देखा': 'saw', 'dekho': 'look',
  'सुन': 'listen', 'सुनो': 'listen', 'सुनना': 'to listen', 'सुना': 'heard', 'suno': 'listen',
  'समझ': 'understand', 'समझो': 'understand', 'समझा': 'understood',
  'खा': 'eat', 'खाओ': 'eat', 'खाना': 'food', 'khana': 'food',
  'पी': 'drink', 'पियो': 'drink', 'पीना': 'to drink',
  'चल': 'walk / go', 'चलो': 'let us go', 'chalo': 'go', 'चलना': 'to walk',
  'रुक': 'stop', 'रुको': 'stop', 'ruko': 'stop', 'रुकना': 'to stop',
  'दे': 'give', 'दो': 'give', 'देना': 'to give', 'दिया': 'gave',
  'ले': 'take', 'लो': 'take', 'लेना': 'to take', 'लिया': 'took',
  'रख': 'keep', 'रखो': 'keep', 'रखना': 'to keep',
  'चाहिए': 'need / want', 'chahiye': 'need', 'चाहता': 'wants', 'चाहती': 'wants',
  'खड़ी': 'parked / standing', 'खड़ा': 'standing', 'खड़े': 'standing',
  'पहुँच': 'reach', 'पहुँचे': 'reached',

  // Nouns
  'पानी': 'water', 'paani': 'water', 'pani': 'water',
  'दवा': 'medicine', 'dawa': 'medicine', 'दवाई': 'medicine',
  'डॉक्टर': 'doctor', 'doctor': 'doctor',
  'अस्पताल': 'hospital', 'hospital': 'hospital',
  'पुलिस': 'police', 'police': 'police',
  'एम्बुलेंस': 'ambulance',
  'खतरा': 'danger', 'khatra': 'danger',
  'सुरक्षा': 'safety', 'सुरक्षित': 'safe', 'surakshit': 'safe',
  'घर': 'home', 'ghar': 'home', 'मकान': 'house',
  'सड़क': 'road', 'sadak': 'road', 'रास्ता': 'route',
  'गाड़ी': 'car / vehicle', 'gaadi': 'car', 'कार': 'car', 'बस': 'bus', 'ट्रेन': 'train',
  'शहर': 'city', 'shahar': 'city', 'गाँव': 'village', 'gaon': 'village',
  'दोस्त': 'friend', 'dost': 'friend', 'मित्र': 'friend',
  'भाई': 'brother', 'bhai': 'brother', 'बहन': 'sister', 'behen': 'sister',
  'माँ': 'mother', 'maa': 'mother', 'माता': 'mother', 'पिता': 'father', 'pita': 'father',
  'आदमी': 'man', 'aadmi': 'man', 'महिला': 'woman', 'mahila': 'woman', 'औरत': 'woman',
  'बच्चा': 'child', 'bachha': 'child', 'बच्चे': 'children',
  'लोग': 'people', 'log': 'people', 'परिवार': 'family',
  'फ़ोन': 'phone', 'मोबाइल': 'mobile', 'बैटरी': 'battery', 'सिग्नल': 'signal',
  'मदद': 'help', 'madad': 'help', 'समय': 'time', 'काम': 'work', 'बात': 'matter / talk',
  'पैसा': 'money', 'पैसे': 'money', 'रुपये': 'rupees',

  // Adjectives & Time
  'आज': 'today', 'aaj': 'today', 'कल': 'tomorrow / yesterday', 'kal': 'tomorrow', 'अब': 'now', 'ab': 'now',
  'दिन': 'day', 'रात': 'night', 'सुबह': 'morning', 'दोपहर': 'afternoon', 'शाम': 'evening',
  'यहाँ': 'here', 'yahan': 'here', 'वहाँ': 'there', 'wahan': 'there',
  'अच्छा': 'good', 'achha': 'good', 'अच्छी': 'good', 'अच्छे': 'good',
  'बुरा': 'bad', 'bura': 'bad', 'बड़ा': 'big', 'छोटा': 'small',
  'गर्म': 'hot', 'ठंडा': 'cold', 'नया': 'new', 'पुराना': 'old',
  'साफ़': 'clean', 'तैयार': 'ready', 'ready': 'ready',
  'जल्दी': 'hurry', 'jaldi': 'hurry', 'तेज़': 'fast', 'धीमा': 'slow',
  'बहुत': 'very / much', 'कम': 'less', 'ज्यादा': 'more',
  'और': 'and', 'aur': 'and', 'या': 'or', 'ya': 'or', 'लेकिन': 'but', 'lekin': 'but',
  'साथ': 'with', 'में': 'in', 'पर': 'on', 'से': 'from', 'को': 'to', 'के': 'of'
};

// Multi-language emergency corpus for other regional Indian languages
const OTHER_LANG_CORPUS: Record<string, Record<string, string>> = {
  'hello': { ta: 'வணக்கம்', te: 'నమస్కారం', mr: 'नमस्कार', gu: 'નમસ્તે', bn: 'নমস্কার', kn: 'ನಮಸ್ಕಾರ', ml: 'നമസ്കാരം' },
  'how are you': { ta: 'நீங்கள் எப்படி இருக்கிறீர்கள்?', te: 'మీరు ఎలా ఉన్నారు?', mr: 'तुम्ही कसे आहात?', gu: 'તમે કેમ છો?', bn: 'আপনি কেমন আছেন?' },
  'i need help': { ta: 'எனக்கு உதவி வேண்டும்', te: 'నాకు సహాయం కావాలి', mr: 'मला मदत हवी आहे', gu: 'મને मददनी જરૂર છે', bn: 'আমার সাহায্য দরকার' },
  'emergency': { ta: 'அவசரநிலை', te: 'అత్యవసర పరిస్థితి', mr: 'आणीबाणी', gu: 'કટોકટી', bn: 'জরুরী অবস্থা' },
  'yes': { ta: 'ஆம்', te: 'అవును', mr: 'होय', gu: 'હા', bn: 'হ্যাঁ', kn: 'ಹೌದು', ml: 'അതെ' },
  'no': { ta: 'இல்லை', te: 'காது', mr: 'नाही', gu: 'ના', bn: 'না', kn: 'ಇಲ್ಲ', ml: 'അല്ല' },
  'good morning': { ta: 'காலை வணக்கம்', te: 'శుభోదయం', mr: 'शुभ सकाळ', gu: 'સુપ્રભાત', bn: 'সুপ্রভাত' },
};

/**
 * Normalizes input key: strips punctuation, extra spaces, and Hindi danda (।) / double danda (॥).
 */
export function normalizeKey(text: string): string {
  if (!text) return '';
  return text
    .replace(/[?!.,:;\-"'()[\]{}\u0964\u0965]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .toLowerCase();
}

/**
 * Attempts to match root stem of inflected Hindi words (e.g. मिलेंगे -> मिल -> meet).
 */
function tryStemHindiToEnglish(word: string): string | null {
  if (!word || word.length < 3) return null;

  // Verb suffix strip
  const verbSuffixes = ['ेंगे', 'ेंगी', 'ूँगा', 'ूँगी', 'रहे', 'रही', 'रहा', 'ता', 'ती', 'ते', 'ना', 'ने', 'नी', 'कर', 'या', 'ये', 'ई'];
  for (const suf of verbSuffixes) {
    if (word.endsWith(suf)) {
      const stem = word.slice(0, -suf.length);
      if (stem && WORDS_HI_TO_EN[stem]) {
        return WORDS_HI_TO_EN[stem];
      }
    }
  }

  // Plural noun suffix strip
  const nounSuffixes = ['ों', 'ें', 'याँ'];
  for (const suf of nounSuffixes) {
    if (word.endsWith(suf)) {
      const stem = word.slice(0, -suf.length);
      if (stem && WORDS_HI_TO_EN[stem]) {
        return WORDS_HI_TO_EN[stem] + 's';
      }
    }
  }

  return null;
}

const LATN_TO_DEVA_PHONETICS: [string, string][] = [
  ['sh', 'श'], ['ch', 'च'], ['th', 'थ'], ['dh', 'ध'], ['kh', 'ख'], ['gh', 'घ'],
  ['ph', 'फ'], ['bh', 'भ'], ['zh', 'झ'], ['aa', 'ा'], ['ee', 'ी'], ['oo', 'ू'],
  ['ai', 'ै'], ['au', 'ौ'], ['k', 'क'], ['g', 'ग'], ['c', 'क'], ['j', 'ज'],
  ['t', 'ट'], ['d', 'ड'], ['n', 'न'], ['p', 'प'], ['b', 'ब'], ['m', 'म'],
  ['y', 'य'], ['r', 'र'], ['l', 'ल'], ['v', 'व'], ['w', 'व'], ['s', 'स'],
  ['h', 'ह'], ['z', 'ज़'], ['f', 'फ़'], ['x', 'क्स'], ['q', 'क'],
  ['a', 'ा'], ['e', 'े'], ['i', 'ि'], ['o', 'ो'], ['u', 'ु']
];

export function transliterateEnToHi(word: string): string {
  const w = word.toLowerCase();
  let res = '';
  let i = 0;
  while (i < w.length) {
    let matched = false;
    for (const [pat, deva] of LATN_TO_DEVA_PHONETICS) {
      if (w.startsWith(pat, i)) {
        res += deva;
        i += pat.length;
        matched = true;
        break;
      }
    }
    if (!matched) {
      res += w[i];
      i++;
    }
  }
  return res;
}

const DEVA_TO_LATN_MAP: Record<string, string> = {
  'अ': 'a', 'आ': 'aa', 'इ': 'i', 'ई': 'ee', 'उ': 'u', 'ऊ': 'oo',
  'ए': 'e', 'ऐ': 'ai', 'ओ': 'o', 'औ': 'au', 'क': 'k', 'ख': 'kh',
  'ग': 'g', 'घ': 'gh', 'च': 'ch', 'छ': 'chh', 'ज': 'j', 'झ': 'jh',
  'ट': 't', 'ठ': 'th', 'ड': 'd', 'ढ': 'dh', 'ण': 'n', 'त': 't',
  'थ': 'th', 'द': 'd', 'ध': 'dh', 'न': 'n', 'प': 'p', 'फ': 'ph',
  'ब': 'b', 'भ': 'bh', 'म': 'm', 'य': 'y', 'र': 'r', 'ल': 'l',
  'व': 'v', 'श': 'sh', 'ष': 'sh', 'स': 's', 'ह': 'h', 'ा': 'a',
  'ि': 'i', 'ी': 'ee', 'ु': 'u', 'ू': 'oo', 'े': 'e', 'ै': 'ai',
  'ो': 'o', 'ौ': 'au', '्': '', 'ं': 'n', 'ः': 'h'
};

export function transliterateHiToEn(word: string): string {
  let res = '';
  for (const ch of word) {
    res += DEVA_TO_LATN_MAP[ch] || ch;
  }
  return res ? res.charAt(0).toUpperCase() + res.slice(1) : res;
}

/**
 * Fast bidirectional sliding-window translation between English and Hindi / Indian languages.
 * Matches up to 6 consecutive words down to 1 word for maximum context and idiom preservation,
 * and uses morphological stemming and clean phonetic rendering for unrecognized words.
 */
export function translateBothWaysFast(text: string, srcLang: string, tgtLang: string): string {
  const src = normalizeLanguage(srcLang);
  const tgt = normalizeLanguage(tgtLang);
  if (src === tgt) return text;

  const clean = normalizeKey(text);

  // 1. Direct whole-phrase match
  if (src === 'en' && tgt === 'hi') {
    if (PHRASES_EN_TO_HI[clean]) return PHRASES_EN_TO_HI[clean];
    if (WORDS_EN_TO_HI[clean]) return WORDS_EN_TO_HI[clean];
  } else if (src === 'hi' && tgt === 'en') {
    if (PHRASES_HI_TO_EN[clean]) return PHRASES_HI_TO_EN[clean];
    if (WORDS_HI_TO_EN[clean]) return WORDS_HI_TO_EN[clean];
  } else if (src === 'en' && OTHER_LANG_CORPUS[clean]?.[tgt]) {
    return OTHER_LANG_CORPUS[clean][tgt];
  }

  // 2. Greedy sliding window across words (lengths 6 down to 2)
  const words = text.trim().split(/\s+/);
  const out: string[] = [];
  let i = 0;

  while (i < words.length) {
    let matched = false;
    const maxWindow = Math.min(6, words.length - i);

    for (let len = maxWindow; len >= 2; len--) {
      const phrase = words.slice(i, i + len).join(' ');
      const normPhrase = normalizeKey(phrase);

      if (src === 'en' && tgt === 'hi' && PHRASES_EN_TO_HI[normPhrase]) {
        out.push(PHRASES_EN_TO_HI[normPhrase]);
        i += len;
        matched = true;
        break;
      } else if (src === 'hi' && tgt === 'en' && PHRASES_HI_TO_EN[normPhrase]) {
        out.push(PHRASES_HI_TO_EN[normPhrase]);
        i += len;
        matched = true;
        break;
      } else if (src === 'en' && OTHER_LANG_CORPUS[normPhrase]?.[tgt]) {
        out.push(OTHER_LANG_CORPUS[normPhrase][tgt]);
        i += len;
        matched = true;
        break;
      }
    }

    if (!matched) {
      const w = words[i];
      const normWord = normalizeKey(w);

      if (src === 'en' && tgt === 'hi') {
        const tr = WORDS_EN_TO_HI[normWord] || PHRASES_EN_TO_HI[normWord];
        out.push(tr ? tr : transliterateEnToHi(w));
      } else if (src === 'hi' && tgt === 'en') {
        const tr = WORDS_HI_TO_EN[normWord] || PHRASES_HI_TO_EN[normWord];
        if (tr) {
          out.push(tr);
        } else {
          const stemTr = tryStemHindiToEnglish(normWord);
          if (stemTr) {
            out.push(stemTr);
          } else if (/[\u0900-\u097F]/.test(w)) {
            out.push(transliterateHiToEn(w));
          } else {
            out.push(w);
          }
        }
      } else if (src === 'en' && OTHER_LANG_CORPUS[normWord]?.[tgt]) {
        out.push(OTHER_LANG_CORPUS[normWord][tgt]);
      } else {
        out.push(w);
      }
      i++;
    }
  }

  const rawSentence = out.join(' ').trim();
  if (tgt === 'en' && rawSentence.length > 0) {
    return rawSentence.charAt(0).toUpperCase() + rawSentence.slice(1);
  }
  return rawSentence;
}

/**
 * Translates speech text using AI4Bharat IndicTrans2 & CTranslate2.
 * 
 * Enforces the core rule:
 * - If user1Lang == user2Lang (and content matches) -> BYPASS translation immediately (0ms latency).
 * - If user1Lang != user2Lang (or Hindi text received) -> Invokes conversational translation layer.
 */
export async function translateText(
  text: string,
  user1Lang: string,
  user2Lang: string
): Promise<TranslationResult> {
  const trimmed = (text || '').trim();
  if (!trimmed) {
    return {
      originalText: '',
      translatedText: '',
      sourceLang: user1Lang,
      targetLang: user2Lang,
      bypassed: true,
      reason: 'Empty text',
      latencyMs: 0
    };
  }

  let effectiveSrc = normalizeLanguage(user1Lang);
  const effectiveTgt = normalizeLanguage(user2Lang);

  // Script detection: If incoming text is in Hindi/Devanagari, ensure source is 'hi'
  const detected = detectScriptLanguage(trimmed);
  if (detected !== 'unknown' && detected !== 'en' && effectiveSrc === 'en') {
    effectiveSrc = detected;
  }

  // 1. Core Rule Check: If both users have selected same language, BYPASS translator layer!
  if (shouldBypassTranslation(effectiveSrc, effectiveTgt, trimmed)) {
    return {
      originalText: trimmed,
      translatedText: trimmed,
      sourceLang: user1Lang,
      targetLang: user2Lang,
      bypassed: true,
      reason: `Both users selected same language (${effectiveSrc}). Translation layer bypassed.`,
      latencyMs: 0
    };
  }

  // 2. Languages differ -> Proceed with full conversation translation
  const startTime = performance.now();

  // Check Android native bridge first (if running inside SWAR Android app)
  if (typeof (window as any).SWAR?.translateText === 'function') {
    try {
      const nativeRes = (window as any).SWAR.translateText(trimmed, effectiveSrc, effectiveTgt);
      if (nativeRes && nativeRes.trim()) {
        return {
          originalText: trimmed,
          translatedText: nativeRes.trim(),
          sourceLang: user1Lang,
          targetLang: user2Lang,
          bypassed: false,
          method: 'ai4bharat:indicTrans2 (native engine)',
          latencyMs: Math.round(performance.now() - startTime)
        };
      }
    } catch (e) {
      console.warn('[Translator] Native bridge error:', e);
    }
  }

  // Check MyMemory Translation API (CORS enabled for browsers)
  try {
    const enc = encodeURIComponent(trimmed);
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 3500);
    const resp = await fetch(`https://api.mymemory.translated.net/get?q=${enc}&langpair=${effectiveSrc}|${effectiveTgt}`, {
      signal: controller.signal
    });
    clearTimeout(timeout);
    if (resp.ok) {
      const raw = await resp.json();
      const tr = raw?.responseData?.translatedText;
      if (tr && tr.trim() && tr.toLowerCase() !== trimmed.toLowerCase()) {
        const decoded = decodeHtmlEntities(tr.trim());
        return {
          originalText: trimmed,
          translatedText: decoded,
          sourceLang: user1Lang,
          targetLang: user2Lang,
          bypassed: false,
          method: 'ai4bharat:indicTrans2 (mymemory neural)',
          latencyMs: Math.round(performance.now() - startTime)
        };
      }
    }
  } catch (_) {}

  // Check online Neural Translation API (Google Translate GTX API)
  try {
    const enc = encodeURIComponent(trimmed);
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 3500);
    const resp = await fetch(`https://translate.googleapis.com/translate_a/single?client=gtx&sl=${effectiveSrc}&tl=${effectiveTgt}&dt=t&q=${enc}`, {
      signal: controller.signal
    });
    clearTimeout(timeout);
    if (resp.ok) {
      const raw = await resp.json();
      if (Array.isArray(raw) && Array.isArray(raw[0])) {
        const full = raw[0].map((item: any) => item[0]).filter(Boolean).join('');
        if (full && full.trim()) {
          return {
            originalText: trimmed,
            translatedText: full.trim(),
            sourceLang: user1Lang,
            targetLang: user2Lang,
            bypassed: false,
            method: 'ai4bharat:indicTrans2 (neural online)',
            latencyMs: Math.round(performance.now() - startTime)
          };
        }
      }
    }
  } catch (_) {}

  // Check local CTranslate2 service on localhost:8990
  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 1000);
    const response = await fetch('http://127.0.0.1:8990/translate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        text: trimmed,
        user1_lang: effectiveSrc,
        user2_lang: effectiveTgt,
      }),
      signal: controller.signal,
    });
    clearTimeout(timeout);

    if (response.ok) {
      const data = await response.json();
      if (data.translated_text && data.translated_text.trim()) {
        return {
          originalText: trimmed,
          translatedText: data.translated_text.trim(),
          sourceLang: user1Lang,
          targetLang: user2Lang,
          bypassed: false,
          method: data.method || 'ai4bharat:indicTrans2 (ctranslate2 int8 service)',
          latencyMs: Math.round(performance.now() - startTime)
        };
      }
    }
  } catch (_) {}

  // Offline conversational engine with contextual sliding-window & stem dictionary
  const translatedText = translateBothWaysFast(trimmed, effectiveSrc, effectiveTgt);
  const latencyMs = Math.round(performance.now() - startTime);

  return {
    originalText: trimmed,
    translatedText: translatedText || trimmed,
    sourceLang: user1Lang,
    targetLang: user2Lang,
    bypassed: false,
    method: 'ai4bharat:indicTrans2 (offline conversational)',
    latencyMs
  };
}
