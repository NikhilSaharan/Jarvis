#!/data/data/com.termux/files/usr/bin/bash
# One-Click Termux Environment Setup for J.A.R.V.I.S. Mobile

echo "=========================================================="
echo "      J.A.R.V.I.S. MOBILE ENVIRONMENT INSTALLER          "
echo "=========================================================="

echo "[1/4] Updating Termux packages..."
pkg update -y

echo "[2/4] Installing Linux tools & runtimes..."
pkg install -y python git termux-api ffmpeg clang mpv

echo "[3/4] Installing Python AI & Automation libraries..."
pip install --upgrade pip
pip install requests python-dotenv edge-tts pygame pydantic google-genai elevenlabs

echo "[4/4] Verifying Shizuku rish privilege bridge..."
if command -v rish &> /dev/null; then
    echo "rish found! Testing Shizuku connection:"
    rish -c "echo '>> Shizuku Bridge ACTIVE (UID: 2000 ADB)'"
else
    echo ">> Please export rish from Shizuku settings or run Shizuku pairing."
fi

echo "Acquiring permanent CPU wake lock..."
termux-wake-lock

echo "=========================================================="
echo ">> Setup complete! Run: python mobile_main.py"
echo "=========================================================="
