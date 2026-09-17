#!/usr/bin/env python3
"""Foreground Quarkus dev-mode launcher for macOS/Linux; standard library only."""
import fcntl
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
RUN = ROOT / '.run'
APP = 'enterprise-ai-runway'
SERVICES = [('mcp-tools', 8092, 5007), ('policy-gateway', 8091, 5006), ('agent-runtime', 8090, 5005)]
LEGACY = ['runway-agent', 'runway-gateway', 'runway-tools']


def say(message):
    print(message, flush=True)


def podman(*args, check=True, env=None):
    result = subprocess.run(['podman', *args], capture_output=True, text=True, timeout=30, env=env)
    if check and result.returncode:
        raise RuntimeError(f'Podman {args[0]} failed: {result.stderr.strip()}')
    return result


def container(name):
    result = podman('container', 'exists', name, check=False)
    if result.returncode == 1:
        return None
    if result.returncode:
        raise RuntimeError(result.stderr.strip())
    # Inspect only ownership/state; never print container environment credentials.
    owner = podman('inspect', '--format', '{{index .Config.Labels "app"}}', name).stdout.strip()
    if owner != APP:
        raise RuntimeError(f'{name} is not labeled app={APP}; leaving it untouched.')
    return podman('inspect', '--format', '{{.State.Running}}', name).stdout.strip() == 'true'


def stop_container(name):
    if container(name):
        podman('stop', '-t', '10', name)


def acquire(lock):
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return True
    except BlockingIOError:
        return False


class StopRequested(Exception):
    pass


class Supervisor:
    def __init__(self, run_dir=RUN):
        self.run_dir = run_dir
        self.stopping = False
        self.children = []
        self.services = []
        self.readers = []

    def request_stop(self, *_):
        self.stopping = True

    def check(self):
        if self.stopping or (self.run_dir / 'dev.stop').exists():
            raise StopRequested()
        for name, process in self.services:
            if process.poll() is not None:
                raise RuntimeError(f'{name} exited (code {process.returncode}); stopping the demo.')

    def spawn(self, name, args, env=None, service=False):
        self.check()
        process = subprocess.Popen(args, cwd=ROOT, env=env, stdin=subprocess.DEVNULL,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, errors='replace', start_new_session=True)
        self.children.append(process)
        if service:
            self.services.append((name, process))

        def stream():
            with process.stdout, (self.run_dir / f'{name}.log').open('w') as log:
                for line in process.stdout:
                    log.write(line)
                    log.flush()
                    say(f'[{name}] {line.rstrip()}')

        reader = threading.Thread(target=stream, daemon=True)
        reader.start()
        self.readers.append(reader)
        return process

    def command(self, name, args, env=None):
        process = self.spawn(name, args, env)
        while process.poll() is None:
            self.check()
            time.sleep(.2)
        self.check()
        if process.returncode:
            raise RuntimeError(f'{name} failed (code {process.returncode}); see output above.')
        self.readers[-1].join(timeout=2)
        if not self.readers[-1].is_alive():
            self.children.remove(process)

    def ready(self, name, port, timeout=300):
        deadline = time.monotonic() + timeout
        # Local probes must not be sent through an HTTP proxy configured in the shell.
        http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        while time.monotonic() < deadline:
            self.check()
            try:
                with http.open(f'http://127.0.0.1:{port}/q/health/ready', timeout=2) as response:
                    if response.status == 200:
                        say(f'[{name}] Ready on http://localhost:{port}')
                        return
            except (OSError, urllib.error.URLError):
                pass
            time.sleep(.5)
        raise RuntimeError(f'{name} was not ready within {timeout} seconds; see output above.')

    def close(self):
        # Maven forks Quarkus. Signal the entire session we created, including JVM children.
        for sig in (signal.SIGTERM, signal.SIGKILL):
            for process in reversed(self.children):
                try:
                    os.killpg(process.pid, sig)
                except ProcessLookupError:
                    pass
            deadline = time.monotonic() + (10 if sig == signal.SIGTERM else 2)
            for process in self.children:
                try:
                    process.wait(timeout=max(.01, deadline - time.monotonic()))
                except subprocess.TimeoutExpired:
                    pass
            # Readers also wait for forked JVMs that inherited stdout after Maven exits.
            for reader in self.readers:
                reader.join(timeout=max(.01, deadline - time.monotonic()))
            if all(p.poll() is not None for p in self.children) and all(not r.is_alive() for r in self.readers):
                break
        self.children.clear()
        self.services.clear()
        self.readers.clear()


def database(supervisor):
    existing = container('runway-db')
    if existing is not None:
        bindings = json.loads(podman('inspect', '--format', '{{json .HostConfig.PortBindings}}', 'runway-db').stdout)
        ports = (bindings or {}).get('5432/tcp') or []
        if not ports or any(p['HostIp'] != '127.0.0.1' for p in ports):
            # Migrate the original container-only launcher without deleting its named volume.
            mounts = json.loads(podman('inspect', '--format', '{{json .Mounts}}', 'runway-db').stdout)
            if not any(m.get('Name') == 'runway-data' and m['Destination'] == '/var/lib/postgresql/data' for m in mounts):
                raise RuntimeError('runway-db does not use runway-data; refusing to recreate it.')
            stop_container('runway-db')
            podman('rm', 'runway-db')
            existing = None
    if existing is None:
        volume = podman('volume', 'exists', 'runway-data', check=False)
        if volume.returncode == 1:
            podman('volume', 'create', 'runway-data')
        elif volume.returncode:
            raise RuntimeError(volume.stderr.strip())
        env = dict(os.environ, POSTGRES_PASSWORD=os.environ['DB_PASSWORD'])
        supervisor.command('database', [
            'podman', 'run', '-d', '--name', 'runway-db', '--label', f'app={APP}',
            '-v', 'runway-data:/var/lib/postgresql/data', '-p', '127.0.0.1::5432',
            '-e', 'POSTGRES_USER=runway', '-e', 'POSTGRES_PASSWORD', '-e', 'POSTGRES_DB=runway',
            '--memory=512m', 'docker.io/library/postgres:17.11'], env)
    else:
        podman('start', 'runway-db')
    for _ in range(60):
        supervisor.check()
        if podman('exec', 'runway-db', 'pg_isready', '-U', 'runway', '-d', 'runway', check=False).returncode == 0:
            port = podman('port', 'runway-db', '5432/tcp').stdout.strip().split(':')[-1]
            return f'jdbc:postgresql://127.0.0.1:{int(port)}/runway'
        time.sleep(1)
    raise RuntimeError('PostgreSQL did not become ready within 60 seconds.')


