"""Generate our own codec fixtures; fetch checksum-pinned models only for test APK."""
import hashlib
import pathlib
import shutil
import subprocess
import urllib.request

root = pathlib.Path("app/src/androidTest/assets")
root.mkdir(parents=True, exist_ok=True)
if not shutil.which("ffmpeg"):
    subprocess.run(["sudo", "apt-get", "update", "-qq"], check=True)
    subprocess.run(["sudo", "apt-get", "install", "-y", "ffmpeg"], check=True)
for name, rate, codec in [
    ("tone24.wav", 24000, "pcm_s16le"), ("tone48.mp3", 48000, "libmp3lame"),
    ("tone48.flac", 48000, "flac"), ("tone48.m4a", 48000, "aac"),
    ("tone48.ogg", 48000, "libvorbis"),
]:
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", "sine=frequency=997:duration=1", "-ac", "2",
        "-ar", str(rate), "-c:a", codec, str(root / name)], check=True)
models = [{"file":"UVR-MDX-NET-Voc_FT.onnx","sha256":"534b2070fcc7df514b13ef660dc8cbb328679c2374d04354a5c42bb14ecce111"},{"file":"kuielab_a_vocals.onnx","sha256":"daba83c2ee1afee9139766ad64c9b6808d6b6f092fff04bed3338be50baac721"},{"file":"kuielab_a_drums.onnx","sha256":"40f586b7091934dd6f5563f0cba8f14bad57ce88440da1098bf388ea716c2901"},{"file":"kuielab_a_bass.onnx","sha256":"0c3e77b9963185b1ea6bb46a4b8924137d9370fc1ccdefec7b1b416ef550dcaa"},{"file":"kuielab_a_other.onnx","sha256":"7b67a1dcb5f232153528c59960b4c7bf8dc736b8114de360af0e719633f53358"}]
for model in models:
    target = root / model["file"]
    urllib.request.urlretrieve("https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/" + model["file"], target)
    assert hashlib.sha256(target.read_bytes()).hexdigest() == model["sha256"]
print("Generated 5 audio fixtures and verified 5 real models for instrumented tests.")
