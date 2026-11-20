import zipfile
import os

zip_name = "jarvis_mobile.zip"
if os.path.exists(zip_name):
    os.remove(zip_name)

with zipfile.ZipFile(zip_name, "w", zipfile.ZIP_DEFLATED) as zf:
    for root, dirs, files in os.walk("mobile"):
        if "__pycache__" in root:
            continue
        for f in files:
            if f.endswith(".pyc"):
                continue
            full_path = os.path.join(root, f)
            # Guarantee Linux / Android forward slashes
            rel_path = os.path.relpath(full_path, ".").replace("\\", "/")
            zf.write(full_path, rel_path)

print(f"Created {zip_name} with Linux forward slashes. Size: {os.path.getsize(zip_name)} bytes")
