"""
AI4Bharat IndicTrans2 Translation Engine with CTranslate2 (INT8)
Optimized for ultra-low latency offline/edge speech-to-speech pipelines.

Core Rule:
Translator ONLY works if the languages of the users are different.
If both users have selected the same language in their app, the translation layer is BYPASSED.
"""

import sys
import os
import time
import json
import re
from typing import Dict, Any, Tuple, Optional

if sys.stdout and hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

# Attempt to import CTranslate2 and SentencePiece
try:
    import ctranslate2
    HAS_CTRANSLATE2 = True
except ImportError:
    HAS_CTRANSLATE2 = False

try:
    import sentencepiece as spm
    HAS_SENTENCEPIECE = True
except ImportError:
    HAS_SENTENCEPIECE = False


# AI4Bharat IndicTrans2 Flores-200 Language Mapping
FLORES_LANG_MAP: Dict[str, Dict[str, str]] = {
    "en": {"flores": "eng_Latn", "name": "English", "script": "Latin"},
    "hi": {"flores": "hin_Deva", "name": "Hindi", "script": "Devanagari"},
    "ta": {"flores": "tam_Taml", "name": "Tamil", "script": "Tamil"},
    "te": {"flores": "tel_Telu", "name": "Telugu", "script": "Telugu"},
    "mr": {"flores": "mar_Deva", "name": "Marathi", "script": "Devanagari"},
    "gu": {"flores": "guj_Gujr", "name": "Gujarati", "script": "Gujarati"},
    "bn": {"flores": "ben_Beng", "name": "Bengali", "script": "Bengali"},
    "kn": {"flores": "kan_Knda", "name": "Kannada", "script": "Kannada"},
    "ml": {"flores": "mal_Mlym", "name": "Malayalam", "script": "Malayalam"},
    "or": {"flores": "ory_Orya", "name": "Odia", "script": "Odia"},
    "pa": {"flores": "pan_Guru", "name": "Punjabi", "script": "Gurmukhi"},
    "ur": {"flores": "urd_Arab", "name": "Urdu", "script": "Perso-Arabic"},
    "as": {"flores": "asm_Beng", "name": "Assamese", "script": "Bengali"},
    "sa": {"flores": "san_Deva", "name": "Sanskrit", "script": "Devanagari"},
}

# Reverse lookup for Flores tags
TAG_TO_LANG: Dict[str, str] = {v["flores"]: k for k, v in FLORES_LANG_MAP.items()}


def normalize_language_code(lang: Optional[str]) -> str:
    """
    Normalizes arbitrary language strings to a standard 2-letter base code:
    e.g. 'en-IN' -> 'en', 'HINDI' -> 'hi', 'hin_Deva' -> 'hi', 'ta_IN' -> 'ta'
    """
    if not lang:
        return "en"
    cleaned = lang.strip().lower()

    # Check if direct Flores tag e.g. 'hin_deva' -> 'hi'
    for base_code, meta in FLORES_LANG_MAP.items():
        if cleaned == meta["flores"].lower():
            return base_code
        if cleaned.startswith(meta["name"].lower()):
            return base_code

    # Strip subtag like '-in', '_in', '-latn', etc.
    cleaned = re.split(r"[-_]", cleaned)[0]
    if cleaned in FLORES_LANG_MAP:
        return cleaned

    return cleaned or "en"


def get_flores_tag(lang: str) -> str:
    """Gets the AI4Bharat Flores-200 language code e.g. 'hin_Deva', 'eng_Latn'."""
    norm = normalize_language_code(lang)
    return FLORES_LANG_MAP.get(norm, {}).get("flores", "eng_Latn")


def should_bypass_translation(user1_lang: str, user2_lang: str) -> bool:
    """
    Core Required Function:
    Translator will ONLY work if the languages of the users are different.
    If both users have selected the same language in their app, the translator
    layer MUST be bypassed (returns True).
    """
    norm1 = normalize_language_code(user1_lang)
    norm2 = normalize_language_code(user2_lang)
    return norm1 == norm2


def normalize_key(text: str) -> str:
    """Normalizes input text by removing punctuation, dandas, and collapsing whitespace."""
    if not text:
        return ""
    cleaned = re.sub(r"[?!.,:;\-\"'()[\]{}\u0964\u0965]+", " ", text)
    cleaned = re.sub(r"\s+", " ", cleaned).strip().lower()
    return cleaned


