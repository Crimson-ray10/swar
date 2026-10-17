import zipfile, re

with zipfile.ZipFile('mobile/app-device.apk') as z:
    dex = z.read('classes3.dex')
    
# Let's extract all strings in dex (length 3 to 60)
strings = re.findall(b'[\x20-\x7e]{3,60}', dex)
interesting = []
for s in strings:
    s_lower = s.lower()
    if any(k in s_lower for k in [b'speechengine', b'downloadmodel', b'offline', b'model', b'status', b'capabilities', b'language', b'tts', b'recogniz']):
        interesting.append(s.decode('latin1', errors='ignore'))

print("Total matches:", len(interesting))
for s in sorted(set(interesting)):
    print(s)
