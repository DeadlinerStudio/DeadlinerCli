"""Exercise the shipped entry point with a private profile and the real CLI writer."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen

BIN = Path(os.environ.get("DEADLINER_TEST_BINARY", Path(__file__).resolve().parents[1] / "bin/deadliner"))

class ServeTest(unittest.TestCase):
    def test_readonly_live_snapshot(self):
        with tempfile.TemporaryDirectory() as directory:
            cli = [str(BIN), "--json", "--data-dir", directory]
            def mutate(*args):
                value = json.loads(subprocess.check_output(cli + list(args)))
                self.assertTrue(value["ok"])
            mutate("task", "list")
            with subprocess.Popen(cli + ["serve"], stdout=subprocess.PIPE, text=True) as service:
                try:
                    port, token = json.loads(service.stdout.readline())["data"]["connectionCode"].split(".")
                    url = f"http://127.0.0.1:{port}/v1/tasks"
                    headers = {"Authorization": "Bearer " + token}
                    def snapshot():
                        with urlopen(Request(url, headers=headers), timeout=5) as response:
                            value = json.load(response)
                            self.assertEqual(value["version"], 1)
                            return value["items"], response.headers["ETag"]
                    self.assertEqual(snapshot()[0], [])
                    for request, status in [(Request(url), 401), (Request(url, headers=headers, method="POST"), 405)]:
                        with self.assertRaises(HTTPError) as error: urlopen(request, timeout=5)
                        self.assertEqual(error.exception.code, status)
                    mutate("task", "add", "--uid", "fixture", "--title", "Demo")
                    rows, revision = snapshot()
                    self.assertEqual([row["title"] for row in rows], ["Demo"])
                    with self.assertRaises(HTTPError) as same:
                        urlopen(Request(url, headers={**headers, "If-None-Match": revision}), timeout=5)
                    self.assertEqual(same.exception.code, 304)
                    mutate("task", "update", "fixture", "--note", "Updated")
                    rows, new_revision = snapshot()
                    self.assertNotEqual(revision, new_revision)
                    self.assertEqual(rows[0]["note"], "Updated")
                    mutate("task", "complete", "fixture")
                    self.assertEqual(snapshot()[0][0]["state"], "completed")
                    mutate("task", "delete", "fixture")
                    self.assertEqual(snapshot()[0], [])
                finally:
                    service.terminate()
                    service.wait(timeout=10)

if __name__ == "__main__":
    unittest.main()