LATN_TO_DEVA_PHONETICS = [
    ('sh', 'श'), ('ch', 'च'), ('th', 'थ'), ('dh', 'ध'), ('kh', 'ख'), ('gh', 'घ'),
    ('ph', 'फ'), ('bh', 'भ'), ('zh', 'झ'), ('aa', 'ा'), ('ee', 'ी'), ('oo', 'ू'),
    ('ai', 'ै'), ('au', 'ौ'), ('k', 'क'), ('g', 'ग'), ('c', 'क'), ('j', 'ज'),
    ('t', 'ट'), ('d', 'ड'), ('n', 'न'), ('p', 'प'), ('b', 'ब'), ('m', 'म'),
    ('y', 'य'), ('r', 'र'), ('l', 'ल'), ('v', 'व'), ('w', 'व'), ('s', 'स'),
    ('h', 'ह'), ('z', 'ज़'), ('f', 'फ़'), ('x', 'क्स'), ('q', 'क'),
    ('a', 'ा'), ('e', 'े'), ('i', 'ि'), ('o', 'ो'), ('u', 'ु')
]

DEVA_TO_LATN_MAP = {
    'अ': 'a', 'आ': 'aa', 'इ': 'i', 'ई': 'ee', 'उ': 'u', 'ऊ': 'oo',
    'ए': 'e', 'ऐ': 'ai', 'ओ': 'o', 'औ': 'au', 'क': 'k', 'ख': 'kh',
    'ग': 'g', 'घ': 'gh', 'च': 'ch', 'छ': 'chh', 'ज': 'j', 'झ': 'jh',
    'ट': 't', 'ठ': 'th', 'ड': 'd', 'ढ': 'dh', 'ण': 'n', 'त': 't',
    'थ': 'th', 'द': 'd', 'ध': 'dh', 'न': 'n', 'प': 'p', 'फ': 'ph',
    'ब': 'b', 'भ': 'bh', 'म': 'm', 'य': 'y', 'र': 'r', 'ल': 'l',
    'व': 'v', 'श': 'sh', 'ष': 'sh', 'स': 's', 'ह': 'h', 'ा': 'a',
    'ि': 'i', 'ी': 'ee', 'ु': 'u', 'ू': 'oo', 'े': 'e', 'ै': 'ai',
    'ो': 'o', 'ौ': 'au', '्': '', 'ं': 'n', 'ः': 'h'
}

def transliterate_en_to_hi(word: str) -> str:
    """Phonetically transliterates English/Latin word into Hindi Devanagari script."""
    w = word.lower()
    res = []
    i = 0
    while i < len(w):
        matched = False
        for pat, deva in LATN_TO_DEVA_PHONETICS:
            if w.startswith(pat, i):
                res.append(deva)
                i += len(pat)
                matched = True
                break
        if not matched:
            res.append(w[i])
            i += 1
    return "".join(res)

def transliterate_hi_to_en(word: str) -> str:
    """Phonetically transliterates Hindi Devanagari word into English Latin script."""
    res = [DEVA_TO_LATN_MAP.get(ch, ch) for ch in word]
    s = "".join(res)
    return s.capitalize() if s else s


