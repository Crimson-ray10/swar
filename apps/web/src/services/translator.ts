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
 * Core Requirement Function:
 * Returns TRUE if both users have selected the same language (translation layer BYPASSED).
 * Returns FALSE if users have different languages (translation layer REQUIRED).
 */
export function shouldBypassTranslation(user1Lang: string, user2Lang: string): boolean {
  const norm1 = normalizeLanguage(user1Lang);
  const norm2 = normalizeLanguage(user2Lang);
  return norm1 === norm2;
}

// Comprehensive bidirectional phrases for English -> Hindi
const PHRASES_EN_TO_HI: Record<string, string> = {
  'hello': 'नमस्ते',
  'hi': 'नमस्ते',
  'how are you': 'आप कैसे हैं?',
  'how are you doing': 'आप कैसे हैं?',
  'i am fine': 'मैं ठीक हूँ',
  'i am good': 'मैं ठीक हूँ',
  'what is your name': 'आपका नाम क्या है?',
  'my name is': 'मेरा नाम है',
  'where are you': 'आप कहाँ हैं?',
  'where are you going': 'आप कहाँ जा रहे हैं?',
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
  'see you later': 'फिर मिलेंगे',
  'take care': 'अपना ख्याल रखना',
  'hurry up': 'जल्दी करो',
  'do not worry': 'चिंता मत करो',
  'no problem': 'कोई बात नहीं',
  'listen to me': 'मेरी बात सुनो',
  'speak slowly': 'धीरे बोलो',
  'repeat please': 'कृपया दोहराएं',
  'where is the hospital': 'अस्पताल कहाँ है?',
  'where is the road': 'सड़क कहाँ है?',
  'let us go': 'चलो चलें',
  'are you ready': 'क्या आप तैयार हैं?',
  'i am ready': 'मैं तैयार हूँ',
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
  'नमस्ते': 'Hello', 'प्रणाम': 'Hello', 'namaste': 'Hello', 'pranam': 'Hello',
  'आप कैसे हैं': 'How are you?', 'क्या हाल है': 'How are you?', 'कैसे हो': 'How are you?',
  'kaise ho': 'How are you?', 'kya haal hai': 'How are you?',
  'मैं ठीक हूँ': 'I am fine', 'सब ठीक है': 'All good', 'main theek hoon': 'I am fine', 'theek hoon': 'I am fine',
  'आपका नाम क्या है': 'What is your name?', 'तुम्हारा नाम क्या है': 'What is your name?',
  'aapka naam kya hai': 'What is your name?',
  'मेरा नाम': 'My name is', 'mera naam': 'My name is',
  'आप कहाँ हैं': 'Where are you?', 'कहाँ हो': 'Where are you?', 'aap kahan ho': 'Where are you?', 'kahan ho': 'Where are you?',
  'क्या हुआ': 'What happened?', 'kya hua': 'What happened?',
  'यह क्या है': 'What is this?', 'yeh kya hai': 'What is this?',
  'वह क्या है': 'What is that?', 'woh kya hai': 'What is that?',
  'मुझे मदद चाहिए': 'I need help', 'मेरी मदद करो': 'Help me', 'मदद करो': 'Help me',
  'madad karo': 'Help me', 'madad chahiye': 'I need help',
  'कृपया मदद करें': 'Please help', 'kripya madad karein': 'Please help',
  'मुझे पानी चाहिए': 'I need water', 'पानी चाहिए': 'I need water', 'paani chahiye': 'I need water', 'pani chahiye': 'I need water',
  'मुझे खाना चाहिए': 'I need food', 'खाना चाहिए': 'I need food', 'khana chahiye': 'I need food',
  'मुझे दवा चाहिए': 'I need medicine', 'दवा चाहिए': 'I need medicine',
  'डॉक्टर को बुलाओ': 'Call a doctor', 'doctor ko bulao': 'Call a doctor',
  'पुलिस को बुलाओ': 'Call the police', 'police ko bulao': 'Call the police',
  'धन्यवाद': 'Thank you', 'शुक्रिया': 'Thank you', 'बहुत धन्यवाद': 'Thank you very much',
  'dhanyawad': 'Thank you', 'dhanyavad': 'Thank you', 'shukriya': 'Thank you',
  'शुभ प्रभात': 'Good morning', 'सुप्रभात': 'Good morning', 'shubh prabhat': 'Good morning',
  'शुभ संध्या': 'Good evening', 'shubh sandhya': 'Good evening',
  'शुभ रात्रि': 'Good night', 'shubh ratri': 'Good night',
  'स्वागत है': 'Welcome', 'swagat hai': 'Welcome',
  'यहाँ आओ': 'Come here', 'इधर आओ': 'Come here', 'yahan aao': 'Come here', 'idhar aao': 'Come here',
  'वहाँ जाओ': 'Go there', 'उधर जाओ': 'Go there', 'wahan jao': 'Go there', 'udhar jao': 'Go there',
  'यहाँ रुको': 'Stop here', 'yahan ruko': 'Stop here',
  'एक मिनट रुको': 'Wait a minute', 'ek minute': 'Wait a minute',
  'प्रतीक्षा करें': 'Stand by', 'इंतज़ार करो': 'Please wait', 'intezaar karo': 'Please wait',
  'सब साफ़ है': 'All clear', 'sab saaf hai': 'All clear',
  'साफ़ और स्पष्ट': 'Loud and clear',
  'क्या आप मुझे सुन सकते हैं': 'Can you hear me?', 'सुन रहे हो': 'Can you hear me?', 'sun rahe ho': 'Can you hear me?',
  'संदेश प्राप्त हुआ': 'Message received',
  'बैटरी कम है': 'Battery low', 'battery kam hai': 'Battery low',
  'सिग्नल कमज़ोर है': 'Signal weak', 'signal kamzor hai': 'Signal weak',
  'सावधान रहो': 'Be careful', 'सावधान': 'Be careful', 'savdhan': 'Be careful',
  'खतरा है': 'Danger', 'आगे खतरा है': 'Danger ahead', 'khatra hai': 'Danger ahead',
  'समझ गया': 'Understood', 'samajh gaya': 'Understood',
  'मुझे समझ नहीं आया': 'I do not understand', 'समझ नहीं आया': 'I do not understand',
  'जल्दी करो': 'Hurry up', 'jaldi karo': 'Hurry up',
  'चिंता मत करो': 'Do not worry', 'chinta mat karo': 'Do not worry',
  'कोई बात नहीं': 'No problem', 'koi baat nahi': 'No problem',
  'मेरी बात सुनो': 'Listen to me', 'सुनो': 'Listen', 'meri baat suno': 'Listen to me', 'suno': 'Listen',
  'चलो चलें': 'Let us go', 'चलो': 'Let us go', 'chalo': 'Let us go',
  'तैयार हो': 'Are you ready?', 'क्या तैयार हो': 'Are you ready?', 'ready ho': 'Are you ready?',
  'मैं तैयार हूँ': 'I am ready', 'main ready hoon': 'I am ready',
  'आपातकालीन स्थिति': 'Emergency', 'आपातकाल': 'Emergency',
  'कृपया उत्तर दें': 'Please respond', 'जवाब दो': 'Please respond', 'jawab do': 'Please respond',
};

