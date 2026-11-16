#!/usr/bin/env python3
"""Local orphan-grant/replay/late-proof drill; never refunds ambiguous credits."""
import base64
import json
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

root = Path(__file__).resolve().parents[1]


def login(user):
    data = urllib.parse.urlencode(
        dict(client_id='saas-ui', grant_type='password', username=user, password='demo-password')).encode()
    with urllib.request.urlopen(
            urllib.request.Request('http://localhost:8188/realms/saas/protocol/openid-connect/token',
                                   data=data)) as r: return json.load(r)['access_token']


def api(token, path, method='GET', body=None):
    data = json.dumps(body).encode() if body is not None else b'' if method == 'POST' else None
    try:
        with urllib.request.urlopen(urllib.request.Request('http://localhost:8088/api/v1' + path,
                                                           headers={'Authorization': 'Bearer ' + token,
                                                                    'Content-Type': 'application/json'}, data=data,
                                                           method=method), timeout=15) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


a, admin = login('alice'), login('platform');
subject = json.loads(base64.urlsafe_b64decode(a.split('.')[1] + '=' * (-len(a.split('.')[1]) % 4)))['sub'];
uuid.UUID(subject)
slug = 'quota-drill-' + uuid.uuid4().hex[:12];
status, data = api(a, '/tenants', 'POST', {'slug': slug, 'name': slug});
assert status == 201;
t = data['id'];
assert api(admin, '/admin/tenants/' + t + '/provision', 'POST')[0] == 200
lease = str(uuid.uuid4());
compose = ['docker', 'compose', '-f', str(root / 'docker-compose.yml'), 'exec', '-T', 'postgres', 'psql', '-X', '-qAt',
           '-v', 'ON_ERROR_STOP=1', '-U', 'saas_runtime', '-d', 'saas']


def sql(statement): return subprocess.check_output(compose, input=statement, text=True).strip()


def usage(): return json.loads(
    sql(f"BEGIN;SET LOCAL app.current_tenant='{t}';SELECT row_to_json(u) FROM usage u;COMMIT;"))


# Simulate losing the grant acknowledgment, then replay the SAME stable ID.
for _ in range(2): sql(f"BEGIN;SELECT grant_api_budget('{t}','{subject}',4,'{lease}',NULL,0);COMMIT;")
assert usage()['api'] == 4
path = '/admin/tenants/' + t + '/quota/reconciliation';
assert api(a, path, 'POST')[0] == 403
assert api(admin, path)[0] == 200
sql(f"BEGIN;SET LOCAL app.current_tenant='{t}';UPDATE api_budgets SET window_at=clock_timestamp()-interval '90 seconds' WHERE id='{lease}';COMMIT;")
assert api(admin, path, 'POST')[0] == 200;
first = usage();
assert first['api'] == 4 and first['api_confirmed'] == 1 and first['api_assumed'] == 3, first
assert api(admin, path, 'POST')[0] == 200;
assert usage() == first
for _ in range(2): sql(f"BEGIN;SELECT close_api_budget('{t}','{lease}',2);COMMIT;")
last = usage();
assert last['api'] == 2 and last['api_confirmed'] == 2 and last['api_assumed'] == 0, last
print(json.dumps({'result': 'PASS', 'tenant': t, 'duplicate_grant_reserved_once': True, 'ordinary_user_status': 403,
                  'after_conservative_settlement': {k: first[k] for k in ['api', 'api_confirmed', 'api_assumed']},
                  'after_late_proof_replayed_twice': {k: last[k] for k in ['api', 'api_confirmed', 'api_assumed']},
                  'scope': 'local simulated orphan; no automatic refund without positive unused-credit evidence'},
                 indent=2))