def up(lock):
    if not acquire(lock):
        raise RuntimeError('The demo is already running. Use ./demo.sh status or ./demo.sh down.')
    (RUN / 'dev.stop').unlink(missing_ok=True)
    supervisor = Supervisor()
    for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
        signal.signal(sig, supervisor.request_stop)
    db_started = False
    try:
        podman('info')
        for name in LEGACY:
            stop_container(name)
        for _, port, debug in SERVICES:
            for available_port in (port, debug):
                with socket.socket() as probe:
                    probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                    try:
                        probe.bind(('127.0.0.1', available_port))
                    except OSError as error:
                        raise RuntimeError(f'Port {available_port} is in use; stop its owner before starting the demo.') from error
        say('Starting Quarkus Dev Mode. Logs stream here; Ctrl+C or ./demo.sh down stops the demo.')
        db_started = True
        db_url = database(supervisor)
        # Install just the shared library/parent, so module launches also work on a fresh checkout.
        build_env = dict(os.environ)
        for key in ('OPENAI_API_KEY', 'LLM_API_KEY', 'DB_PASSWORD', 'DEMO_API_KEY',
                    'GATEWAY_READ_KEY', 'GATEWAY_WRITE_KEY', 'BACKEND_KEY'):
            build_env.pop(key, None)
        supervisor.command('shared', ['./mvnw', '-B', '-pl', 'shared', '-am', 'install', '-DskipTests'], build_env)
        for name, port, debug in SERVICES:
            env = dict(build_env, DB_URL=db_url, DB_USER='runway', DB_PASSWORD=os.environ['DB_PASSWORD'],
                       MCP_BACKEND_URL=os.environ.get('MCP_BACKEND_URL', 'http://127.0.0.1:8092'),
                       MCP_GATEWAY_URL=os.environ.get('MCP_GATEWAY_URL', 'http://127.0.0.1:8091'))
            keys = {'mcp-tools': ['BACKEND_KEY'],
                    'policy-gateway': ['BACKEND_KEY', 'GATEWAY_READ_KEY', 'GATEWAY_WRITE_KEY'],
                    'agent-runtime': ['DEMO_API_KEY', 'GATEWAY_READ_KEY', 'GATEWAY_WRITE_KEY',
                                      'OPENAI_API_KEY', 'LLM_API_KEY']}[name]
            for key in keys:
                if key in os.environ:
                    env[key] = os.environ[key]
            supervisor.spawn(name, [
                './mvnw', '-B', '-f', f'{name}/pom.xml', 'quarkus:dev', f'-Ddebug={debug}',
                '-Dquarkus.http.host=127.0.0.1', f'-Dquarkus.http.port={port}',
                '-Dquarkus.console.enabled=false', '-Dquarkus.console.color=false',
                '-Dquarkus.test.continuous-testing=disabled'], env, service=True)
            supervisor.ready(name, port)
        say('\nDemo ready: http://localhost:8090\nDev UI: http://localhost:8090/q/dev-ui\n'
            'Logs continue below. Run ./demo.sh smoke in another terminal for an optional flow check.\n')
        while True:
            supervisor.check()
            time.sleep(.5)
    except StopRequested:
        say('\nStopping the demo...')
    finally:
        supervisor.close()
        if db_started:
            stop_container('runway-db')
        (RUN / 'dev.stop').unlink(missing_ok=True)
        say('Demo stopped. Database volume and history retained.')


def down(lock):
    if not acquire(lock):
        (RUN / 'dev.stop').touch()
        say('Waiting for Quarkus Dev Mode to stop...')
        deadline = time.monotonic() + 60
        while not acquire(lock):
            if time.monotonic() >= deadline:
                raise RuntimeError('Shutdown is still in progress; check the ./demo.sh up terminal.')
            time.sleep(.2)
    # Also supports stopping the four containers from the old launcher.
    for name in [*LEGACY, 'runway-db']:
        stop_container(name)
    (RUN / 'dev.stop').unlink(missing_ok=True)
    say('Demo stopped. Database volume and history retained.')


def status(lock):
    say('Dev launcher: ' + ('stopped' if acquire(lock) else 'running'))
    for name, port, _ in SERVICES:
        try:
            with socket.create_connection(('127.0.0.1', port), timeout=1):
                say(f'{name}: port {port} listening')
        except OSError:
            say(f'{name}: port {port} closed')
    say(podman('ps', '-a', '--filter', f'label=app={APP}', '--format', '{{.Names}}\t{{.Status}}\t{{.Ports}}').stdout.rstrip())


def main():
    os.umask(0o077)
    RUN.mkdir(exist_ok=True)
    try:
        with (RUN / 'dev.lock').open('a') as lock:
            {'up': up, 'down': down, 'status': status}[sys.argv[1]](lock)
        return 0
    except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
        say(f'Error: {error}')
        return 1


if __name__ == '__main__':
    sys.exit(main())