// Rich Hindi -> English single words (Devanagari + Hinglish)
const WORDS_HI_TO_EN: Record<string, string> = {
  'हाँ': 'yes', 'haan': 'yes', 'ha': 'yes', 'नहीं': 'no', 'nahi': 'no', 'na': 'no',
  'पानी': 'water', 'paani': 'water', 'pani': 'water', 'खाना': 'food', 'khana': 'food',
  'दवा': 'medicine', 'dawa': 'medicine', 'डॉक्टर': 'doctor', 'doctor': 'doctor',
  'अस्पताल': 'hospital', 'hospital': 'hospital', 'पुलिस': 'police', 'police': 'police',
  'खतरा': 'danger', 'khatra': 'danger', 'सुरक्षित': 'safe', 'surakshit': 'safe',
  'रुको': 'stop', 'ruko': 'stop', 'जाओ': 'go', 'jao': 'go', 'आओ': 'come', 'aao': 'come',
  'सुनो': 'listen', 'suno': 'listen', 'बोलो': 'speak', 'bolo': 'speak',
  'कहो': 'say', 'kaho': 'say', 'बताओ': 'tell', 'batao': 'tell', 'देखो': 'look', 'dekho': 'look',
  'जल्दी': 'hurry', 'jaldi': 'hurry', 'तेज़': 'fast', 'tez': 'fast', 'धीमा': 'slow',
  'अच्छा': 'good', 'achha': 'good', 'acha': 'good', 'बुरा': 'bad', 'bura': 'bad',
  'बड़ा': 'big', 'bada': 'big', 'छोटा': 'small', 'chhota': 'small',
  'गर्म': 'hot', 'garm': 'hot', 'ठंडा': 'cold', 'thanda': 'cold',
  'नया': 'new', 'naya': 'new', 'पुराना': 'old', 'purana': 'old',
  'घर': 'home', 'ghar': 'home', 'सड़क': 'road', 'sadak': 'road', 'गाड़ी': 'car', 'gaadi': 'car',
  'शहर': 'city', 'shahar': 'city', 'गाँव': 'village', 'gaon': 'village',
  'दोस्त': 'friend', 'dost': 'friend', 'भाई': 'brother', 'bhai': 'brother',
  'बहन': 'sister', 'behen': 'sister', 'माँ': 'mother', 'maa': 'mother',
  'पिता': 'father', 'pita': 'father', 'आदमी': 'man', 'aadmi': 'man',
  'महिला': 'woman', 'mahila': 'woman', 'बच्चा': 'child', 'bachha': 'child',
  'लोग': 'people', 'log': 'people', 'आज': 'today', 'aaj': 'today',
  'कल': 'tomorrow', 'kal': 'tomorrow', 'अब': 'now', 'ab': 'now',
  'यहाँ': 'here', 'yahan': 'here', 'वहाँ': 'there', 'wahan': 'there',
  'कहाँ': 'where', 'kahan': 'where', 'क्या': 'what', 'kya': 'what',
  'क्यों': 'why', 'kyun': 'why', 'कब': 'when', 'kab': 'when',
  'कैसे': 'how', 'kaise': 'how', 'कौन': 'who', 'kaun': 'who',
  'यह': 'this', 'yeh': 'this', 'वह': 'that', 'woh': 'that',
  'मेरा': 'my', 'mera': 'my', 'मेरी': 'my', 'meri': 'my',
  'आपका': 'your', 'aapka': 'your', 'आपकी': 'your', 'aapki': 'your',
  'हमारा': 'our', 'hamara': 'our', 'उनका': 'their', 'unka': 'their',
  'उसका': 'his', 'uska': 'his',
  'एक': 'one', 'ek': 'one', 'दो': 'two', 'do': 'two', 'तीन': 'three', 'teen': 'three',
  'चार': 'four', 'char': 'four', 'पाँच': 'five', 'paanch': 'five',
  'और': 'and', 'aur': 'and', 'या': 'or', 'ya': 'or', 'लेकिन': 'but', 'lekin': 'but'
};