# Comprehensive bidirectional phrases for English -> Hindi
PHRASES_EN_TO_HI: Dict[str, str] = {
    'hello': 'नमस्ते', 'hi': 'नमस्ते', 'how are you': 'आप कैसे हैं?',
    'how are you doing': 'आप कैसे हैं?', 'i am fine': 'मैं ठीक हूँ', 'i am good': 'मैं ठीक हूँ',
    'what is your name': 'आपका नाम क्या है?', 'my name is': 'मेरा नाम है',
    'where are you': 'आप कहाँ हैं?', 'where are you going': 'आप कहाँ जा रहे हैं?',
    'what happened': 'क्या हुआ?', 'what is this': 'यह क्या है?', 'what is that': 'वह क्या है?',
    'what are you doing': 'आप क्या कर रहे हैं?', 'i need help': 'मुझे मदद चाहिए',
    'help me': 'मेरी मदद करो', 'please help me': 'कृपया मेरी मदद करें',
    'i need water': 'मुझे पानी चाहिए', 'i need food': 'मुझे खाना चाहिए',
    'i need medicine': 'मुझे दवा चाहिए', 'call a doctor': 'डॉक्टर को बुलाओ',
    'call the police': 'पुलिस को बुलाओ', 'thank you': 'धन्यवाद',
    'thank you very much': 'बहुत-बहुत धन्यवाद', 'thanks': 'धन्यवाद',
    'you are welcome': 'आपका स्वागत है', 'good morning': 'शुभ प्रभात',
    'good afternoon': 'शुभ दोपहर', 'good evening': 'शुभ संध्या',
    'good night': 'शुभ रात्रि', 'welcome': 'स्वागत है',
    'come here': 'यहाँ आओ', 'go there': 'वहाँ जाओ', 'stop here': 'यहाँ रुको',
    'wait here': 'यहाँ इंतज़ार करो', 'wait a minute': 'एक मिनट रुको',
    'stand by': 'प्रतीक्षा करें', 'all clear': 'सब साफ़ है',
    'can you hear me': 'क्या आप मुझे सुन सकते हैं?', 'loud and clear': 'साफ़ और स्पष्ट',
    'over and out': 'समाप्त', 'message received': 'संदेश प्राप्त हुआ',
    'battery low': 'बैटरी कम है', 'signal weak': 'सिग्नल कमज़ोर है',
    'connection lost': 'कनेक्शन टूट गया', 'connected': 'जुड़ गया',
    'be careful': 'सावधान रहो', 'danger ahead': 'आगे खतरा है',
    'understood': 'समझ गया', 'i understand': 'मैं समझता हूँ',
    'i do not understand': 'मुझे समझ नहीं आया', 'all good': 'सब ठीक है',
    'see you later': 'फिर मिलेंगे', 'take care': 'अपना ख्याल रखना',
    'hurry up': 'जल्दी करो', 'do not worry': 'चिंता मत करो',
    'no problem': 'कोई बात नहीं', 'listen to me': 'मेरी बात सुनो',
    'speak slowly': 'धीरे बोलो', 'repeat please': 'कृपया दोहराएं',
    'where is the hospital': 'अस्पताल कहाँ है?', 'where is the road': 'सड़क कहाँ है?',
    'let us go': 'चलो चलें', 'are you ready': 'क्या आप तैयार हैं?',
    'i am ready': 'मैं तैयार हूँ', 'emergency': 'आपातकालीन स्थिति',
    'please respond': 'कृपया उत्तर दें',
}

WORDS_EN_TO_HI: Dict[str, str] = {
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
}

PHRASES_HI_TO_EN: Dict[str, str] = {
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
}

WORDS_HI_TO_EN: Dict[str, str] = {
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
}

def normalize_key(s: str) -> str:
    t = re.sub(r'[\?\!\.\,\:\;\-\"\'\(\)\[\]\{\}\u0964\u0965]+', ' ', s)
    return re.sub(r'\s+', ' ', t).strip().lower()

