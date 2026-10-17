import zipfile, re

with zipfile.ZipFile('mobile/app-device.apk') as z:
    dex = z.read('classes.dex')
    # find all strings in dex
    strings = set(re.findall(rb'[A-Za-z_][A-Za-z0-9_\.]{3,35}', dex))
    keywords = [b'speech', b'model', b'offline', b'language', b'status', b'download', b'Bridge', b'iTantra', b'error', b'event', b'speak', b'recognize', b'tts', b'stt', b'voice']
    found = {k: [] for k in keywords}
    for s in strings:
        s_lower = s.lower()
        for k in keywords:
            if k in s_lower:
                found[k].append(s.decode('latin1', errors='ignore'))
    for k, v in found.items():
        print(f"--- {k.decode()} ---")
        print(sorted(v)[:20])
