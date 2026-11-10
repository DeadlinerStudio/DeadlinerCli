#!/usr/bin/env python3
"""Real CLI/Core SQLite integration and isolated conditional WebDAV HTTP tests."""
import base64
import fcntl
import json
import os
import pty
import select
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BIN = Path(os.environ.get('DEADLINER_TEST_BINARY', Path(__file__).resolve().parents[1] / 'bin/deadliner'))

class WebDAV(BaseHTTPRequestHandler):
    blobs = {}
    revision = 0
    conflicts = 0
    fail_write = False
    lock = threading.Lock()
    def log_message(self, *args): pass
    def reply(self, status, body=b'', etag=None):
        self.send_response(status)
        if etag: self.send_header('ETag', etag)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        if self.command != 'HEAD': self.wfile.write(body)
    def authorized(self):
        if self.headers.get('Authorization') == 'Basic ' + base64.b64encode(b'user:secret').decode(): return True
        self.reply(401)
        return False
    def do_HEAD(self): self.do_GET()
    def do_GET(self):
        if not self.authorized(): return
        with self.lock:
            value = self.blobs.get(self.path)
            self.reply(200, *value) if value else self.reply(404)
    def do_PROPFIND(self):
        if self.authorized(): self.reply(207)
    def do_MKCOL(self):
        if self.authorized(): self.reply(201)
    def do_PUT(self):
        payload = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        if not self.authorized(): return
        with self.lock:
            cls = type(self)
            if cls.fail_write: return self.reply(503)
            if cls.conflicts:
                cls.conflicts -= 1
                return self.reply(412)
            prior = self.blobs.get(self.path)
            if self.headers.get('If-None-Match') == '*' and prior: return self.reply(412)
            if self.headers.get('If-Match') and (not prior or prior[1] != self.headers['If-Match']): return self.reply(412)
            cls.revision += 1
            etag = f'"{cls.revision}"'
            self.blobs[self.path] = (payload, etag)
            self.reply(201, etag=etag)

