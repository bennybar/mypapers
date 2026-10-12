package main

import (
	"crypto"
	"crypto/hmac"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

var testKey, _ = rsa.GenerateKey(rand.Reader, 2048)

type fakeKeys struct{}

func (fakeKeys) key(kid string) (*rsa.PublicKey, error) {
	if kid != "k1" {
		return nil, errors.New("unknown")
	}
	return &testKey.PublicKey, nil
}

var now = time.Unix(1_800_000_000, 0)

func googleToken(t *testing.T, claims map[string]any) string {
	enc := func(v any) string {
		b, _ := json.Marshal(v)
		return base64.RawURLEncoding.EncodeToString(b)
	}
	unsigned := enc(map[string]string{"alg": "RS256", "kid": "k1"}) + "." + enc(claims)
	digest := sha256.Sum256([]byte(unsigned))
	sig, err := rsa.SignPKCS1v15(rand.Reader, testKey, crypto.SHA256, digest[:])
	if err != nil {
		t.Fatal(err)
	}
	return unsigned + "." + base64.RawURLEncoding.EncodeToString(sig)
}

func goodClaims() map[string]any {
	return map[string]any{
		"iss": "https://accounts.google.com", "aud": "client-1", "sub": "1234567890",
		"email": "bennybar@gmail.com", "email_verified": true, "exp": now.Unix() + 600,
	}
}

func TestVerifyAcceptsValidToken(t *testing.T) {
	c, err := verifyGoogleToken(googleToken(t, goodClaims()), fakeKeys{}, []string{"client-1"}, now)
	if err != nil || c.Email != "bennybar@gmail.com" || c.Sub != "1234567890" {
		t.Fatalf("got %v, %v", c, err)
	}
}

func TestVerifyRejects(t *testing.T) {
	cases := map[string]func(map[string]any){
		"other app's token": func(c map[string]any) { c["aud"] = "someone-else" },
		"expired":           func(c map[string]any) { c["exp"] = now.Unix() - 1 },
		"wrong issuer":      func(c map[string]any) { c["iss"] = "https://evil.example" },
		"unverified email":  func(c map[string]any) { c["email_verified"] = false },
	}
	for name, mutate := range cases {
		claims := goodClaims()
		mutate(claims)
		if _, err := verifyGoogleToken(googleToken(t, claims), fakeKeys{}, []string{"client-1"}, now); err == nil {
			t.Errorf("%s: accepted", name)
		}
	}
	// Tampered payload: the signature no longer matches.
	tok := googleToken(t, goodClaims())
	parts := strings.Split(tok, ".")
	claims := goodClaims()
	claims["email"] = "attacker@gmail.com"
	b, _ := json.Marshal(claims)
	parts[1] = base64.RawURLEncoding.EncodeToString(b)
	if _, err := verifyGoogleToken(strings.Join(parts, "."), fakeKeys{}, []string{"client-1"}, now); err == nil {
		t.Error("tampered token accepted")
	}
}

func TestCouchTokenIsHS256SignedWithSecret(t *testing.T) {
	secret := []byte("0123456789abcdef0123456789abcdef")
	tok, expMs := couchToken(secret, "g_1", "a@b.c", now, time.Hour)
	parts := strings.Split(tok, ".")
	mac := hmac.New(sha256.New, secret)
	mac.Write([]byte(parts[0] + "." + parts[1]))
	if base64.RawURLEncoding.EncodeToString(mac.Sum(nil)) != parts[2] {
		t.Fatal("bad signature")
	}
	var payload map[string]any
	decodeSegment(parts[1], &payload)
	if payload["sub"] != "g_1" || int64(payload["exp"].(float64)) != now.Unix()+3600 || expMs != (now.Unix()+3600)*1000 {
		t.Fatalf("payload %v exp %d", payload, expMs)
	}
}

func TestSessionCreatesDatabaseForAllowlistedUser(t *testing.T) {
	var mu sync.Mutex
	var calls []string
	couchSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		body, _ := io.ReadAll(r.Body)
		calls = append(calls, r.Method+" "+r.URL.Path+" "+strings.TrimSpace(string(body)))
		mu.Unlock()
		if u, p, _ := r.BasicAuth(); u != "admin" || p != "pw" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		if r.URL.Path == "/mypapers_u_1234567890" {
			w.WriteHeader(http.StatusCreated)
			return
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer couchSrv.Close()

	dir := t.TempDir()
	allow := filepath.Join(dir, "allowlist")
	os.WriteFile(allow, []byte("# the only user\nBennyBar@gmail.com\n"), 0o600)
	s := &server{
		cfg: config{
			ClientIDs: []string{"client-1"}, AllowlistFile: allow, CouchURL: couchSrv.URL,
			CouchUser: "admin", CouchPassword: "pw", JWTSecret: make([]byte, 32), TokenTTL: time.Hour,
		},
		keys: fakeKeys{}, now: func() time.Time { return now },
	}

	post := func(token string) *httptest.ResponseRecorder {
		rec := httptest.NewRecorder()
		body, _ := json.Marshal(map[string]string{"idToken": token})
		s.session(rec, httptest.NewRequest(http.MethodPost, "/session", strings.NewReader(string(body))))
		return rec
	}

	rec := post(googleToken(t, goodClaims()))
	if rec.Code != http.StatusOK {
		t.Fatalf("status %d: %s", rec.Code, rec.Body)
	}
	var resp struct {
		DB, Token, Email string
		ExpiresAt        int64
	}
	json.Unmarshal(rec.Body.Bytes(), &resp)
	if resp.DB != "mypapers_u_1234567890" || resp.Token == "" {
		t.Fatalf("response %+v", resp)
	}
	if len(calls) != 2 || !strings.Contains(calls[1], `"members":{"names":["g_1234567890"]`) {
		t.Fatalf("couch calls %v", calls)
	}

	// Not on the allowlist: refused, nothing created.
	calls = nil
	other := goodClaims()
	other["email"] = "stranger@gmail.com"
	if rec := post(googleToken(t, other)); rec.Code != http.StatusForbidden || len(calls) != 0 {
		t.Fatalf("stranger: %d, calls %v", rec.Code, calls)
	}
	if rec := post("garbage"); rec.Code != http.StatusUnauthorized {
		t.Fatalf("garbage: %d", rec.Code)
	}
}
