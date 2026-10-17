import os, sys, zipfile, shutil, subprocess, struct

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE_APK = os.path.join(ROOT, "device_ipod.apk")
OUT_UNSIGNED = os.path.join(ROOT, "mobile", "swar-ipod-unsigned.apk")
OUT_ALIGNED = os.path.join(ROOT, "mobile", "swar-ipod-aligned.apk")
OUT_SIGNED = os.path.join(ROOT, "mobile", "swar-ipod-signed.apk")
GEN_DIR = os.path.join(ROOT, "mobile", "app", "src", "main", "assets", "generated")
RES_DIR = os.path.join(ROOT, "mobile", "app", "src", "main", "res")
JAVA_SRC_DIR = os.path.join(ROOT, "mobile", "app", "src", "main", "java", "in", "itantra", "mobile")
MODELS_DIR = os.path.join(ROOT, "scratch", "en-IN")

SCRATCH = os.path.join(ROOT, "scratch", "build")
CLASSES_DIR = os.path.join(SCRATCH, "classes")
DEX_DIR = os.path.join(SCRATCH, "dex")

USER_HOME = os.path.expanduser("~")
KEYSTORE = os.path.join(USER_HOME, ".android", "debug.keystore")
JAVA_HOME = os.path.join(USER_HOME, ".jdk17")
JAVAC = os.path.join(JAVA_HOME, "bin", "javac.exe")
JAVA_EXE = os.path.join(JAVA_HOME, "bin", "java.exe")
KOTLINC_JAR = os.path.join(USER_HOME, ".kotlinc", "kotlinc", "lib", "kotlin-compiler.jar")

BUILD_TOOLS = r"C:\Program Files\Unity\Hub\Editor\6000.6.0f1\Editor\Data\PlaybackEngines\AndroidPlayer\SDK\build-tools\36.0.0"
APKSIGNER = os.path.join(BUILD_TOOLS, "apksigner.bat")
ZIPALIGN = os.path.join(BUILD_TOOLS, "zipalign.exe")
AAPT2 = os.path.join(BUILD_TOOLS, "aapt2.exe")
D8 = os.path.join(BUILD_TOOLS, "d8.bat")
ANDROID_JAR = r"C:\Program Files\Unity\Hub\Editor\6000.6.0f1\Editor\Data\PlaybackEngines\AndroidPlayer\SDK\platforms\android-34\android.jar"
ADB = r"C:\Program Files\Unity\Hub\Editor\6000.6.0f1\Editor\Data\PlaybackEngines\AndroidPlayer\SDK\platform-tools\adb.exe"

def patch_resources_arsc(raw_arsc, new_app_name="SWAR"):
    """
    Modifies string 0 ('SWAR iPod') in the UTF-8 string pool to 'SWAR'
    and rebuilds the binary ARSC resource table.
    """
    header_size = 12
    sp_type, sp_header_size, sp_size = struct.unpack('<HHI', raw_arsc[12:20])
    string_count, style_count, flags, strings_start, styles_start = struct.unpack('<IIIII', raw_arsc[20:40])
    old_offsets = struct.unpack(f'<{string_count}I', raw_arsc[40:40+string_count*4])

    strings = []
    abs_strings_start = 12 + strings_start
    for i, off in enumerate(old_offsets):
        s_abs = abs_strings_start + off
        u16len = raw_arsc[s_abs]
        u8len = raw_arsc[s_abs+1]
        val = raw_arsc[s_abs+2:s_abs+2+u8len].decode('utf-8')
        strings.append(val)

    print(f"Original String 0: {strings[0]!r}")
    strings[0] = new_app_name
    print(f"Patched String 0:  {strings[0]!r}")

    encoded_strings = []
    new_offsets = []
    curr_off = 0
    for s in strings:
        new_offsets.append(curr_off)
        b = s.encode('utf-8')
        data = bytes([len(s), len(b)]) + b + b'\x00'
        encoded_strings.append(data)
        curr_off += len(data)

    str_payload = b''.join(encoded_strings)
    pad_len = (4 - (len(str_payload) % 4)) % 4
    str_payload += b'\x00' * pad_len

    new_strings_start = sp_header_size + string_count * 4
    new_sp_size = new_strings_start + len(str_payload)

    new_sp_header = struct.pack('<HHI', sp_type, sp_header_size, new_sp_size)
    new_sp_meta = struct.pack('<IIIII', string_count, style_count, flags, new_strings_start, styles_start)
    new_offsets_bytes = struct.pack(f'<{string_count}I', *new_offsets)
    new_sp_chunk = new_sp_header + new_sp_meta + new_offsets_bytes + str_payload

    old_pkg_chunk = raw_arsc[12 + sp_size:]
    new_total_size = 12 + len(new_sp_chunk) + len(old_pkg_chunk)
    new_table_header = struct.pack('<HHI', 2, 12, new_total_size) + raw_arsc[8:12]

    return new_table_header + new_sp_chunk + old_pkg_chunk