class Integration(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(('127.0.0.1', 0), WebDAV)
        cls.worker = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.worker.start()
    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown(); cls.server.server_close(); cls.worker.join()
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.a = self.tmp.name + '/a'; self.b = self.tmp.name + '/b'
        with WebDAV.lock:
            WebDAV.blobs.clear(); WebDAV.conflicts = 0; WebDAV.fail_write = False
    def runcli(self, *args, data=None, code=0, profile=None, password='secret'):
        env = {k:v for k,v in os.environ.items() if not k.startswith('DEADLINER_')}
        env.update(DEADLINER_WEBDAV_URL=f'http://127.0.0.1:{self.server.server_port}/test',
                   DEADLINER_WEBDAV_USERNAME='user', DEADLINER_WEBDAV_PASSWORD=password)
        p = subprocess.run([str(BIN), '--data-dir', profile or self.a, *args],
                           input=json.dumps(data) if data is not None else '', text=True,
                           capture_output=True, env=env, timeout=45)
        self.assertEqual(p.returncode, code, (p.stdout, p.stderr))
        result = json.loads(p.stdout)  # Fails if any Core diagnostics contaminate stdout.
        self.assertEqual(result['ok'], code == 0)
        return result.get('data', result.get('error'))
    def add(self, uid='one', **kw):
        return self.runcli('task','add','--input','-',data={'uid':uid,'title':'任务一',**kw})['task']
    def test_crud_guards_dates_and_dry_run(self):
        original = self.add(dueAt='2026-11-10T09:00:00+08:00', note='原注释')
        self.assertEqual(self.runcli('task','get','one')['title'], '任务一')
        self.runcli('task','update','one','--input','-','--if-updated-at','stale',data={'title':'错误'},code=4)
        self.runcli('task','update','one','--input','-','--dry-run',data={'note':'dry'})
        self.assertEqual(self.runcli('task','get','one')['note'], '原注释')
        self.runcli('task','update','one','--input','-','--if-updated-at',original['updatedAt'],data={'note':'','dueAt':None})
        updated = self.runcli('task','get','one')
        self.assertEqual(updated['note'], ''); self.assertIsNone(updated['dueAt'])
        for payload in [{'title':''},{'dueAt':'tomorrow'},{'state':'completed'},{'isStarred':'true'},{'title':None}]:
            self.runcli('task','update','one','--input','-',data=payload,code=2)
        self.runcli('task','delete','one','--dry-run')
        self.runcli('task','get','one')
        self.runcli('task','delete','one')
        self.runcli('task','get','one',code=3)
        self.assertEqual(self.runcli('task','list')['total'], 0)
    def test_idempotency_states_pagination(self):
        self.add(); self.assertEqual(self.runcli('task','add','--input','-',data={'uid':'one','title':'任务一'})['outcome'],'NO_OP')
        self.runcli('task','add','--input','-',data={'uid':'one','title':'changed'},code=4)
        self.runcli('task','archive','one',code=4)
        self.runcli('task','complete','one'); self.runcli('task','complete','one')
        self.assertEqual(self.runcli('task','get','one')['state'],'completed')
        self.runcli('task','archive','one'); self.runcli('task','unarchive','one'); self.runcli('task','reopen','one')
        self.runcli('task','abandon','one'); self.runcli('task','abandon','one')
        self.assertEqual(self.runcli('task','get','one')['state'],'abandoned')
        self.add('two')
        page = self.runcli('task','list','--query','任务','--limit','1','--offset','1')
        self.assertEqual(page['total'],2); self.assertEqual(len(page['items']),1)
        self.assertEqual(self.runcli('task','list','--state','active')['total'],1)
        self.runcli('task','list','--limit','-1',code=2)
        self.runcli('task','get','one','--bogus','yes',code=2)
    def test_profile_lock(self):
        self.add()
        with open(self.a + '/cli.lock','w') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.runcli('task','list',code=4)
        self.runcli('task','list')
    def test_webdav_two_devices_tombstones_and_retry(self):
        self.add()
        self.runcli('sync','run')
        self.runcli('sync','run',profile=self.b)
        self.assertEqual(self.runcli('task','get','one',profile=self.b)['title'],'任务一')
        self.runcli('task','update','one','--input','-',data={'title':'remote edit'},profile=self.b)
        WebDAV.conflicts = 1
        self.runcli('sync','run',profile=self.b)
        self.assertEqual(WebDAV.conflicts,0)
        self.runcli('sync','run')
        self.assertEqual(self.runcli('task','get','one')['title'],'remote edit')
        self.runcli('task','delete','one',profile=self.b)
        self.runcli('sync','run',profile=self.b); self.runcli('sync','run')
        self.runcli('task','get','one',code=3)
    def test_auth_failure_and_failed_upload_recovery(self):
        self.add()
        error = self.runcli('sync','run',password='wrong-secret',code=5)
        self.assertNotIn('wrong-secret',str(error))
        WebDAV.fail_write = True
        self.runcli('sync','run',code=5)
        self.runcli('task','get','one')
        WebDAV.fail_write = False
        self.runcli('sync','run'); self.runcli('sync','run',profile=self.b)
        self.runcli('task','get','one',profile=self.b)
    def human(self, *args, tty=False, code=0):
        command = [str(BIN), '--data-dir', self.a, *args]
        if tty:
            master, slave = pty.openpty()
            chunks = []
            finished = threading.Event()
            def drain():
                while True:
                    if select.select([master], [], [], 0.1)[0]:
                        try:
                            chunk = os.read(master, 65536)
                            if not chunk: break
                            chunks.append(chunk)
                        except OSError: break
                    elif finished.is_set(): break
            reader = threading.Thread(target=drain)
            reader.start()
            try:
                p = subprocess.run(command, stdin=subprocess.DEVNULL, stdout=slave,
                                   stderr=subprocess.PIPE, timeout=20)
            finally:
                finished.set()
                reader.join(timeout=2)
                os.close(slave)
                os.close(master)
            output = b''.join(chunks).decode()
            error = p.stderr.decode()
        else:
            p = subprocess.run(command, input='', text=True, capture_output=True, timeout=20)
            output, error = p.stdout, p.stderr
        self.assertEqual(p.returncode, code, (output, error))
        return output, error

    def test_human_output_and_tty_selection(self):
        self.runcli('task', 'add', '--uid', 'human', '--title', '周报', '--note', '附上数据',
                    '--due', '2026-11-11T18:00:00+08:00')
        output, error = self.human('task', 'list', tty=True)
        self.assertIn('[待办] 周报', output)
        self.assertIn('ID：human', output)
        self.assertIn('时间显示：', output)
        self.assertNotIn('"ok":', output)
        self.assertEqual(error, '')
        output, _ = self.human('--json', 'task', 'get', 'human', tty=True)
        self.assertEqual(json.loads(output)['data']['note'], '附上数据')
        output, _ = self.human('--human', 'task', 'complete', 'human', '--dry-run')
        self.assertIn('未保存', output)
        self.assertEqual(self.runcli('task', 'get', 'human')['state'], 'active')
        self.runcli('task', 'update', 'human', '--clear-due', '--note', '')
        self.assertIsNone(self.runcli('task', 'get', 'human')['dueAt'])
        self.runcli('task', 'update', 'human', '--input', '-', '--title', 'bad', data={'note':'x'}, code=2)
        self.runcli('task', 'update', 'human', '--due', '2026-11-11T00:00:00Z', '--clear-due', code=2)
        self.runcli('--human', '--json', 'task', 'list', code=2)
        output, error = self.human('--human', 'task', 'get', 'missing', code=3)
        self.assertEqual(output, '')
        self.assertIn('找不到这个任务', error)
        help_text, _ = self.human('--human', '-h')
        self.assertIn('快速开始', help_text)
        self.assertIn('--title', help_text)
        output, _ = self.human('schema', tty=True)
        self.assertEqual(json.loads(output)['data']['protocolVersion'], 1)

    def test_terminal_text_sanitization(self):
        self.add(title='标题\x1b[2J\n伪造行\u202e')
        output, _ = self.human('--human', 'task', 'list')
        self.assertNotIn('\x1b', output)
        self.assertNotIn('\u202e', output)
        self.assertIn(' ↵ ', output)

    def test_discovery(self):
        self.assertEqual(self.runcli('schema')['protocolVersion'],1)
        self.runcli('version'); self.runcli('unknown',code=2)

if __name__ == '__main__': unittest.main(verbosity=2)