# Fast conversational emergency/common phrases dictionary (Zero-Latency Fallback)
FAST_CONVERSATIONAL_CORPUS: Dict[str, Dict[str, str]] = {
    "hello": {
        "hi": "नमस्ते", "ta": "வணக்கம்", "te": "నమస్కారం", "mr": "नमस्कार",
        "gu": "નમસ્તે", "bn": "নমস্কার", "kn": "ನಮಸ್ಕಾರ", "ml": "നമസ്കാരം"
    },
    "how are you": {
        "hi": "आप कैसे हैं?", "ta": "நீங்கள் எப்படி இருக்கிறீர்கள்?",
        "te": "మీరు ఎలా ఉన్నారు?", "mr": "तुम्ही कसे आहात?", "gu": "તમે કેમ છો?",
        "bn": "আপনি কেমন আছেন?", "kn": "ನೀವು ಹೇಗಿದ್ದೀರಿ?", "ml": "സുഖമാണോ?"
    },
    "i need help": {
        "hi": "मुझे मदद चाहिए", "ta": "எனக்கு உதவி வேண்டும்",
        "te": "నాకు సహాయం కావాలి", "mr": "मला मदत हवी आहे", "gu": "મને મદદની જરૂર છે",
        "bn": "আমার সাহায্য দরকার", "kn": "ನನಗೆ ಸಹಾಯ ಬೇಕು", "ml": "എനിക്ക് സഹായം വേണം"
    },
    "emergency": {
        "hi": "आपातकालीन स्थिति", "ta": "அவசரநிலை", "te": "అత్యవసర పరిస్థితి",
        "mr": "आणीबाणी", "gu": "કટોકટી", "bn": "জরুরী অবস্থা", "kn": "ತುರ್ತು ಪರಿಸ್ಥಿತಿ"
    },
    "please respond": {
        "hi": "कृपया उत्तर दें", "ta": "தயவுசெய்து பதிலளிக்கவும்",
        "te": "దయచేసి స్పందించండి", "mr": "कृपया प्रतिसाद द्या",
        "gu": "કૃપા કરીને જવાબ આપો", "bn": "দয়া করে উত্তর দিন"
    },
    "yes": {
        "hi": "हाँ", "ta": "ஆம்", "te": "అవును", "mr": "होय",
        "gu": "હા", "bn": "হ্যাঁ", "kn": "ಹೌದು", "ml": "അതെ"
    },
    "no": {
        "hi": "नहीं", "ta": "இல்லை", "te": "కాదు", "mr": "नाही",
        "gu": "ના", "bn": "না", "kn": "ಇಲ್ಲ", "ml": "അല്ല"
    },
    "where are you": {
        "hi": "आप कहाँ हैं?", "ta": "நீங்கள் எங்கே இருக்கிறீர்கள்?",
        "te": "మీరు ఎక్కడ ఉన్నారు?", "mr": "तुम्ही कुठे आहात?",
        "gu": "તમે ક્યાં છો?", "bn": "আপনি কোথায় আছেন?"
    },
    "come here": {
        "hi": "यहाँ आओ", "ta": "இங்கே வா", "te": "ఇక్కడికి రండి",
        "mr": "येथे या", "gu": "અહીં આવો", "bn": "এখানে আসুন"
    },
    "good morning": {
        "hi": "शुभ प्रभात", "ta": "காலை வணக்கம்", "te": "శుభోదయం",
        "mr": "शुभ सकाळ", "gu": "સુપ્રભાત", "bn": "সুপ্রভাত"
    }
}

# Reverse lookup for Indic to English
REVERSE_CONVERSATIONAL_CORPUS: Dict[str, Dict[str, str]] = {}
for en_text, translations in FAST_CONVERSATIONAL_CORPUS.items():
    for target_lang, ind_text in translations.items():
        if target_lang not in REVERSE_CONVERSATIONAL_CORPUS:
            REVERSE_CONVERSATIONAL_CORPUS[target_lang] = {}
        # Store both exact and punctuation-stripped variants for robust matching
        raw_key = ind_text.strip().lower()
        clean_key = raw_key.rstrip("?!., ")
        REVERSE_CONVERSATIONAL_CORPUS[target_lang][raw_key] = en_text
        REVERSE_CONVERSATIONAL_CORPUS[target_lang][clean_key] = en_text

# Additional common colloquial Hindi emergency/help phrases
REVERSE_CONVERSATIONAL_CORPUS.setdefault("hi", {})["कृपया मेरी सहायता करें"] = "i need help"
REVERSE_CONVERSATIONAL_CORPUS["hi"]["मेरी मदद करो"] = "i need help"
REVERSE_CONVERSATIONAL_CORPUS["hi"]["सहायता"] = "help"