def compile_java_and_dex():
    print("\n--- Compiling Java Sources and Building DEX ---")
    os.makedirs(CLASSES_DIR, exist_ok=True)
    os.makedirs(DEX_DIR, exist_ok=True)

    env = os.environ.copy()
    env["JAVA_HOME"] = JAVA_HOME
    env["PATH"] = f"{os.path.join(JAVA_HOME, 'bin')};{env.get('PATH', '')}"

    # 1. Compile Java sources with javac
    java_files = [os.path.join(JAVA_SRC_DIR, f) for f in os.listdir(JAVA_SRC_DIR) if f.endswith(".java")]
    print(f"Compiling {len(java_files)} Java files with javac...")
    cmd_javac = [JAVAC, "-cp", ANDROID_JAR, "-d", CLASSES_DIR] + java_files
    res_javac = subprocess.run(cmd_javac, env=env, capture_output=True, text=True)
    if res_javac.returncode != 0:
        raise RuntimeError(f"javac failed:\n{res_javac.stderr}\n{res_javac.stdout}")
    print("javac compilation successful!")

    # 1b. Compile Kotlin sources with kotlinc
    kt_sources = [
        os.path.join(ROOT, "mobile", "app", "src", "main", "java", "in", "itantra", "mobile", "SpeechEngine.kt"),
    ]
    sherpa_src = os.path.join(ROOT, "mobile", "app", "src", "main", "java", "com", "k2fsa", "sherpa", "onnx")
    if os.path.exists(sherpa_src):
        for f in os.listdir(sherpa_src):
            if f.endswith(".kt"):
                kt_sources.append(os.path.join(sherpa_src, f))

    if os.path.exists(KOTLINC_JAR):
        print(f"Compiling {len(kt_sources)} Kotlin files with kotlinc...")
        kt_cp = f"{ANDROID_JAR};{CLASSES_DIR}"
        cmd_kt = [JAVA_EXE, "-cp", KOTLINC_JAR, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-cp", kt_cp, "-d", CLASSES_DIR, "-jvm-target", "1.8"] + kt_sources
        res_kt = subprocess.run(cmd_kt, env=env, capture_output=True, text=True)
        if res_kt.returncode != 0:
            raise RuntimeError(f"kotlinc failed:\n{res_kt.stderr}\n{res_kt.stdout}")
        print("kotlinc compilation successful!")

    # 2. Dex compiled class files with D8
    class_files = []
    for root, dirs, files in os.walk(CLASSES_DIR):
        for f in files:
            if f.endswith(".class"):
                class_files.append(os.path.join(root, f))
    print(f"Dexing {len(class_files)} class files with D8...")
    out_dex_file = os.path.join(DEX_DIR, "classes.dex")
    if os.path.exists(out_dex_file):
        os.remove(out_dex_file)

    cmd_d8 = [D8, "--lib", ANDROID_JAR, "--min-api", "28", "--output", DEX_DIR] + class_files
    res_d8 = subprocess.run(cmd_d8, env=env, capture_output=True, text=True)
    if res_d8.returncode != 0:
        raise RuntimeError(f"D8 dexing failed:\n{res_d8.stderr}\n{res_d8.stdout}")
    
    print(f"Generated new DEX: {out_dex_file} ({os.path.getsize(out_dex_file)} bytes)")
    return out_dex_file

def repack_and_deploy():
    print("=== SWAR IPOD APK BUILD & DEPLOY (Package: in.itantra.mobile.ipod) ===")
    
    # Compile fresh Java classes into DEX
    new_dex_path = compile_java_and_dex()
    with open(new_dex_path, "rb") as fp:
        new_dex_data = fp.read()

    # 1. Gather new web generated assets
    new_files = {}
    for root, dirs, files in os.walk(GEN_DIR):
        for f in files:
            full = os.path.join(root, f)
            rel = os.path.relpath(full, GEN_DIR).replace("\\", "/")
            zip_path = f"assets/generated/{rel}"
            with open(full, "rb") as fp:
                new_files[zip_path] = fp.read()
            print(f"  + {zip_path} ({len(new_files[zip_path])} bytes)")

    # 2. Gather voice models if --embed-models is requested
    embed_models = "--embed-models" in sys.argv
    if embed_models and os.path.exists(MODELS_DIR):
        print(f"\nEmbedding offline voice models from {MODELS_DIR} into APK assets...")
        for root, dirs, files in os.walk(MODELS_DIR):
            for f in files:
                full = os.path.join(root, f)
                rel = os.path.relpath(full, MODELS_DIR).replace("\\", "/")
                zip_path = f"assets/models/en-IN/{rel}"
                with open(full, "rb") as fp:
                    new_files[zip_path] = fp.read()
                print(f"  + [MODEL] {zip_path} ({len(new_files[zip_path])} bytes)")
    else:
        print("\nBuilding lightweight standalone APK (~56MB) for Vercel/GitHub distribution.")
        print("  (Models are downloaded on-demand from Hugging Face repository via in-app Language section)")

    # 3. Gather new app icons
    icon_mappings = [
        ("res/drawable-hdpi-v4/ic_itantra.png", os.path.join(RES_DIR, "drawable-hdpi", "ic_itantra.png")),
        ("res/drawable-mdpi-v4/ic_itantra.png", os.path.join(RES_DIR, "drawable-mdpi", "ic_itantra.png")),
        ("res/drawable-xhdpi-v4/ic_itantra.png", os.path.join(RES_DIR, "drawable-xhdpi", "ic_itantra.png")),
        ("res/drawable-xxhdpi-v4/ic_itantra.png", os.path.join(RES_DIR, "drawable-xxhdpi", "ic_itantra.png")),
        ("res/drawable-xxxhdpi-v4/ic_itantra.png", os.path.join(RES_DIR, "drawable-xxxhdpi", "ic_itantra.png")),
        ("res/drawable/ic_itantra.png", os.path.join(RES_DIR, "drawable", "ic_itantra.png")),
        ("res/mipmap-hdpi-v4/ic_itantra.png", os.path.join(RES_DIR, "mipmap-hdpi", "ic_itantra.png")),
        ("res/mipmap-mdpi-v4/ic_itantra.png", os.path.join(RES_DIR, "mipmap-mdpi", "ic_itantra.png")),
        ("res/mipmap-xhdpi-v4/ic_itantra.png", os.path.join(RES_DIR, "mipmap-xhdpi", "ic_itantra.png")),
        ("res/mipmap-xxhdpi-v4/ic_itantra.png", os.path.join(RES_DIR, "mipmap-xxhdpi", "ic_itantra.png")),
        ("res/mipmap-xxxhdpi-v4/ic_itantra.png", os.path.join(RES_DIR, "mipmap-xxxhdpi", "ic_itantra.png")),
    ]
    for apk_path, local_path in icon_mappings:
        if os.path.exists(local_path):
            with open(local_path, "rb") as fp:
                new_files[apk_path] = fp.read()
            print(f"  + [ICON] {apk_path} ({len(new_files[apk_path])} bytes)")

    # 4. Read base APK and replace assets, icons, and patched resources.arsc
    print(f"\nReading base APK: {BASE_APK} ({os.path.getsize(BASE_APK)} bytes)")
    if os.path.exists(OUT_UNSIGNED): os.remove(OUT_UNSIGNED)
    with zipfile.ZipFile(BASE_APK, "r") as zin, zipfile.ZipFile(OUT_UNSIGNED, "w", compression=zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            # Skip old generated assets, old model assets, and files to be replaced
            if item.filename.startswith("assets/generated/") or item.filename.startswith("assets/models/") or item.filename in new_files:
                continue
            if item.filename == "resources.arsc":
                raw_arsc = zin.read("resources.arsc")
                patched_arsc = patch_resources_arsc(raw_arsc, "SWAR")
                item.file_size = len(patched_arsc)
                item.compress_size = len(patched_arsc)
                item.compress_type = zipfile.ZIP_STORED
                zout.writestr(item, patched_arsc)
                continue
            # Handle DEX swapping: classes5.dex is loaded BEFORE classes6.dex!
            # Move old classes5.dex to classes13.dex, and write our new DEX as classes5.dex!
            if item.filename == "classes5.dex":
                old_c5_data = zin.read("classes5.dex")
                # Write old classes5.dex as classes13.dex
                zout.writestr("classes13.dex", old_c5_data)
                # Write our new compiled DEX (with updated LocalTransport) as classes5.dex!
                item.file_size = len(new_dex_data)
                item.compress_size = len(new_dex_data)
                item.compress_type = zipfile.ZIP_STORED
                zout.writestr(item, new_dex_data)
                print(f"  * Replaced classes5.dex with new DEX ({len(new_dex_data)} bytes), moved old to classes13.dex ({len(old_c5_data)} bytes)")
                continue
            # Strip old signature files
            if item.filename.startswith("META-INF/") and (item.filename.endswith(".SF") or item.filename.endswith(".RSA") or item.filename.endswith(".MF")):
                continue
            zout.writestr(item, zin.read(item.filename))
        
        # Add new generated assets, icons & models
        for zip_path, data in new_files.items():
            zi = zipfile.ZipInfo(zip_path)
            if zip_path.endswith('.onnx') or zip_path.endswith('.pth') or zip_path.endswith('.wav'):
                zi.compress_type = zipfile.ZIP_STORED
            else:
                zi.compress_type = zipfile.ZIP_DEFLATED
            zout.writestr(zi, data)

    print(f"Unsigned APK created: {os.path.getsize(OUT_UNSIGNED)} bytes")

    # 5. Zipalign APK
    print("\nAligning APK with zipalign...")
    if os.path.exists(OUT_ALIGNED): os.remove(OUT_ALIGNED)
    cmd_align = [ZIPALIGN, "-p", "-f", "4", OUT_UNSIGNED, OUT_ALIGNED]
    res_align = subprocess.run(cmd_align, capture_output=True, text=True)
    if res_align.returncode != 0:
        raise RuntimeError(f"Zipalign failed: {res_align.stderr}\n{res_align.stdout}")
    print(f"Aligned APK created: {os.path.getsize(OUT_ALIGNED)} bytes")

    # 6. Sign APK with apksigner
    print("\nSigning APK with debug key...")
    if os.path.exists(OUT_SIGNED): os.remove(OUT_SIGNED)
    env = os.environ.copy()
    env["JAVA_HOME"] = JAVA_HOME
    env["PATH"] = f"{os.path.join(JAVA_HOME, 'bin')};{env.get('PATH', '')}"

    cmd_sign = [
        APKSIGNER, "sign",
        "--ks", KEYSTORE,
        "--ks-pass", "pass:android",
        "--key-pass", "pass:android",
        "--ks-key-alias", "androiddebugkey",
        "--out", OUT_SIGNED,
        OUT_ALIGNED
    ]
    res_sign = subprocess.run(cmd_sign, env=env, capture_output=True, text=True)
    if res_sign.returncode != 0:
        raise RuntimeError(f"Signing failed: {res_sign.stderr}\n{res_sign.stdout}")
    print(f"Signed APK created: {os.path.getsize(OUT_SIGNED)} bytes")

    # 7. Verify signature and badging
    cmd_verify = [APKSIGNER, "verify", "--verbose", OUT_SIGNED]
    res_verify = subprocess.run(cmd_verify, env=env, capture_output=True, text=True)
    print(f"Signature Verification:\n{res_verify.stdout.strip()}")

    res_badge = subprocess.run([AAPT2, "dump", "badging", OUT_SIGNED], capture_output=True, text=True)
    for line in res_badge.stdout.splitlines():
        if "package:" in line or "application-label:" in line or "application:" in line:
            print(f"  [BADGE] {line}")

    # 8. Copy signed APK to public download locations
    print("\nDistributing APK to web assets...")
    dest_paths = [
        os.path.join(ROOT, "apps", "web", "public", "swar-ipod.apk"),
        os.path.join(ROOT, "apps", "web", "public", "swar.apk"),
        os.path.join(ROOT, "apps", "web", "dist", "swar-ipod.apk"),
        os.path.join(ROOT, "apps", "web", "dist", "swar.apk"),
    ]
    for dst in dest_paths:
        try:
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            if os.path.exists(dst):
                try:
                    os.remove(dst)
                except Exception:
                    pass
            shutil.copy2(OUT_SIGNED, dst)
            print(f"  -> {dst} ({os.path.getsize(dst)} bytes)")
        except Exception as e:
            print(f"  [WARN] Could not copy to {dst}: {e}")

    # 9. Install to connected device via ADB
    print("\nChecking for connected Android devices...")
    cmd_devices = [ADB, "devices"]
    res_devices = subprocess.run(cmd_devices, capture_output=True, text=True)
    print(res_devices.stdout)

    devices = []
    for line in res_devices.stdout.splitlines():
        parts = line.strip().split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])

    if not devices:
        print("No authorized device found to install.")
        return

    package_name = "in.itantra.mobile.ipod"
    for dev in devices:
        print(f"\nInstalling {OUT_SIGNED} onto device {dev} (Package: {package_name})...")
        install_res = subprocess.run([ADB, "-s", dev, "install", "-r", "-d", OUT_SIGNED], capture_output=True, text=True)
        print(install_res.stdout)
        if install_res.stderr:
            print(install_res.stderr)
        
        if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in install_res.stderr or "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in install_res.stdout:
            print("Existing app signed with different certificate. Reinstalling cleanly...")
            subprocess.run([ADB, "-s", dev, "uninstall", package_name], capture_output=True)
            install_res = subprocess.run([ADB, "-s", dev, "install", OUT_SIGNED], capture_output=True, text=True)
            print(install_res.stdout)

        if "Success" in install_res.stdout:
            print("Granting required permissions...")
            perms = [
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.BLUETOOTH_SCAN",
                "android.permission.BLUETOOTH_ADVERTISE",
                "android.permission.NEARBY_WIFI_DEVICES",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.RECORD_AUDIO",
                "android.permission.POST_NOTIFICATIONS"
            ]
            for p in perms:
                subprocess.run([ADB, "-s", dev, "shell", "pm", "grant", package_name, p], capture_output=True)
            
            print(f"Launching {package_name}/in.itantra.mobile.MainActivity on {dev}...")
            subprocess.run([ADB, "-s", dev, "shell", "am", "start", "-n", f"{package_name}/in.itantra.mobile.MainActivity"], capture_output=True)
            print(f"APP LAUNCHED SUCCESSFULLY ON {dev}!")
        else:
            print(f"Installation failed: {install_res.stdout} {install_res.stderr}")

if __name__ == "__main__":
    repack_and_deploy()
