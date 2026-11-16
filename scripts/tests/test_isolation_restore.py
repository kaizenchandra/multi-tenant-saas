import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('isolation', Path(__file__).parents[1] / 'isolated-performance.py')
isolation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(isolation)


class RestoreTests(unittest.TestCase):
    def state(self, directory):
        path = Path(directory) / 'state.json'
        path.write_text(json.dumps({'paused': [{'id': 'ours', 'name': 'recorded'}],
                                    'resumed_ids': ['previous'], 'window_closed': False}))
        return path

    def result(self, stdout='', stderr='', code=0):
        return subprocess.CompletedProcess([], code, stdout, stderr)

    def test_success_requires_state_recheck_and_keeps_previous_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.state(directory)
            responses = [self.result('{"Paused":true,"Running":true}'), self.result(),
                         self.result('{"Paused":false,"Running":true}')]
            with patch.object(isolation.subprocess, 'run', side_effect=responses) as run:
                isolation.resume(path)
            result = json.loads(path.read_text())
            self.assertTrue(result['window_closed'])
            self.assertEqual(['ours', 'previous'], result['resumed_ids'])
            self.assertEqual(3, run.call_count)
            self.assertEqual('running', result['restore_checks'][0]['status'])

    def test_daemon_error_retries_and_does_not_claim_window_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.state(directory)
            with patch.object(isolation.subprocess, 'run',
                              return_value=self.result(stderr='daemon unavailable', code=1)) as run, patch.object(
                isolation.time, 'sleep'):
                with self.assertRaises(RuntimeError): isolation.resume(path)
            result = json.loads(path.read_text())
            self.assertFalse(result['window_closed'])
            self.assertEqual(3, run.call_count)
            self.assertEqual('unverified', result['restore_checks'][0]['status'])
            self.assertEqual('daemon unavailable', result['restore_errors'][0]['error'])

    def test_removed_container_is_recorded_without_recreation(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.state(directory)
            with patch.object(isolation.subprocess, 'run',
                              return_value=self.result(stderr='error: no such object: ours', code=1)) as run:
                isolation.resume(path)
            result = json.loads(path.read_text())
            self.assertTrue(result['window_closed'])
            self.assertEqual('removed', result['restore_checks'][0]['status'])
            self.assertEqual(1, run.call_count)


if __name__ == '__main__': unittest.main()
