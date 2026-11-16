#!/usr/bin/env python3
"""Explicitly user-authorized bounded pause of unrelated local Compose projects.
Resumes only recorded containers this script paused; independent watchdog caps the window.
"""
import json
import os
import subprocess
import sys
import time
from pathlib import Path

root = Path(__file__).resolve().parents[1]
statefile = root / 'benchmarks/isolation-window.json'


def read_state(path):
    for _ in range(20):
        try:
            return json.loads(path.read_text())
        except json.JSONDecodeError:
            time.sleep(.05)
    raise RuntimeError('Cannot read isolation state')


def write_state(path, state):
    temporary = path.with_name('.' + path.name + '.' + str(os.getpid()) + '.tmp')
    temporary.write_text(json.dumps(state, indent=2) + '\n');
    temporary.chmod(0o600);
    os.replace(temporary, path)


def resume(path):
    state = read_state(path)
    resumed = set(state.get('resumed_ids', []));
    checks = [];
    errors = []
    for row in state['paused']:
        status = None;
        last_error = None
        for attempt in range(3):
            try:
                check = subprocess.run(['docker', 'inspect', '--format', '{{json .State}}', row['id']],
                                       capture_output=True, text=True, timeout=15)
                if check.returncode:
                    if 'no such object' in check.stderr.lower() or 'no such container' in check.stderr.lower():
                        status = 'removed';
                        break
                    raise RuntimeError(check.stderr.strip() or 'docker inspect failed')
                current = json.loads(check.stdout)
                if not current['Paused']:
                    status = 'running' if current['Running'] else 'stopped';
                    break
                result = subprocess.run(['docker', 'unpause', row['id']], capture_output=True, text=True, timeout=15)
                if result.returncode:
                    raise RuntimeError(result.stderr.strip() or 'docker unpause failed')
                resumed.add(row['id'])
                # A successful CLI response is insufficient: verify the actual state.
                check = subprocess.run(['docker', 'inspect', '--format', '{{json .State}}', row['id']],
                                       capture_output=True, text=True, timeout=15)
                if check.returncode: raise RuntimeError(check.stderr.strip() or 'restore verification failed')
                current = json.loads(check.stdout)
                if current['Paused']: raise RuntimeError('Container remains paused after unpause')
                status = 'running' if current['Running'] else 'stopped';
                break
            except (RuntimeError, ValueError, subprocess.TimeoutExpired) as error:
                last_error = str(error)
                if attempt < 2: time.sleep(1)
        checks.append({**row, 'status': status or 'unverified'})
        if status is None: errors.append({'id': row['id'], 'error': last_error})
    state = read_state(path)
    state['resumed_ids'] = sorted(set(state.get('resumed_ids', [])) | resumed)
    state['restore_checks'] = checks;
    state['restore_errors'] = errors
    state['resumed_unix'] = time.time();
    state['window_closed'] = not errors
    write_state(path, state)
    if errors: raise RuntimeError('Container restoration remains unverified; see restore_errors')


def main():
    if len(sys.argv) > 1 and sys.argv[1] == '--watchdog':
        path = Path(sys.argv[2]);
        state = read_state(path)
        assert 0 <= state['deadline_unix'] - state['started_unix'] <= 900
        while time.time() < state['deadline_unix']:
            if read_state(path).get('window_closed'): raise SystemExit(0)
            time.sleep(min(30, max(.1, state['deadline_unix'] - time.time())))
        resume(path);
        raise SystemExit(0)

    assert not statefile.exists() or json.loads(statefile.read_text()).get(
        'window_closed'), 'A window is already active'
    if statefile.exists(): raise SystemExit('Preserve the prior window artifact before starting another')
    ids = subprocess.check_output(['docker', 'ps', '-q'], text=True).split()
    items = json.loads(subprocess.check_output(['docker', 'inspect', *ids]))
    selected = []
    for item in items:
        labels = item['Config'].get('Labels') or {};
        project = labels.get('com.docker.compose.project')
        # Images can inherit Compose labels when launched by Kubernetes. Let
        # controllers manage their pods; do not select them as Compose services.
        if 'io.kubernetes.pod.namespace' in labels: continue
        if project and project != 'multi-tenant-saas' and item['State']['Running'] and not item['State']['Paused']:
            selected.append({'id': item['Id'], 'name': item['Name'].lstrip('/'), 'project': project})
    state = {'started_unix': time.time(), 'deadline_unix': time.time() + 900, 'paused': [],
             'authorization': 'User approved pausing other project containers for a bounded benchmark window',
             'window_closed': False, 'trials': []}
    write_state(statefile, state);
    statefile.chmod(0o600)
    watchdog = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), '--watchdog', str(statefile)],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    try:
        for item in selected:
            subprocess.run(['docker', 'pause', item['id']], check=True, stdout=subprocess.DEVNULL)
            state['paused'].append(item);
            write_state(statefile, state)
        print('Paused', len(selected), 'unrelated containers; watchdog deadline', state['deadline_unix'], flush=True)
        trials = [
            ('isolated-base-mixed', 'perf-baseline', 'mixed', []),
            ('isolated-fixed-mixed', 'perf-guarded', 'mixed', []),
            ('isolated-wide-mixed', 'perf-guarded', 'mixed', ['--api-cpus', '3', '--pool-size', '48']),
            ('isolated-wide-noisy', 'perf-guarded', 'noisy', ['--api-cpus', '3', '--pool-size', '48']),
        ]
        for label, image, mode, extra in trials:
            if time.time() > state['deadline_unix'] - 180: break
            command = [sys.executable, str(root / 'scripts/repeatable-performance.py'), '--image',
                       'multi-tenant-saas-app:' + image, '--label', label, '--mode', mode, '--repeats', '2',
                       '--duration', '20', *extra]
            with (root / 'benchmarks' / f'{label}-driver-output.txt').open('w') as output:
                child = subprocess.Popen(command, cwd=root, stdout=output, stderr=subprocess.STDOUT)
                while child.poll() is None:
                    if time.time() > state['deadline_unix'] - 45:
                        child.send_signal(2);
                        child.wait(timeout=40);
                        break
                    time.sleep(1)
            state['trials'].append({'label': label, 'exit_code': child.returncode})
            write_state(statefile, state)
            print(label, 'exit', child.returncode, flush=True)
    finally:
        resume(statefile)
        print('Other project containers resumed', flush=True)
    raise SystemExit(1 if any(trial['exit_code'] for trial in state['trials']) else 0)


if __name__ == "__main__":
    main()
