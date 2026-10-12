// mypapers-auth trades a Google sign-in for access to the user's own CouchDB
// database. The app sends the Google ID token from Sign in with Google; this
// checks it (Google's signature, our client ID, an allowlisted email), creates
// the user's database on first sign-in, and returns a CouchDB JWT for it.
// CouchDB trusts the JWT through the shared HMAC key in its [jwt_keys].
package main

import (
	"bufio"
	"bytes"
	"crypto"
	"crypto/hmac"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"math/big"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

const googleCertsURL = "https://www.googleapis.com/oauth2/v3/certs"

type config struct {
	Listen        string
	ClientIDs     []string // accepted "aud" values (the app's web client ID)
	AllowlistFile string
	CouchURL      string
	CouchUser     string
	CouchPassword string
	JWTSecret     []byte // the same key as CouchDB's [jwt_keys] hmac:_default (base64 there)
	TokenTTL      time.Duration
}

func loadConfig() (config, error) {
	secret, err := base64.StdEncoding.DecodeString(os.Getenv("JWT_SECRET"))
	if err != nil || len(secret) < 32 {
		return config{}, errors.New("JWT_SECRET must be base64 of at least 32 bytes")
	}
	days, _ := strconv.Atoi(getenv("TOKEN_DAYS", "30"))
	c := config{
		Listen:        getenv("LISTEN", "127.0.0.1:8790"),
		ClientIDs:     splitNonEmpty(os.Getenv("GOOGLE_CLIENT_IDS")),
		AllowlistFile: getenv("ALLOWLIST_FILE", "/etc/mypapers-auth/allowlist"),
		CouchURL:      strings.TrimRight(getenv("COUCH_URL", "http://127.0.0.1:5984"), "/"),
		CouchUser:     os.Getenv("COUCH_ADMIN_USER"),
		CouchPassword: os.Getenv("COUCH_ADMIN_PASSWORD"),
		JWTSecret:     secret,
		TokenTTL:      time.Duration(days) * 24 * time.Hour,
	}
	if len(c.ClientIDs) == 0 || c.CouchUser == "" {
		return config{}, errors.New("GOOGLE_CLIENT_IDS and COUCH_ADMIN_USER are required")
	}
	return c, nil
}

func getenv(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func splitNonEmpty(s string) []string {
	var out []string
	for _, p := range strings.Split(s, ",") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

// ---- Google ID token ----

type googleClaims struct {
	Iss           string          `json:"iss"`
	Aud           string          `json:"aud"`
	Sub           string          `json:"sub"`
	Email         string          `json:"email"`
	EmailVerified json.RawMessage `json:"email_verified"` // true, or "true" in older tokens
	Exp           int64           `json:"exp"`
}

// keySource returns Google's public key for a key ID.
type keySource interface {
	key(kid string) (*rsa.PublicKey, error)
}

// googleKeys caches Google's signing keys (they rotate; the response says for how long).
type googleKeys struct {
	mu        sync.Mutex
	keys      map[string]*rsa.PublicKey
	expires   time.Time
	lastFetch time.Time
}

// certsClient gives up on a slow Google rather than holding every sign-in behind it.
var certsClient = &http.Client{Timeout: 10 * time.Second}

func (g *googleKeys) key(kid string) (*rsa.PublicKey, error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if k, ok := g.keys[kid]; ok && time.Now().Before(g.expires) {
		return k, nil
	}
	// An unknown kid with fresh keys: refetch at most once a minute (junk tokens can't force a fetch each).
	if time.Now().Before(g.expires) && time.Since(g.lastFetch) < time.Minute {
		return nil, fmt.Errorf("unknown key %q", kid)
	}
	g.lastFetch = time.Now()
	if err := g.fetch(); err != nil {
		return nil, err
	}
	if k, ok := g.keys[kid]; ok {
		return k, nil
	}
	return nil, fmt.Errorf("unknown key %q", kid)
}

var maxAge = regexp.MustCompile(`max-age=(\d+)`)

func (g *googleKeys) fetch() error {
	resp, err := certsClient.Get(googleCertsURL)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	var body struct {
		Keys []struct{ Kid, N, E string } `json:"keys"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		return err
	}
	keys := map[string]*rsa.PublicKey{}
	for _, k := range body.Keys {
		n, err1 := base64.RawURLEncoding.DecodeString(k.N)
		e, err2 := base64.RawURLEncoding.DecodeString(k.E)
		if err1 != nil || err2 != nil {
			continue
		}
		keys[k.Kid] = &rsa.PublicKey{N: new(big.Int).SetBytes(n), E: int(new(big.Int).SetBytes(e).Int64())}
	}
	ttl := time.Hour
	if m := maxAge.FindStringSubmatch(resp.Header.Get("Cache-Control")); m != nil {
		if s, err := strconv.Atoi(m[1]); err == nil {
			ttl = time.Duration(s) * time.Second
		}
	}
	g.keys, g.expires = keys, time.Now().Add(ttl)
	return nil
}

var errInvalid = errors.New("invalid Google sign-in")

func verifyGoogleToken(token string, keys keySource, clientIDs []string, now time.Time) (*googleClaims, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return nil, errInvalid
	}
	var header struct{ Alg, Kid string }
	if err := decodeSegment(parts[0], &header); err != nil || header.Alg != "RS256" {
		return nil, errInvalid
	}
	pub, err := keys.key(header.Kid)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", errInvalid, err)
	}
	sig, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil {
		return nil, errInvalid
	}
	digest := sha256.Sum256([]byte(parts[0] + "." + parts[1]))
	if rsa.VerifyPKCS1v15(pub, crypto.SHA256, digest[:], sig) != nil {
		return nil, errInvalid
	}
	var c googleClaims
	if err := decodeSegment(parts[1], &c); err != nil {
		return nil, errInvalid
	}
	if c.Iss != "accounts.google.com" && c.Iss != "https://accounts.google.com" {
		return nil, errInvalid
	}
	if !contains(clientIDs, c.Aud) || now.Unix() >= c.Exp || c.Sub == "" {
		return nil, errInvalid
	}
	if v := strings.Trim(string(c.EmailVerified), `"`); v != "true" {
		return nil, errInvalid
	}
	return &c, nil
}

func decodeSegment(seg string, v any) error {
	b, err := base64.RawURLEncoding.DecodeString(seg)
	if err != nil {
		return err
	}
	return json.Unmarshal(b, v)
}

func contains(list []string, s string) bool {
	for _, x := range list {
		if x == s {
			return true
		}
	}
	return false
}

// allowed reads the allowlist on every sign-in, so edits apply without a restart.
// One email per line; # starts a comment.
func allowed(file, email string) (bool, error) {
	f, err := os.Open(file)
	if err != nil {
		return false, err
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := strings.TrimSpace(strings.SplitN(sc.Text(), "#", 2)[0])
		if line != "" && strings.EqualFold(line, email) {
			return true, nil
		}
	}
	return false, sc.Err()
}

// ---- CouchDB ----

// userNames: the CouchDB user (the JWT "sub") and database for a Google account.
func userNames(googleSub string) (user, db string) {
	return "g_" + googleSub, "mypapers_u_" + googleSub
}

// ensureDatabase creates the user's database if needed and makes them its only member.
func ensureDatabase(c config, db, user string) error {
	if err := couch(c, http.MethodPut, "/"+db, nil, http.StatusCreated, http.StatusPreconditionFailed); err != nil {
		return err
	}
	security := map[string]any{
		"admins":  map[string][]string{"names": {}, "roles": {}},
		"members": map[string][]string{"names": {user}, "roles": {}},
	}
	return couch(c, http.MethodPut, "/"+db+"/_security", security, http.StatusOK)
}

func couch(c config, method, path string, body any, okCodes ...int) error {
	var buf bytes.Buffer
	if body != nil {
		if err := json.NewEncoder(&buf).Encode(body); err != nil {
			return err
		}
	}
	req, err := http.NewRequest(method, c.CouchURL+path, &buf)
	if err != nil {
		return err
	}
	req.SetBasicAuth(c.CouchUser, c.CouchPassword)
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	for _, ok := range okCodes {
		if resp.StatusCode == ok {
			return nil
		}
	}
	return fmt.Errorf("couchdb %s %s: %s", method, path, resp.Status)
}

// couchToken signs a JWT CouchDB accepts as [user] (HS256, no kid = its hmac:_default key).
func couchToken(secret []byte, user, email string, now time.Time, ttl time.Duration) (string, int64) {
	exp := now.Add(ttl).Unix()
	enc := func(v any) string {
		b, _ := json.Marshal(v)
		return base64.RawURLEncoding.EncodeToString(b)
	}
	unsigned := enc(map[string]string{"alg": "HS256", "typ": "JWT"}) + "." +
		enc(map[string]any{"sub": user, "email": email, "iat": now.Unix(), "exp": exp})
	mac := hmac.New(sha256.New, secret)
	mac.Write([]byte(unsigned))
	return unsigned + "." + base64.RawURLEncoding.EncodeToString(mac.Sum(nil)), exp * 1000
}

// ---- HTTP ----

type server struct {
	cfg  config
	keys keySource
	now  func() time.Time
}

func (s *server) session(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST only", http.StatusMethodNotAllowed)
		return
	}
	var req struct {
		IDToken string `json:"idToken"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16<<10)).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	claims, err := verifyGoogleToken(req.IDToken, s.keys, s.cfg.ClientIDs, s.now())
	if err != nil {
		log.Printf("rejected token: %v", err)
		http.Error(w, "invalid Google sign-in", http.StatusUnauthorized)
		return
	}
	ok, err := allowed(s.cfg.AllowlistFile, claims.Email)
	if err != nil {
		log.Printf("allowlist: %v", err)
		http.Error(w, "server error", http.StatusInternalServerError)
		return
	}
	if !ok {
		log.Printf("not allowlisted: %s", claims.Email)
		http.Error(w, "this account isn't allowed on this server", http.StatusForbidden)
		return
	}
	user, db := userNames(claims.Sub)
	if err := ensureDatabase(s.cfg, db, user); err != nil {
		log.Printf("ensure %s: %v", db, err)
		http.Error(w, "database error", http.StatusBadGateway)
		return
	}
	token, expiresAt := couchToken(s.cfg.JWTSecret, user, claims.Email, s.now(), s.cfg.TokenTTL)
	log.Printf("signed in: %s → %s", claims.Email, db)
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"db": db, "token": token, "expiresAt": expiresAt, "email": claims.Email,
	})
}

func main() {
	cfg, err := loadConfig()
	if err != nil {
		log.Fatal(err)
	}
	s := &server{cfg: cfg, keys: &googleKeys{}, now: time.Now}
	mux := http.NewServeMux()
	mux.HandleFunc("/session", s.session)
	mux.HandleFunc("/health", func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte("ok")) })
	log.Printf("listening on %s", cfg.Listen)
	log.Fatal(http.ListenAndServe(cfg.Listen, mux))
}