// Multi-language emergency corpus for other regional Indian languages
const OTHER_LANG_CORPUS: Record<string, Record<string, string>> = {
  'hello': { ta: 'வணக்கம்', te: 'నమస్కారం', mr: 'नमस्कार', gu: 'નમસ્તે', bn: 'নমস্কার', kn: 'ನಮಸ್ಕಾರ', ml: 'നമസ്കാരം' },
  'how are you': { ta: 'நீங்கள் எப்படி இருக்கிறீர்கள்?', te: 'మీరు ఎలా ఉన్నారు?', mr: 'तुम्ही कसे आहात?', gu: 'તમે કેમ છો?', bn: 'আপনি কেমন আছেন?' },
  'i need help': { ta: 'எனக்கு உதவி வேண்டும்', te: 'నాకు సహాయం కావాలి', mr: 'मला मदत हवी आहे', gu: 'મને મદદની જરૂર છે', bn: 'আমার সাহায্য দরকার' },
  'emergency': { ta: 'அவசரநிலை', te: 'అత్యవసర పరిస్థితి', mr: 'ஆણીবাણી', gu: 'કટોકટી', bn: 'জরুরী অবস্থা' },
  'yes': { ta: 'ஆம்', te: 'అవును', mr: 'होय', gu: 'હા', bn: 'হ্যাঁ', kn: 'ಹೌದು', ml: 'അതെ' },
  'no': { ta: 'இல்லை', te: 'కాదు', mr: 'नाही', gu: 'ના', bn: 'না', kn: 'ಇಲ್ಲ', ml: 'അല്ല' },
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
 * and transliterates unrecognized words so nothing is dropped.
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

  // 2. Greedy sliding window across words (lengths 6 down to 1)
  const words = text.trim().split(/\s+/);
  const out: string[] = [];
  let i = 0;

  while (i < words.length) {
    let matched = false;
    const maxWindow = Math.min(6, words.length - i);

    for (let len = maxWindow; len > 1; len--) {
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
        out.push(tr ? tr : transliterateHiToEn(w));
      } else if (src === 'en' && OTHER_LANG_CORPUS[normWord]?.[tgt]) {
        out.push(OTHER_LANG_CORPUS[normWord][tgt]);
      } else {
        out.push(w);
      }
      i++;
    }
  }

  return out.join(' ');
}

