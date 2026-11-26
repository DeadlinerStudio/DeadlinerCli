"""Verify the timestamp contract implemented by the source-built CLI."""
import unittest

from integration import CliTestCase


class PortableTimestamps(CliTestCase):
    def test_portable_timestamp_input(self):
        original = self.add(dueAt='2026-11-23T18:30:00+08:00')
        for value in ('tomorrow', '2026-11-23T18:30:00', '2026-11-23T18:30:00+08',
                      '+012026-11-23T18:30:00Z', '2026-02-30T18:30:00Z',
                      '2026-11-23T24:00:00Z', '2026-11-23T18:30:00+25:00'):
            with self.subTest(value=value):
                error = self.runcli('task','update','one','--input','-',
                                    data={'title':'must not persist','dueAt':value},code=2)
                self.assertIn('dueAt', error['message'])
                self.assertEqual(error['code'], 'INVALID_ARGUMENT')
        self.runcli('task','add','--title','invalid','--start','2026-11-23',code=2)
        self.assertEqual(self.runcli('task','get','one'), original)
        self.assertEqual(self.runcli('task','list')['total'], 1)
        for value in ('2026-11-23T18:30:00+08:00','2026-11-23T10:30:00Z','2026-11-23T02:30:00.123456789-08:00'):
            result = self.runcli('task','update','one','--start',value,'--dry-run')
            self.assertEqual(result['task']['startAt'], value)


if __name__ == "__main__":
    unittest.main(verbosity=2)
