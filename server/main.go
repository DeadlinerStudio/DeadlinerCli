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
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// One SELECT gives one committed snapshot, including changes still in SQLite's WAL.
// Keep this projection small: habits, credentials and sync history are outside v1.
const tasksSQL = `SELECT uid, title, note, state, start_at AS startAt, due_at AS dueAt, updated_at AS updatedAt
FROM task WHERE is_deleted = 0 ORDER BY title, uid`

func main() {
	if len(os.Args) != 4 || (os.Args[3] != "read" && os.Args[3] != "write") {
		log.Fatal("expected profile path and output mode")
	}
	writable := os.Args[3] == "write"
	executable, err := os.Executable()
	if err != nil {
		log.Fatal(err)
	}
	cli := filepath.Join(filepath.Dir(executable), "deadliner")
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
		mode := "只读"
		if writable {
			mode = "可编辑"
		}
		fmt.Printf("在 Mac 的 CLI 任务界面输入连接码（%s）：\n%s\n按 Ctrl+C 停止服务。\n", mode, code)
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
			if writable && r.Method == http.MethodPost {
				writeTask(w, r, cli, os.Args[1])
				return
			}
			if r.Method != http.MethodGet {
				if writable {
					w.Header().Set("Allow", "GET, POST")
				} else {
					w.Header().Set("Allow", "GET")
				}
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
				Version  int             `json:"version"`
				Writable bool            `json:"writable"`
				Items    json.RawMessage `json:"items"`
			}{1, writable, items})
		}),
	}
	log.Fatal(server.Serve(listener))
}

// Dispatch only task verbs. The CLI performs revision checks and the write together
// inside its profile lock and routes every accepted mutation through Core.
func writeTask(w http.ResponseWriter, r *http.Request, cli, profile string) {
	var m struct {
		Op       string                     `json:"op"`
		UID      string                     `json:"uid"`
		Revision string                     `json:"revision"`
		Fields   map[string]json.RawMessage `json:"fields"`
	}
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&m); err != nil || decoder.Decode(&struct{}{}) != io.EOF ||
		m.UID == "" || strings.HasPrefix(m.UID, "--") || strings.ContainsRune(m.UID, 0) {
		http.Error(w, "Invalid task request", http.StatusBadRequest)
		return
	}
	args := []string{"--data-dir", profile, "--json", "task", m.Op}
	var input []byte
	switch m.Op {
	case "add":
		if m.Revision != "" || m.Fields == nil {
			http.Error(w, "Invalid creation", 400)
			return
		}
		m.Fields["uid"], _ = json.Marshal(m.UID)
	case "update", "complete", "reopen", "abandon", "archive", "unarchive", "delete":
		if m.Revision == "" || strings.ContainsRune(m.Revision, 0) {
			http.Error(w, "Missing task revision", http.StatusPreconditionRequired)
			return
		}
		args = append(args, m.UID, "--if-updated-at", m.Revision)
	default:
		http.Error(w, "Unknown task operation", 400)
		return
	}
	if m.Op == "add" || m.Op == "update" {
		if len(m.Fields) == 0 {
			http.Error(w, "Empty task fields", 400)
			return
		}
		input, _ = json.Marshal(m.Fields)
		args = append(args, "--input", "-")
	} else if len(m.Fields) > 0 {
		http.Error(w, "Unexpected task fields", 400)
		return
	}
	cmd := exec.CommandContext(r.Context(), cli, args...)
	cmd.Stdin = bytes.NewReader(input)
	result, err := cmd.Output()
	status := http.StatusOK
	if err != nil {
		failure, ok := err.(*exec.ExitError)
		if !ok || !json.Valid(result) {
			http.Error(w, "CLI unavailable", 503)
			return
		}
		switch failure.ExitCode() {
		case 2:
			status = http.StatusBadRequest
		case 3:
			status = http.StatusNotFound
		case 4:
			status = http.StatusConflict
		default:
			status = http.StatusServiceUnavailable
		}
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	w.Write(result)
}
