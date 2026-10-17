import os, sys, zipfile, shutil, subprocess

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE_APK = os.path.join(ROOT, "mobile", "swar-ipod-base.apk")
OUT_UNSIGNED = os.path.join(ROOT, "mobile", "swar-ipod-unsigned.apk")
OUT_SIGNED = os.path.join(ROOT, "mobile", "swar-ipod-signed.apk")
GEN_DIR = os.path.join(ROOT, "mobile", "app", "src", "main", "assets", "generated")
KEYSTORE = os.path.join(ROOT, "mobile", "debug.keystore")
SIGNER_JAR = os.path.join(ROOT, "mobile", "signer.jar")
USER_HOME = os.path.expanduser("~")
APKSIG_JAR = os.path.join(USER_HOME, r".gradle\caches\modules-2\files-2.1\com.android.tools.build\apksig\9.0.0\2881101f6a9d0baf6a341926ef0ff08c98a5d13b\apksig-9.0.0.jar")
JAVA_EXE = os.path.join(USER_HOME, ".jdk17", "bin", "java.exe")

MODELS_DIR = os.path.join(ROOT, "mobile", "app", "src", "main", "assets", "models")

def repack():
    print("Repacking swar-ipod.apk with newly built web assets and English voice model...")
    # Gather new generated files
    new_files = {}
    for root, dirs, files in os.walk(GEN_DIR):
        for f in files:
            full = os.path.join(root, f)
            rel = os.path.relpath(full, GEN_DIR).replace("\\", "/")
            zip_path = f"assets/generated/{rel}"
            with open(full, "rb") as fp:
                new_files[zip_path] = fp.read()
            print(f"  + {zip_path} ({len(new_files[zip_path])} bytes)")

    # Gather bundled English voice model files
    if os.path.exists(MODELS_DIR):
        for root, dirs, files in os.walk(MODELS_DIR):
            for f in files:
                full = os.path.join(root, f)
                rel = os.path.relpath(full, MODELS_DIR).replace("\\", "/")
                zip_path = f"assets/models/{rel}"
                with open(full, "rb") as fp:
                    new_files[zip_path] = fp.read()
                print(f"  + [VOICE MODEL] {zip_path} ({len(new_files[zip_path])} bytes)")

    # Read base APK and replace
    with zipfile.ZipFile(BASE_APK, "r") as zin, zipfile.ZipFile(OUT_UNSIGNED, "w", compression=zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            # Skip old generated assets and old models
            if item.filename.startswith("assets/generated/") or item.filename.startswith("assets/models/"):
                continue
            # Skip signature files
            if item.filename.startswith("META-INF/") and (item.filename.endswith(".SF") or item.filename.endswith(".RSA") or item.filename.endswith(".MF")):
                continue
            zout.writestr(item, zin.read(item.filename))
        
        # Add new generated assets & model files
        for zip_path, data in new_files.items():
            zout.writestr(zip_path, data)

    print(f"Unsigned APK created: {os.path.getsize(OUT_UNSIGNED)} bytes")

    # Sign APK with SignerKt
    print("Signing APK with debug key...")
    signer_cp = f"{SIGNER_JAR};{APKSIG_JAR}"
    cmd = [JAVA_EXE, "-cp", signer_cp, "SignerKt", OUT_UNSIGNED, OUT_SIGNED, KEYSTORE]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        raise RuntimeError(f"Signing failed: {res.stderr}\n{res.stdout}")
    print(f"Signed APK created: {os.path.getsize(OUT_SIGNED)} bytes")

    # Copy signed APK to public download location
    pub_apk = os.path.join(ROOT, "apps", "web", "public", "swar-ipod.apk")
    shutil.copy2(OUT_SIGNED, pub_apk)
    print(f"Copied signed APK to {pub_apk} ({os.path.getsize(pub_apk)} bytes)")

if __name__ == "__main__":
    repack()
