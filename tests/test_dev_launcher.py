"""Exercise launcher process ownership and shutdown without Java, Podman or model calls."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('dev', Path(__file__).resolve().parents[1] / 'scripts/dev.py')
dev = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dev)


class LauncherTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.run = Path(self.temp.name)
        self.supervisor = dev.Supervisor(self.run)

    def tearDown(self):
        self.supervisor.close()
        self.temp.cleanup()

    def wait_for(self, path):
        deadline = time.monotonic() + 5
        while not path.exists():
            if time.monotonic() > deadline:
                self.fail(f'Timed out waiting for {path.name}')
            time.sleep(.02)

    def test_shutdown_reaches_forked_child_and_preserves_unrelated_process(self):
        ready = self.run / 'ready'
        stopped = self.run / 'stopped'
        child = (
            'import signal, time; from pathlib import Path; '
            f'signal.signal(signal.SIGTERM, lambda *_: (Path({str(stopped)!r}).touch(), exit(0))); '
            f'Path({str(ready)!r}).touch(); time.sleep(120)'
        )
        parent = f'import subprocess, sys; subprocess.Popen([sys.executable, "-c", {child!r}]).wait()'
        unrelated = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(120)'], start_new_session=True)
        try:
            process = self.supervisor.spawn('forking-service', [sys.executable, '-c', parent], service=True)
            self.wait_for(ready)
            self.supervisor.close()
            self.assertIsNotNone(process.poll())
            self.wait_for(stopped)
            self.assertIsNone(unrelated.poll())
        finally:
            unrelated.terminate()
            unrelated.wait(timeout=5)

    def test_service_exit_fails_supervision_and_keeps_diagnostic_log(self):
        process = self.supervisor.spawn('broken', [sys.executable, '-c', 'print("startup failed"); exit(7)'], service=True)
        process.wait(timeout=5)
        with self.assertRaisesRegex(RuntimeError, 'broken exited .*7'):
            self.supervisor.check()
        self.supervisor.close()
        self.assertIn('startup failed', (self.run / 'broken.log').read_text())

    def test_stop_request_prevents_launching_another_service(self):
        (self.run / 'dev.stop').touch()
        with self.assertRaises(dev.StopRequested):
            self.supervisor.spawn('must-not-start', [sys.executable, '-c', 'exit(99)'])
        self.assertEqual([], self.supervisor.children)

    def test_down_requests_owner_shutdown_without_signaling_stored_pids(self):
        ready = self.run / 'ready'
        owner = subprocess.Popen([sys.executable, '-c', '\n'.join([
            'import fcntl, time', 'from pathlib import Path', f'root = Path({str(self.run)!r})',
            'with (root / "dev.lock").open("a") as lock:',
            '    fcntl.flock(lock, fcntl.LOCK_EX)', '    (root / "ready").touch()',
            '    while not (root / "dev.stop").exists(): time.sleep(.02)',
        ])])
        try:
            self.wait_for(ready)
            with patch.object(dev, 'RUN', self.run), patch.object(dev, 'stop_container') as stop:
                with (self.run / 'dev.lock').open('a') as lock:
                    self.assertFalse(dev.acquire(lock))
                    dev.down(lock)
                self.assertEqual([*dev.LEGACY, 'runway-db'], [call.args[0] for call in stop.call_args_list])
            owner.wait(timeout=5)
            self.assertFalse((self.run / 'dev.stop').exists())
        finally:
            if owner.poll() is None:
                owner.terminate()
                owner.wait(timeout=5)

    def test_foreign_container_with_demo_name_is_never_stopped(self):
        def podman(*args, **_):
            return subprocess.CompletedProcess(args, 0, 'another-project\n', '')
        with patch.object(dev, 'podman', side_effect=podman) as commands:
            with self.assertRaisesRegex(RuntimeError, 'leaving it untouched'):
                dev.stop_container('runway-db')
        self.assertFalse(any(call.args[0] == 'stop' for call in commands.call_args_list))


if __name__ == '__main__':
    unittest.main()