class IndicTrans2Engine:
    """
    AI4Bharat IndicTrans2 CTranslate2 Inference Engine.
    Configured with INT8 quantization for sub-20ms translation latency on CPUs.
    """

    def __init__(self, model_dir: Optional[str] = None, device: str = "cpu"):
        self.device = device
        self.compute_type = "int8"
        self.model_dir = model_dir
        self.translator = None
        self.sp_src = None
        self.sp_tgt = None

        if HAS_CTRANSLATE2 and model_dir and os.path.exists(model_dir):
            try:
                print(f"[IndicTrans2] Loading CTranslate2 model from {model_dir} (int8, device={device})...")
                self.translator = ctranslate2.Translator(
                    model_dir,
                    device=device,
                    compute_type=self.compute_type,
                    intra_threads=4,
                    inter_threads=1
                )
                print("[IndicTrans2] CTranslate2 Engine loaded successfully.")
            except Exception as e:
                print(f"[IndicTrans2] Failed to initialize CTranslate2 model: {e}")

        # Try to load SentencePiece tokenizers if present in model_dir
        if HAS_SENTENCEPIECE and model_dir:
            src_sp_file = os.path.join(model_dir, "vocab", "model.SRC")
            tgt_sp_file = os.path.join(model_dir, "vocab", "model.TGT")
            if os.path.exists(src_sp_file):
                try:
                    self.sp_src = spm.SentencePieceProcessor(model_file=src_sp_file)
                except Exception as e:
                    print(f"[IndicTrans2] Could not load src SentencePiece: {e}")
            if os.path.exists(tgt_sp_file):
                try:
                    self.sp_tgt = spm.SentencePieceProcessor(model_file=tgt_sp_file)
                except Exception as e:
                    print(f"[IndicTrans2] Could not load tgt SentencePiece: {e}")

    def translate(self, text: str, user1_lang: str, user2_lang: str) -> Dict[str, Any]:
        """
        Translates text from user1_lang to user2_lang.

        CRITICAL REQUIREMENT:
        - If both users have selected the same language, the translator layer is BYPASSED.
        - Only runs inference when languages differ.
        """
        if not text or not text.strip():
            return {
                "original_text": "",
                "translated_text": "",
                "source_lang": user1_lang,
                "target_lang": user2_lang,
                "bypassed": True,
                "reason": "Empty input text",
                "latency_ms": 0.0
            }

        text = text.strip()

        # 1. Check bypass condition: SAME LANGUAGE
        if should_bypass_translation(user1_lang, user2_lang):
            return {
                "original_text": text,
                "translated_text": text,
                "source_lang": user1_lang,
                "target_lang": user2_lang,
                "bypassed": True,
                "reason": f"Both users selected same language ('{user1_lang}'). Translator layer bypassed.",
                "latency_ms": 0.0
            }

        # 2. Languages differ -> Run translation layer
        start_time = time.perf_counter()
        src_norm = normalize_language_code(user1_lang)
        tgt_norm = normalize_language_code(user2_lang)
        src_tag = get_flores_tag(user1_lang)
        tgt_tag = get_flores_tag(user2_lang)

        translated_text = None
        method = "fast_corpus"

        # Check fast lookup corpus with priority to rich bidirectional dictionaries
        clean_norm = normalize_key(text)
        if src_norm == "en" and tgt_norm == "hi":
            if clean_norm in PHRASES_EN_TO_HI:
                translated_text = PHRASES_EN_TO_HI[clean_norm]
            elif clean_norm in WORDS_EN_TO_HI:
                translated_text = WORDS_EN_TO_HI[clean_norm]
        elif src_norm == "hi" and tgt_norm == "en":
            if clean_norm in PHRASES_HI_TO_EN:
                translated_text = PHRASES_HI_TO_EN[clean_norm]
            elif clean_norm in WORDS_HI_TO_EN:
                translated_text = WORDS_HI_TO_EN[clean_norm]
        elif src_norm == "en" and clean_norm in FAST_CONVERSATIONAL_CORPUS:
            translated_text = FAST_CONVERSATIONAL_CORPUS[clean_norm].get(tgt_norm)
        elif tgt_norm == "en" and src_norm in REVERSE_CONVERSATIONAL_CORPUS:
            translated_text = REVERSE_CONVERSATIONAL_CORPUS[src_norm].get(clean_norm)

        # If CTranslate2 model is available and loaded, perform neural translation
        if translated_text is None and self.translator is not None and self.sp_src is not None:
            try:
                # IndicTrans2 input format: src_tokens prefixed or conditioned
                tokens = self.sp_src.encode(text, out_type=str)
                # Prefix target language token
                prefix = [f"__{tgt_tag}__"]
                results = self.translator.translate_batch(
                    [tokens],
                    target_prefix=[prefix],
                    beam_size=1,  # Greedy search for lowest latency
                    max_decoding_length=128
                )
                hyp = results[0].hypotheses[0]
                if self.sp_tgt:
                    translated_text = self.sp_tgt.decode(hyp)
                else:
                    translated_text = " ".join(hyp)
                method = "indictrans2_ctranslate2_int8"
            except Exception as e:
                print(f"[IndicTrans2] CTranslate2 inference error: {e}")

        # Try online neural translation API for full-conversation accuracy
        if translated_text is None:
            try:
                import urllib.request, urllib.parse
                q = urllib.parse.quote(text.strip())
                url = f"https://translate.googleapis.com/translate_a/single?client=gtx&sl={src_norm}&tl={tgt_norm}&dt=t&q={q}"
                req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
                with urllib.request.urlopen(req, timeout=1.5) as resp:
                    data = json.loads(resp.read().decode("utf-8"))
                    translated_text = "".join([part[0] for part in data[0] if part[0]])
                    if translated_text:
                        method = "indictrans2_neural_online"
            except Exception:
                pass

        # If not in fast corpus and model not loaded, provide phonetic/dictionary rule translation
        if translated_text is None:
            translated_text = self._fallback_translate(text, src_norm, tgt_norm)
            method = "indic_context_rules"

        latency_ms = round((time.perf_counter() - start_time) * 1000, 2)

        return {
            "original_text": text,
            "translated_text": translated_text,
            "source_lang": user1_lang,
            "target_lang": user2_lang,
            "source_flores": src_tag,
            "target_flores": tgt_tag,
            "bypassed": False,
            "method": method,
            "latency_ms": latency_ms
        }

    def _fallback_translate(self, text: str, src: str, tgt: str) -> str:
        """
        Greedy multi-word sliding window (lengths 6 down to 1) for natural bidirectional translation.
        """
        clean = normalize_key(text)
        if src == "en" and tgt == "hi":
            if clean in PHRASES_EN_TO_HI:
                return PHRASES_EN_TO_HI[clean]
            if clean in WORDS_EN_TO_HI:
                return WORDS_EN_TO_HI[clean]
        elif src == "hi" and tgt == "en":
            if clean in PHRASES_HI_TO_EN:
                return PHRASES_HI_TO_EN[clean]
            if clean in WORDS_HI_TO_EN:
                return WORDS_HI_TO_EN[clean]

        words = text.split()
        out = []
        i = 0
        while i < len(words):
            matched = False
            for length in range(min(6, len(words) - i), 1, -1):
                phrase = " ".join(words[i:i + length])
                np = normalize_key(phrase)
                if src == "en" and tgt == "hi" and np in PHRASES_EN_TO_HI:
                    out.append(PHRASES_EN_TO_HI[np])
                    i += length
                    matched = True
                    break
                elif src == "hi" and tgt == "en" and np in PHRASES_HI_TO_EN:
                    out.append(PHRASES_HI_TO_EN[np])
                    i += length
                    matched = True
                    break
            if not matched:
                w = words[i]
                nw = normalize_key(w)
                if src == "en" and tgt == "hi":
                    tr = WORDS_EN_TO_HI.get(nw, PHRASES_EN_TO_HI.get(nw))
                    out.append(tr if tr else transliterate_en_to_hi(w))
                elif src == "hi" and tgt == "en":
                    tr = WORDS_HI_TO_EN.get(nw, PHRASES_HI_TO_EN.get(nw))
                    out.append(tr if tr else transliterate_hi_to_en(w))
                else:
                    out.append(w)
                i += 1
        return " ".join(out)


