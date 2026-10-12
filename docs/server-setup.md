# MyPapers server

Runs on `runnersidekick.ibarak.org` (`ssh -p 22238 root@runnersidekick.ibarak.org`),
next to the CouchDB 3.5 that was already there, at **https://couchdb.ibarak.me**.

```
app ──Google ID token──▶ nginx /mypapers-api/session ──▶ mypapers-auth (127.0.0.1:8790)
                                                            │ checks Google signature, our client ID, allowlist
                                                            │ creates mypapers_u_<google-id> on first sign-in
app ◀──────────── CouchDB JWT (30 days) ───────────────────┘
app ──Bearer JWT──▶ nginx ──▶ CouchDB (127.0.0.1:5984) /mypapers_u_<google-id>
```

Each Google account has its own database; only that account's CouchDB user
(`g_<google-id>`, the JWT `sub`) is a member, so accounts never see each other's papers.

## Files

| What | Where |
|---|---|
| Auth service binary | `/usr/local/bin/mypapers-auth` (built from `server/auth`) |
| Its settings (secrets, root-only) | `/etc/mypapers-auth/env` |
| Allowed Google accounts | `/etc/mypapers-auth/allowlist` — one email per line, read on every sign-in, no restart |
| systemd unit | `/etc/systemd/system/mypapers-auth.service` |
| CouchDB admin for MyPapers | `/opt/couchdb/etc/local.d/20-mypapers-admin.ini` (`mypapers-admin`, hashed) |
| CouchDB JWT settings | `/opt/couchdb/etc/local.d/30-mypapers-jwt.ini` (HMAC key = `JWT_SECRET` in the env file) |
| nginx route | `location /mypapers-api/` in the `couchdb.ibarak.me` site (`couchdb.conf`) |
| Homepage + privacy policy (for Google's consent screen) | `/var/www/mypapers/` (from `server/site`), served at `/mypapers-api/home` and `/mypapers-api/privacy` |
| Nightly backup | `/etc/cron.d/couchdb-backup` → `/var/backups/couchdb-YYYY-MM-DD.tgz`, 14 days |

CouchDB already required a login for every request and allowed 50 MB documents; nothing else changed there.

## Common tasks

Allow another Google account:
```bash
echo 'someone@gmail.com' >> /etc/mypapers-auth/allowlist
```

Set the Google OAuth web client ID (after creating it in Google Cloud):
```bash
sed -i 's/^GOOGLE_CLIENT_IDS=.*/GOOGLE_CLIENT_IDS=<web-client-id>.apps.googleusercontent.com/' /etc/mypapers-auth/env
systemctl restart mypapers-auth
```

Deploy a new auth service build (from the repo, on the Mac):
```bash
cd server/auth && go test ./... && GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /tmp/mypapers-auth .
scp -P 22238 /tmp/mypapers-auth root@runnersidekick.ibarak.org:/usr/local/bin/mypapers-auth
ssh -p 22238 root@runnersidekick.ibarak.org systemctl restart mypapers-auth
```

Logs: `journalctl -u mypapers-auth -f` (sign-ins, refusals).

Sign everyone out (invalidates every issued token): put a new `JWT_SECRET`
(`openssl rand -base64 48`) in both the env file and `30-mypapers-jwt.ini`,
then `systemctl restart couchdb mypapers-auth`.

Admin UI: `ssh -p 22238 -L 5984:127.0.0.1:5984 root@runnersidekick.ibarak.org`, then http://localhost:5984/_utils.

## Checks

```bash
curl -s https://couchdb.ibarak.me/mypapers-api/health                       # ok
curl -s -X POST https://couchdb.ibarak.me/mypapers-api/session -d '{"idToken":"x"}'  # invalid Google sign-in
```

## Notes

- Port 5984 is also open on all interfaces with the firewall (ufw) off, so CouchDB
  answers plain HTTP directly on the server's IP (login still required). Closing it
  (`bind_address = 127.0.0.1`) would leave only the nginx HTTPS route.
- Leftovers from earlier: the unused Obsidian databases, and a `mypapers` database
  with a `mypapers` password user from before Google sign-in. Both can be deleted.
