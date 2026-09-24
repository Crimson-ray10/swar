"""Regression guard: sound-only transport must not import/construct network implementations."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
sources = root / 'mobile/app/src/main/java/in/itantra/mobile'
for path in sorted(sources.glob('Acoustic*.java')):
    text = path.read_text(encoding='utf-8')
    assert not re.search(r'\b(java\.net|android\.net|Socket|ServerSocket|LocalTransport|WebsiteTransport|WifiManager|BluetoothAdapter)\b', text), path
main = (sources / 'MainActivity.java').read_text(encoding='utf-8')
creation = main[main.index('public void onCreate'):main.index('private void event')]
assert 'transport=new AcousticTransport' in creation
assert 'new LocalTransport' not in creation and 'new WebsiteTransport' not in creation
speech = (sources / 'SpeechEngine.java').read_text(encoding='utf-8')
assert 'createOnDeviceSpeechRecognizer' in speech and 'createSpeechRecognizer(' not in speech
assert 'tts.setLanguage(' not in speech, 'Missing local voice must not select another provider voice'
print('PASS: default acoustic construction, network dependency boundary, on-device-only recognizer, strict offline voice selection')
