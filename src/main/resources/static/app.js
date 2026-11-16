const $ = id => document.getElementById(id);
let token = sessionStorage.getItem('access_token'), selectedProject = null;
let oidc, projectPage = 0, taskPage = 0, refreshing;
const configReady = fetch('/ui-config').then(r => r.json()).then(config => {
    oidc = config.oidc
});

function storeTokens(data) {
    token = data.access_token;
    sessionStorage.setItem('access_token', token);
    sessionStorage.setItem('expires_at', String(Date.now() + data.expires_in * 1000));
    if (data.refresh_token) sessionStorage.setItem('refresh_token', data.refresh_token);
    if (data.id_token) sessionStorage.setItem('id_token', data.id_token)
}

async function validToken() {
    await configReady;
    if (!token) throw Error('Sign in to continue');
    if (Date.now() < Number(sessionStorage.getItem('expires_at') || 0) - 30000) return;
    if (!refreshing) refreshing = (async () => {
        const refresh = sessionStorage.getItem('refresh_token');
        if (!refresh) throw Error('Session expired; sign in again');
        const response = await fetch(oidc + '/token', {
            method: 'POST',
            headers: {'Content-Type': 'application/x-www-form-urlencoded'},
            body: new URLSearchParams({client_id: 'saas-ui', grant_type: 'refresh_token', refresh_token: refresh})
        });
        if (!response.ok) {
            sessionStorage.clear();
            token = null;
            throw Error('Session expired; sign in again')
        }
        storeTokens(await response.json())
    })().finally(() => {
        refreshing = null
    });
    await refreshing
}

function show(v) {
    $('result').textContent = JSON.stringify(v, null, 2)
}

async function api(path, method = 'GET', data, options = {}) {
    await validToken();
    const headers = {
        Authorization: 'Bearer ' + token,
        'Content-Type': 'application/json',
        'X-Tenant-ID': options.tenant || $('tenants').value, ...options.headers
    };
    if (method === 'POST' && (path === '/projects' || path.endsWith('/tasks') || path.endsWith('/reports'))) headers['Idempotency-Key'] = options.key || crypto.randomUUID();
    const response = await fetch('/api/v1' + path, {method, headers, body: data ? JSON.stringify(data) : undefined});
    const body = await response.json();
    if (!response.ok) throw Error(body.detail || body.title || response.status);
    return body
}

function act(f) {
    return async e => {
        e?.preventDefault();
        $('notice').textContent = '';
        try {
            await f(e)
        } catch (x) {
            $('notice').textContent = x.message
        }
    }
}

function button(label, f) {
    const b = document.createElement('button');
    b.textContent = label;
    b.onclick = act(f);
    return b
}

function text(el, value) {
    el.textContent = value
}

function fields(e) {
    return Object.fromEntries(new FormData(e.target))
}

async function login() {
    await configReady;
    const bytes = crypto.getRandomValues(new Uint8Array(32));
    const verifier = btoa(String.fromCharCode(...bytes)).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
    const challenge = btoa(String.fromCharCode(...new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))))).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
    const state = crypto.randomUUID();
    sessionStorage.setItem('pkce', verifier);
    sessionStorage.setItem('state', state);
    location.href = oidc + '/auth?' + new URLSearchParams({
        client_id: 'saas-ui',
        redirect_uri: location.origin + '/',
        response_type: 'code',
        scope: 'openid',
        state,
        code_challenge: challenge,
        code_challenge_method: 'S256'
    })
}

