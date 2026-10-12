# MyPapers server: CouchDB + nginx on Ubuntu

Host: `mypapers.ibarak.org`. CouchDB listens on localhost only; nginx is the
public HTTPS front (TLS by certbot). Files are stored as CouchDB attachments.

## 1. CouchDB (official apt repo)

```bash
sudo apt update && sudo apt install -y curl apt-transport-https gnupg
curl -fsSL https://couchdb.apache.org/repo/keys.asc \
  | gpg --dearmor | sudo tee /usr/share/keyrings/couchdb-archive-keyring.gpg >/dev/null
source /etc/os-release
echo "deb [signed-by=/usr/share/keyrings/couchdb-archive-keyring.gpg] https://apache.jfrog.io/artifactory/couchdb-deb/ ${VERSION_CODENAME} main" \
  | sudo tee /etc/apt/sources.list.d/couchdb.list
sudo apt update && sudo apt install -y couchdb
```

Installer prompts:
- Configuration: **standalone**
- Erlang magic cookie: any random string
- Bind address: **127.0.0.1** (nginx is the only way in)
- Admin password: a strong one (this is the server admin, not the app login)

If `apt update` says the repo has no release for your Ubuntu codename (a very
new Ubuntu), use the previous LTS codename in the `deb` line (e.g. `noble`).

## 2. CouchDB settings

`sudo nano /opt/couchdb/etc/local.d/mypapers.ini`:

```ini
[chttpd]
bind_address = 127.0.0.1
port = 5984
; every request needs a login (no anonymous access, not even to /)
require_valid_user = true
; scanned PDFs/photos: allow up to 50 MB per request
max_http_request_size = 52428800

[couchdb]
single_node = true
max_attachment_size = 52428800

[chttpd_auth]
; stay logged in for a week between requests
timeout = 604800
```

```bash
sudo systemctl restart couchdb
curl -s -u admin:ADMIN_PASSWORD http://127.0.0.1:5984/   # {"couchdb":"Welcome",...}
```

## 3. Database and the app's user

The app logs in as a normal (non-admin) user who can only use the `mypapers` database.

```bash
A='admin:ADMIN_PASSWORD'
H='Content-Type: application/json'
# system databases (a standalone install may not have them yet; "file_exists" is fine)
curl -s -u $A -X PUT http://127.0.0.1:5984/_users
curl -s -u $A -X PUT http://127.0.0.1:5984/_replicator
# the app user
curl -s -u $A -X PUT http://127.0.0.1:5984/_users/org.couchdb.user:benny -H "$H" \
  -d '{"name":"benny","password":"APP_PASSWORD","roles":[],"type":"user"}'
# the database, members = that user only
curl -s -u $A -X PUT http://127.0.0.1:5984/mypapers
curl -s -u $A -X PUT http://127.0.0.1:5984/mypapers/_security -H "$H" \
  -d '{"admins":{"names":[],"roles":[]},"members":{"names":["benny"],"roles":[]}}'
```

## 4. nginx

`sudo nano /etc/nginx/sites-available/mypapers.ibarak.org`:

```nginx
server {
    listen 80;
    listen [::]:80;
    server_name mypapers.ibarak.org;

    # uploads of scanned documents
    client_max_body_size 60m;

    # Admin UI and server internals: not reachable from outside
    # (use an SSH tunnel: ssh -L 5984:127.0.0.1:5984 server, then http://localhost:5984/_utils)
    location ~ ^/(_utils|_node|_config|_cluster_setup|_membership|_dbs|_all_dbs|_active_tasks|_scheduler|_reshard) {
        return 404;
    }

    location / {
        proxy_pass http://127.0.0.1:5984;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        # the app keeps a _changes feed open for live sync
        proxy_buffering off;
        proxy_request_buffering off;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
}
```

```bash
sudo ln -s /etc/nginx/sites-available/mypapers.ibarak.org /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
# then: sudo certbot --nginx -d mypapers.ibarak.org
```

## 5. Check from your Mac (after certbot)

```bash
curl -s https://mypapers.ibarak.org/                        # 401 (login required)
curl -s -u benny:APP_PASSWORD https://mypapers.ibarak.org/mypapers   # {"db_name":"mypapers",...}
curl -s -o /dev/null -w '%{http_code}\n' https://mypapers.ibarak.org/_utils/   # 404
```

## 6. Backups (the server holds the only off-phone copy)

CouchDB's files are append-only, so copying them while it runs is safe:

```bash
# /etc/cron.d/mypapers-backup — nightly at 03:30, keeps 14 days
30 3 * * * root tar czf /var/backups/couchdb-$(date +\%F).tgz -C /opt/couchdb data && find /var/backups -name 'couchdb-*.tgz' -mtime +14 -delete
```

Copy `/var/backups` somewhere off this server (another machine, rclone to cloud storage, etc.).
