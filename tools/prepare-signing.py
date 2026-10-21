"""Load one long-lived PKCS12 signing key from GitHub Actions secret. Never generate in CI."""
import base64
import json
import os
from pathlib import Path

raw = os.environ.get("ANDROID_SIGNING", "")
if not raw:
    raise SystemExit("ANDROID_SIGNING missing: signed publication forbidden.")
secret = json.loads(raw)
password = secret["password"]
alias = secret["alias"]
blob = secret["keystoreBase64"]
if not password or not alias or any(ch in password + alias for ch in "\r\n"):
    raise SystemExit("Invalid secret")
if len(blob) > 40000 or len(password) > 1024:
    raise SystemExit("Secret exceeds size limit")
for value in (password, alias, blob):
    print("::add-mask::" + value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A"))
key = Path(os.environ["RUNNER_TEMP"]) / "capt-usb-print-release.p12"
key.write_bytes(base64.b64decode(blob, validate=True))
key.chmod(0o600)
with Path(os.environ["GITHUB_ENV"]).open("a") as f:
    f.write("RELEASE_KEYSTORE_PATH=" + str(key) + "\n")
    f.write("RELEASE_STORE_PASSWORD=" + password + "\n")
    f.write("RELEASE_KEY_ALIAS=" + alias + "\n")
print("Permanent signing key prepared outside Git checkout.")