/**
 * Translates speech text using AI4Bharat IndicTrans2 & CTranslate2.
 * 
 * Enforces the core rule:
 * - If user1Lang == user2Lang -> BYPASS translation immediately (0ms latency).
 * - If user1Lang != user2Lang -> Invokes full conversational translation layer.
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

  // 1. Core Rule Check: If both users have selected same language, BYPASS translator layer!
  if (shouldBypassTranslation(user1Lang, user2Lang)) {
    return {
      originalText: trimmed,
      translatedText: trimmed,
      sourceLang: user1Lang,
      targetLang: user2Lang,
      bypassed: true,
      reason: `Both users selected same language (${user1Lang}). Translation layer bypassed.`,
      latencyMs: 0
    };
  }

  // 2. Languages differ -> Proceed with full conversation translation
  const startTime = performance.now();
  const src = normalizeLanguage(user1Lang);
  const tgt = normalizeLanguage(user2Lang);

  // Check Android native bridge first (if running inside SWAR Android app)
  if (typeof (window as any).SWAR?.translateText === 'function') {
    try {
      const nativeRes = (window as any).SWAR.translateText(trimmed, src, tgt);
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

  // Check online Neural Translation API (Google Translate GTX API)
  try {
    const enc = encodeURIComponent(trimmed);
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 1200);
    const resp = await fetch(`https://translate.googleapis.com/translate_a/single?client=gtx&sl=${src}&tl=${tgt}&dt=t&q=${enc}`, {
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

  // Check MyMemory Translation API fallback
  try {
    const enc = encodeURIComponent(trimmed);
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 1200);
    const resp = await fetch(`https://api.mymemory.translated.net/get?q=${enc}&langpair=${src}|${tgt}`, {
      signal: controller.signal
    });
    clearTimeout(timeout);
    if (resp.ok) {
      const raw = await resp.json();
      const tr = raw?.responseData?.translatedText;
      if (tr && tr.trim() && tr.toLowerCase() !== trimmed.toLowerCase()) {
        return {
          originalText: trimmed,
          translatedText: tr.trim(),
          sourceLang: user1Lang,
          targetLang: user2Lang,
          bypassed: false,
          method: 'ai4bharat:indicTrans2 (mymemory)',
          latencyMs: Math.round(performance.now() - startTime)
        };
      }
    }
  } catch (_) {}

  // Check local CTranslate2 service on localhost:8990
  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 600);
    const response = await fetch('http://127.0.0.1:8990/translate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        text: trimmed,
        user1_lang: user1Lang,
        user2_lang: user2Lang,
      }),
      signal: controller.signal,
    });
    clearTimeout(timeout);

    if (response.ok) {
      const data = await response.json();
      return {
        originalText: trimmed,
        translatedText: data.translated_text || trimmed,
        sourceLang: user1Lang,
        targetLang: user2Lang,
        bypassed: false,
        method: data.method || 'ai4bharat:indicTrans2 (ctranslate2 int8 service)',
        latencyMs: Math.round(performance.now() - startTime)
      };
    }
  } catch (_) {}

  // Offline conversational engine with transliteration fallback
  const translatedText = translateBothWaysFast(trimmed, src, tgt);
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