async function start() {
    await configReady;
    const p = new URLSearchParams(location.search);
    if (p.has('code')) {
        if (p.get('state') !== sessionStorage.getItem('state')) throw Error('Invalid login state');
        const r = await fetch(oidc + '/token', {
            method: 'POST',
            headers: {'Content-Type': 'application/x-www-form-urlencoded'},
            body: new URLSearchParams({
                client_id: 'saas-ui',
                grant_type: 'authorization_code',
                code: p.get('code'),
                redirect_uri: location.origin + '/',
                code_verifier: sessionStorage.getItem('pkce')
            })
        });
        if (!r.ok) throw Error('Login failed');
        storeTokens(await r.json());
        sessionStorage.removeItem('pkce');
        sessionStorage.removeItem('state');
        history.replaceState({}, '', '/')
    }
    if (token) {
        const payload = JSON.parse(atob(token.split('.')[1].replaceAll('-', '+').replaceAll('_', '/')));
        $('identity').textContent = payload.preferred_username || payload.sub;
        await tenants()
    }
}

async function tenants() {
    const old = $('tenants').value;
    const rows = await api('/me/tenants');
    $('tenants').replaceChildren();
    for (const t of rows) {
        const o = document.createElement('option');
        o.value = t.id;
        o.textContent = t.name + ' · ' + t.state + ' · ' + t.role;
        $('tenants').append(o)
    }
    if (rows.some(t => t.id === old)) $('tenants').value = old;
    if (rows.length) await projects()
}

async function projects() {
    const result = await api('/projects?page=' + projectPage + '&q=' + encodeURIComponent($('search').value));
    $('projectPage').textContent = 'Page ' + (projectPage + 1);
    $('previousProjects').disabled = projectPage === 0;
    $('nextProjects').disabled = result.items.length < 25;
    $('projects').replaceChildren();
    for (const p of result.items) {
        const a = document.createElement('article');
        const name = document.createElement('strong');
        name.textContent = p.name + ' · ' + p.status + ' · ' + p.tasks + ' tasks';
        a.append(name, button('Tasks', async () => {
            selectedProject = p.id;
            taskPage = 0;
            $('taskTitle').textContent = 'Tasks · ' + p.name;
            await tasks()
        }), button('Edit', async () => {
            const name = prompt('Project name', p.name);
            if (name) {
                await api('/projects/' + p.id, 'PATCH', {
                    name,
                    status: p.status
                }, {headers: {'If-Match': String(p.version)}});
                await projects()
            }
        }), button('Close', async () => {
            await api('/projects/' + p.id, 'PATCH', {
                name: p.name,
                status: 'CLOSED'
            }, {headers: {'If-Match': String(p.version)}});
            await projects()
        }), button('Delete', async () => {
            await api('/projects/' + p.id, 'DELETE');
            await projects()
        }));
        $('projects').append(a)
    }
}

async function tasks() {
    const rows = await api('/projects/' + selectedProject + '/tasks?page=' + taskPage);
    $('taskPage').textContent = 'Page ' + (taskPage + 1);
    $('previousTasks').disabled = taskPage === 0;
    $('nextTasks').disabled = rows.length < 100;
    $('tasks').replaceChildren();
    for (const t of rows) {
        const a = document.createElement('article');
        a.textContent = t.title + ' · ' + t.status;
        a.append(button('Done', async () => {
            await api('/tasks/' + t.id, 'PATCH', {title: t.title, status: 'DONE'});
            await tasks()
        }), button('Delete', async () => {
            await api('/tasks/' + t.id, 'DELETE');
            await tasks()
        }), button('Assign', async () => {
            const subject = prompt('Member OIDC subject');
            if (subject) show(await api('/tasks/' + t.id + '/assignments', 'POST', {subject}))
        }));
        $('tasks').append(a)
    }
}

async function admin() {
    const id = $('tenants').value;
    const values = await Promise.all(['subscription', 'entitlements', 'usage'].map(p => api('/tenants/' + id + '/' + p)));
    $('usage').textContent = JSON.stringify(values, null, 2);
    $('audit').textContent = JSON.stringify(await api('/tenants/' + id + '/audit'), null, 2);
    $('members').replaceChildren();
    for (const m of await api('/tenants/' + id + '/members')) {
        const a = document.createElement('article');
        a.textContent = m.subject + ' · ' + m.role + ' · active=' + m.active;
        a.append(button('Remove', async () => {
            await api('/tenants/' + id + '/members/' + encodeURIComponent(m.subject), 'DELETE');
            await admin()
        }), button('Set role', async () => {
            const role = prompt('TENANT_ADMIN, TENANT_MEMBER or TENANT_VIEWER');
            if (role) {
                await api('/tenants/' + id + '/members/' + encodeURIComponent(m.subject), 'PATCH', {
                    subject: m.subject,
                    role
                });
                await admin()
            }
        }));
        $('members').append(a)
    }
}

