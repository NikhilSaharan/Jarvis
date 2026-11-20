#!/data/data/com.termux/files/usr/bin/bash
# Termux:Boot Startup Script for J.A.R.V.I.S. Mobile
# Automatically executes on phone restart / power-on.

# 1. Prevent Android from suspending the CPU
termux-wake-lock

# 2. Wait 10 seconds for Android system services and Shizuku to initialize
sleep 10

# 3. Navigate to Jarvis mobile codebase
cd ~/Jarvis/mobile || cd /sdcard/Jarvis/mobile || cd ~/storage/shared/Jarvis/mobile

# 4. Start 24/7 Autonomous Daemon in the background
nohup python mobile_main.py > ~/jarvis_boot.log 2>&1 &
