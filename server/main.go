// deadliner-serve is launched by the native CLI with the selected profile path.
package main

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"time"
)

// One SELECT gives one committed snapshot, including changes still in SQLite's WAL.
// Keep this projection small: habits, credentials and sync history are outside v1.
const tasksSQL = `SELECT uid, title, note, state, start_at AS startAt, due_at AS dueAt
FROM task WHERE is_deleted = 0 ORDER BY title, uid`

func main() {
	if len(os.Args) != 3 {
		log.Fatal("expected profile path and output mode")
	}
	path := filepath.Join(os.Args[1], "deadliner.db")
	if _, err := os.Stat(path); err != nil {
		log.Fatal("Open the CLI profile with deadliner task list first: ", err)
	}
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		log.Fatal(err)
	}
	secret := make([]byte, 32)
	if _, err := rand.Read(secret); err != nil {
		log.Fatal(err)
	}
	token := hex.EncodeToString(secret)
	code := fmt.Sprintf("%d.%s", listener.Addr().(*net.TCPAddr).Port, token)
	if os.Args[2] == "json" {
		json.NewEncoder(os.Stdout).Encode(map[string]any{"ok": true, "version": 1, "data": map[string]string{"connectionCode": code}})
	} else {
		fmt.Printf("在 Mac 的「前往 → CLI 任务（只读）」中输入连接码：\n%s\n按 Ctrl+C 停止服务。\n", code)
	}
	server := http.Server{
		ReadHeaderTimeout: 3 * time.Second, ReadTimeout: 5 * time.Second, WriteTimeout: 10 * time.Second,
		IdleTimeout: 15 * time.Second, MaxHeaderBytes: 8192,
		Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Cache-Control", "no-store")
			if r.URL.Path != "/v1/tasks" {
				http.NotFound(w, r)
				return
			}
			if subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), []byte("Bearer "+token)) != 1 {
				w.WriteHeader(http.StatusUnauthorized)
				return
			}
			if r.Method != http.MethodGet {
				w.Header().Set("Allow", "GET")
				w.WriteHeader(http.StatusMethodNotAllowed)
				return
			}
			cmd := exec.CommandContext(r.Context(), "/usr/bin/sqlite3", "-batch", "-init", "/dev/null", "-readonly", "-json", path, tasksSQL)
			items, err := cmd.Output()
			if err != nil {
				http.Error(w, "CLI profile unavailable", http.StatusServiceUnavailable)
				return
			}
			if len(bytes.TrimSpace(items)) == 0 {
				items = []byte("[]")
			}
			etag := fmt.Sprintf("\"%x\"", sha256.Sum256(items))
			w.Header().Set("ETag", etag)
			if r.Header.Get("If-None-Match") == etag {
				w.WriteHeader(http.StatusNotModified)
				return
			}
			w.Header().Set("Content-Type", "application/json")
			json.NewEncoder(w).Encode(struct {
				Version int             `json:"version"`
				Items   json.RawMessage `json:"items"`
			}{1, items})
		}),
	}
	log.Fatal(server.Serve(listener))
}