async function platform() {
    const rows = await api('/admin/tenants');
    $('platformTenants').replaceChildren();
    for (const t of rows) {
        const a = document.createElement('article');
        a.textContent = t.name + ' · ' + t.id + ' · ' + t.state;
        for (const [label, path] of [['Provision', '/admin/tenants/' + t.id + '/provision'], ['Suspend', '/tenants/' + t.id + '/suspend'], ['Reactivate', '/tenants/' + t.id + '/activate'], ['Export', '/admin/tenants/' + t.id + '/export']]) a.append(button(label, async () => {
            show(await api(path, 'POST'));
            await platform()
        }));
        a.append(button('Export status', async () => {
            const job = prompt('Export job UUID');
            if (job) show(await api('/admin/tenants/' + t.id + '/exports/' + job))
        }), button('Download export', async () => {
            const job = prompt('Export job UUID');
            if (job) await downloadExport(job, t.id)
        }), button('Health', async () => show(await api('/admin/tenants/' + t.id + '/health'))), button('Quota preview', async () => show(await api('/admin/tenants/' + t.id + '/quota/reconciliation'))), button('Settle ambiguous quota', async () => show(await api('/admin/tenants/' + t.id + '/quota/reconciliation', 'POST'))));
        $('platformTenants').append(a)
    }
}

$('login').onclick = act(login);
$('logout').onclick = act(async () => {
    await configReady;
    const hint = sessionStorage.getItem('id_token');
    sessionStorage.clear();
    token = null;
    location.href = oidc + '/logout?' + new URLSearchParams({
        client_id: 'saas-ui',
        post_logout_redirect_uri: location.origin + '/', ...(hint ? {id_token_hint: hint} : {})
    })
});
$('reload').onclick = act(tenants);
$('tenants').onchange = act(async () => {
    selectedProject = null;
    projectPage = 0;
    taskPage = 0;
    $('tasks').replaceChildren();
    await projects()
});
$('searchBtn').onclick = act(async () => {
    projectPage = 0;
    await projects()
});
for (const b of document.querySelectorAll('[data-tab]')) b.onclick = act(async () => {
    for (const name of ['workspace', 'tenant', 'platform']) $(name).hidden = name !== b.dataset.tab;
    if (b.dataset.tab === 'tenant') {
        await admin();
        await operations()
    }
    if (b.dataset.tab === 'platform') await platform()
});
$('project').onsubmit = act(async e => {
    await api('/projects', 'POST', {...fields(e), status: 'OPEN'});
    e.target.reset();
    await projects()
});
$('task').onsubmit = act(async e => {
    if (!selectedProject) throw Error('Select a project');
    await api('/projects/' + selectedProject + '/tasks', 'POST', {...fields(e), status: 'TODO'});
    e.target.reset();
    await tasks()
});
$('invite').onsubmit = act(async e => show(await api('/tenants/' + $('tenants').value + '/invitations', 'POST', fields(e))));
$('accept').onsubmit = act(async e => {
    show(await api('/invitations/' + fields(e).id + '/accept', 'POST', undefined, {tenant: fields(e).tenantId}));
    await tenants()
});
$('config').onsubmit = act(async e => show(await api('/tenants/' + $('tenants').value, 'PATCH', {
    name: fields(e).name,
    exportEnabled: e.target.exportEnabled.checked
})));
$('plan').onsubmit = act(async e => {
    show(await api('/tenants/' + $('tenants').value + '/subscription', 'POST', fields(e)));
    await admin()
});
$('export').onclick = act(async () => show(await api('/tenants/' + $('tenants').value + '/export', 'POST')));
$('job').onsubmit = act(async e => show(await api('/jobs/' + fields(e).id)));
$('register').onsubmit = act(async e => show(await api('/tenants', 'POST', fields(e))));
$('platformRefresh').onclick = act(platform);
act(start)();