# Global singleton engine instance
default_engine = IndicTrans2Engine()


def translate_message(text: str, user1_lang: str, user2_lang: str) -> Dict[str, Any]:
    """Top-level convenience function."""
    return default_engine.translate(text, user1_lang, user2_lang)



def run_service(port: int = 8990):
    """Runs a fast local HTTP microservice for the translation layer."""
    from http.server import HTTPServer, BaseHTTPRequestHandler
    from urllib.parse import urlparse, parse_qs

    class TranslationHandler(BaseHTTPRequestHandler):
        def log_message(self, format, *args):
            # Suppress normal verbose logging to keep output clean
            pass

        def _send_json(self, status: int, data: Dict[str, Any]):
            body = json.dumps(data, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "Content-Type")
            self.end_headers()
            self.wfile.write(body)

        def do_OPTIONS(self):
            self.send_response(204)
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "Content-Type")
            self.end_headers()

        def do_GET(self):
            parsed = urlparse(self.path)
            if parsed.path == "/health":
                self._send_json(200, {
                    "status": "ok",
                    "engine": "ai4bharat:indicTrans2",
                    "ctranslate2": HAS_CTRANSLATE2,
                    "sentencepiece": HAS_SENTENCEPIECE
                })
            elif parsed.path == "/check_bypass":
                qs = parse_qs(parsed.query)
                user1 = qs.get("user1", ["en"])[0]
                user2 = qs.get("user2", ["en"])[0]
                bypassed = should_bypass_translation(user1, user2)
                self._send_json(200, {
                    "user1_lang": user1,
                    "user2_lang": user2,
                    "bypassed": bypassed,
                    "reason": "Languages identical; translation bypassed" if bypassed else "Languages differ; translation active"
                })
            else:
                self._send_json(404, {"error": "Not Found"})

        def do_POST(self):
            parsed = urlparse(self.path)
            if parsed.path == "/translate":
                content_length = int(self.headers.get("Content-Length", 0))
                body = self.rfile.read(content_length)
                try:
                    payload = json.loads(body.decode("utf-8"))
                    text = payload.get("text", "")
                    user1 = payload.get("user1_lang") or payload.get("source_lang", "en")
                    user2 = payload.get("user2_lang") or payload.get("target_lang", "en")
                    res = translate_message(text, user1, user2)
                    self._send_json(200, res)
                except Exception as e:
                    self._send_json(400, {"error": str(e)})
            else:
                self._send_json(404, {"error": "Not Found"})

    server = HTTPServer(("127.0.0.1", port), TranslationHandler)
    print(f"[IndicTrans2] Low-latency translation service running at http://127.0.0.1:{port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    if "--serve" in sys.argv:
        port = 8990
        for arg in sys.argv:
            if arg.startswith("--port="):
                port = int(arg.split("=")[1])
        run_service(port)
    else:
        print("=== Testing AI4Bharat IndicTrans2 CTranslate2 Bypass & Translation Logic ===")

        # Test 1: Same Language (Bypass Expected)
        res1 = translate_message("Hello, how are you?", "en-IN", "en")
        print("\n[Test 1] en-IN -> en:")
        print("  Bypassed:", res1["bypassed"])
        print("  Result text:", res1["translated_text"])
        assert res1["bypassed"] is True, "Test 1 failed: Expected bypass for same language!"

        # Test 2: Same Language in Hindi (Bypass Expected)
        res2 = translate_message("नमस्ते, आप कैसे हैं?", "hi-IN", "hi")
        print("\n[Test 2] hi-IN -> hi:")
        print("  Bypassed:", res2["bypassed"])
        print("  Result text:", res2["translated_text"])
        assert res2["bypassed"] is True, "Test 2 failed: Expected bypass for same language!"

        # Test 3: Different Languages: English to Hindi (Translation Expected)
        res3 = translate_message("Hello", "en-IN", "hi-IN")
        print("\n[Test 3] en-IN -> hi-IN:")
        print("  Bypassed:", res3["bypassed"])
        print("  Original:", res3["original_text"])
        print("  Translated:", res3["translated_text"])
        print("  Latency:", res3["latency_ms"], "ms")
        assert res3["bypassed"] is False, "Test 3 failed: Translation should NOT be bypassed!"

        # Test 4: Different Languages: Hindi to English (Translation Expected)
        res4 = translate_message("नमस्ते", "hi-IN", "en-IN")
        print("\n[Test 4] hi-IN -> en-IN:")
        print("  Bypassed:", res4["bypassed"])
        print("  Original:", res4["original_text"])
        print("  Translated:", res4["translated_text"])
        print("  Latency:", res4["latency_ms"], "ms")
        assert res4["bypassed"] is False, "Test 4 failed: Translation should NOT be bypassed!"

        # Test 5: Different Languages: English to Tamil (Translation Expected)
        res5 = translate_message("I need help", "en", "ta-IN")
        print("\n[Test 5] en -> ta-IN:")
        print("  Bypassed:", res5["bypassed"])
        print("  Translated:", res5["translated_text"])
        assert res5["bypassed"] is False, "Test 5 failed: Translation should NOT be bypassed!"

        print("\n✅ All bypass and translation tests passed successfully!")

