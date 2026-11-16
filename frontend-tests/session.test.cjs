const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const {webcrypto} = require('node:crypto');

async function app(handler) {
    const data = new Map();
    const elements = new Map();
    const calls = [];
    const context = vm.createContext({
        crypto: webcrypto, URLSearchParams, TextEncoder, Date, console,
        location: {origin: 'http://localhost:8080', search: '', href: ''},
        history: {
            replaceState() {
            }
        },
        sessionStorage: {
            getItem: k => data.get(k) || null,
            setItem: (k, v) => data.set(k, String(v)),
            removeItem: k => data.delete(k),
            clear: () => data.clear()
        },
        document: {
            getElementById: id => {
                if (!elements.has(id)) elements.set(id, {
                    value: '', textContent: '', replaceChildren() {
                    }, append() {
                    }
                });
                return elements.get(id)
            }, querySelectorAll: () => []
        },
        fetch: async (url, options) => {
            calls.push({url, options});
            if (url === '/ui-config') return {json: async () => ({oidc: 'https://identity.example.test/realms/saas/protocol/openid-connect'})};
            return handler(url, options)
        }
    });
    vm.runInContext(fs.readFileSync(require('node:path').join(__dirname, '../src/main/resources/static/app.js'), 'utf8'), context);
    await vm.runInContext('configReady', context);
    return {context, data, elements, calls};
}

test('expired token refresh is shared across simultaneous requests', async () => {
    const state = await app(async () => ({
        ok: true,
        json: async () => ({access_token: 'fresh', refresh_token: 'rotated', expires_in: 60, id_token: 'identity'})
    }));
    state.data.set('refresh_token', 'refresh');
    vm.runInContext("token='expired'", state.context);
    await vm.runInContext('Promise.all([validToken(),validToken(),validToken()])', state.context);
    assert.equal(state.calls.filter(x => x.url.endsWith('/token')).length, 1);
    assert.equal(state.data.get('access_token'), 'fresh');
    assert.equal(state.data.get('refresh_token'), 'rotated');
});
test('tenant selection is explicit on each request and writes get idempotency keys', async () => {
    const state = await app(async () => ({ok: true, json: async () => ({id: 'project'})}));
    state.data.set('expires_at', String(Date.now() + 120000));
    vm.runInContext("token='valid'", state.context);
    state.elements.get('tenants').value = 'tenant-a';
    await vm.runInContext("api('/projects','POST',{name:'A',status:'OPEN'})", state.context);
    state.elements.get('tenants').value = 'tenant-b';
    await vm.runInContext("api('/projects')", state.context);
    const requests = state.calls.filter(x => x.url.startsWith('/api/'));
    assert.equal(requests[0].options.headers['X-Tenant-ID'], 'tenant-a');
    assert.ok(requests[0].options.headers['Idempotency-Key']);
    assert.equal(requests[1].options.headers['X-Tenant-ID'], 'tenant-b');
});
test('rejected refresh clears tokens and never sends a business request', async () => {
    const state = await app(async () => ({ok: false}));
    state.data.set('refresh_token', 'revoked');
    vm.runInContext("token='expired'", state.context);
    await assert.rejects(vm.runInContext("api('/projects')", state.context), /Session expired/);
    assert.equal(state.data.size, 0);
    assert.equal(state.calls.filter(x => x.url.startsWith('/api/')).length, 0);
});
test('sign out clears local tokens and ends the configured identity-provider session', async () => {
    const state = await app(async () => ({ok: true}));
    state.data.set('id_token', 'identity-hint');
    state.data.set('access_token', 'secret');
    await state.elements.get('logout').onclick();
    assert.equal(state.data.size, 0);
    const target = new URL(state.context.location.href);
    assert.equal(target.hostname, 'identity.example.test');
    assert.ok(target.pathname.endsWith('/logout'));
    assert.equal(target.searchParams.get('id_token_hint'), 'identity-hint');
});

test('large exports stream to a chosen file without allocating a blob', async () => {
    let piped = false;
    const destination = {};
    const state = await app(async () => ({
        ok: true, headers: {get: () => 'checksum'}, body: {
            pipeTo: async stream => {
                assert.equal(stream, destination);
                piped = true
            }
        }, blob: () => {
            throw Error('Unexpected full-file buffering')
        }
    }));
    state.data.set('expires_at', String(Date.now() + 120000));
    vm.runInContext("token='valid'", state.context);
    state.elements.get('tenants').value = 'tenant-a';
    state.context.showSaveFilePicker = async () => ({createWritable: async () => destination});
    await vm.runInContext("downloadExport('export-id')", state.context);
    assert.equal(piped, true);
    const request = state.calls.find(x => x.url.endsWith('/content'));
    assert.equal(request.options.headers['X-Tenant-ID'], 'tenant-a');
    assert.equal(request.options.headers.Authorization, 'Bearer valid');
});

test('unsupported browsers cancel large responses rather than buffering them', async () => {
    let cancelled = false;
    const state = await app(async () => ({
        ok: true,
        headers: {get: () => String(33 * 1024 * 1024)},
        body: {
            cancel: async () => {
                cancelled = true
            }
        },
        blob: () => {
            throw Error('Unexpected full-file buffering')
        }
    }));
    state.data.set('expires_at', String(Date.now() + 120000));
    vm.runInContext("token='valid'", state.context);
    await assert.rejects(vm.runInContext("downloadExport('export-id')", state.context), /streaming file access/);
    assert.equal(cancelled, true);
});
