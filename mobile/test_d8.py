import os, subprocess, zipfile

JAVA_HOME = r"C:\Users\Yogya\.jdk17"
BUILD_TOOLS = r"C:\Program Files\Unity\Hub\Editor\6000.6.0f1\Editor\Data\PlaybackEngines\AndroidPlayer\SDK\build-tools\36.0.0"
D8 = os.path.join(BUILD_TOOLS, "d8.bat")
ANDROID_JAR = r"C:\Program Files\Unity\Hub\Editor\6000.6.0f1\Editor\Data\PlaybackEngines\AndroidPlayer\SDK\platforms\android-34\android.jar"

env = os.environ.copy()
env["JAVA_HOME"] = JAVA_HOME
env["PATH"] = JAVA_HOME + r"\bin;" + env.get("PATH", "")

with zipfile.ZipFile("device_ipod.apk") as z:
    z.extract("classes6.dex", "scratch/test_dex")

classes_dir = r"scratch\build\classes"
out_dir = r"scratch\test_dex\merge_test"
os.makedirs(out_dir, exist_ok=True)

class_files = []
for root, dirs, files in os.walk(classes_dir):
    for f in files:
        if f.endswith(".class"):
            class_files.append(os.path.join(root, f))

cmd = [D8, "--lib", ANDROID_JAR, "--min-api", "28", "--output", out_dir, r"scratch\test_dex\classes6_pruned.dex"] + class_files
res = subprocess.run(cmd, env=env, capture_output=True, text=True)
print("Return code:", res.returncode)
print("STDOUT:", res.stdout)
print("STDERR:", res.stderr)
