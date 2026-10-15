"""Create local secrets and storage without displaying or replacing credentials."""
import os
from pathlib import Path
import secrets

os.umask(0o077)
root = Path(__file__).resolve().parent
for name in ('secrets', 'data'):
    (root / name).mkdir(mode=0o700, exist_ok=True)
path = root / 'secrets' / 'ingest_token'
try:
    with path.open('x') as target:
        target.write(secrets.token_urlsafe(48) + '\n')
except FileExistsError:
    pass
path.chmod(0o600)
env = root / '.env'
if not env.exists():
    env.write_text(f'LOCAL_UID={os.getuid()}\nLOCAL_GID={os.getgid()}\n')
print('Local storage and credential file ready; no credential displayed.')