$('previousProjects').onclick = act(async () => {
    projectPage = Math.max(0, projectPage - 1);
    await projects()
});
$('nextProjects').onclick = act(async () => {
    projectPage++;
    await projects()
});
$('previousTasks').onclick = act(async () => {
    taskPage = Math.max(0, taskPage - 1);
    await tasks()
});
$('nextTasks').onclick = act(async () => {
    taskPage++;
    await tasks()
});
$('report').onclick = act(async () => {
    show(await api('/tenants/' + $('tenants').value + '/reports', 'POST'));
    await operations()
});
$('feature').onsubmit = act(async e => {
    const b = fields(e);
    show(await api('/tenants/' + $('tenants').value + '/features/' + b.feature, 'PUT', {
        enabled: e.target.enabled.checked,
        rollout: Number(b.rollout),
        version: Number(b.version)
    }));
    await operations()
});
$('cancelSubscription').onclick = act(async () => show(await api('/tenants/' + $('tenants').value + '/subscription/cancel', 'POST')));
$('transferOwner').onclick = act(async () => {
    const subject = prompt('Existing active member OIDC subject');
    if (subject) {
        show(await api('/tenants/' + $('tenants').value + '/owner', 'POST', {subject}));
        await tenants()
    }
});

async function operations() {
    const root = '/tenants/' + $('tenants').value;
    $('featureStatus').textContent = JSON.stringify(await api(root + '/features'), null, 2);
    $('eventStatus').textContent = JSON.stringify(await api(root + '/events'), null, 2);
    $('jobList').replaceChildren();
    for (const job of await api(root + '/jobs')) {
        const row = document.createElement('article');
        row.textContent = job.kind + ' ' + job.id + ' ' + job.state + ' attempts ' + job.attempts;
        row.append(button('Inspect', async () => show(await api('/jobs/' + job.id))));
        if (job.kind === 'EXPORT' && job.state === 'DONE') row.append(button('Download', () => downloadExport(job.id)));
        if (job.state === 'FAILED') row.append(button('Retry', async () => {
            show(await api(root + '/jobs/' + job.id + '/retry', 'POST'));
            await operations()
        }));
        $('jobList').append(row)
    }
}

async function downloadExport(id, supportTenant) {
    const filename = 'export-' + id + '.json.gz';
    const handle = typeof showSaveFilePicker === 'function' ? await showSaveFilePicker({suggestedName: filename}) : null;
    await validToken();
    const path = supportTenant ? '/admin/tenants/' + supportTenant + '/exports/' + id + '/content' : '/jobs/' + id + '/content';
    const response = await fetch('/api/v1' + path, {
        headers: {
            Authorization: 'Bearer ' + token,
            'X-Tenant-ID': supportTenant || $('tenants').value
        }
    });
    if (!response.ok) {
        const error = await response.json();
        throw Error(error.detail || response.status)
    }
    if (handle) {
        const destination = await handle.createWritable();
        await response.body.pipeTo(destination)
    } else {
        const size = Number(response.headers.get('Content-Length'));
        if (!size || size > 32 * 1024 * 1024) {
            await response.body.cancel();
            throw Error('Large downloads require a browser with streaming file access, or the authenticated download API; see the runbook')
        }
        const url = URL.createObjectURL(await response.blob());
        const link = document.createElement('a');
        link.href = url;
        link.download = filename;
        link.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
    }
    show({downloaded: filename, sha256: response.headers.get('ETag')});
}
