import http from 'k6/http';
import exec from 'k6/execution';
import {SharedArray} from 'k6/data';
import {Counter, Rate, Trend} from 'k6/metrics';

const tenants = new SharedArray('tenants', () => JSON.parse(open(__ENV.TENANTS_FILE)).tenants);
const credentials = new SharedArray('credentials', () => JSON.parse(open(__ENV.TENANTS_FILE)).credentials);
const mode = __ENV.MODE || 'mixed';
const duration = __ENV.DURATION || '20s';
const rps = Number(__ENV.RPS || 10000), seconds = parseInt(duration, 10);
const selector = new Counter('selector_rejections'), preauth = new Counter('preauth_rejections');
const admission = new Counter('admission_rejections'), quota = new Counter('quota_rejections'),
    principal = new Counter('principal_rejections');
const offered = new Counter('cohort_requests'), attemptedWrites = new Counter('write_attempts'),
    unexpected = new Counter('unexpected_responses');
const success = new Counter('business_success'), writes = new Counter('write_success'),
    throttled = new Counter('throttled'), failed = new Rate('business_failed'),
    latency = new Trend('business_latency', true), leaks = new Counter('isolation_violations');
const scenarios = mode === 'noisy' ? {
    hot: {
        executor: 'constant-arrival-rate',
        rate: Math.floor(rps * .9),
        timeUnit: '1s',
        duration,
        preAllocatedVUs: 512,
        maxVUs: 768,
        exec: 'hot'
    },
    neighbor: {
        executor: 'constant-arrival-rate',
        rate: rps - Math.floor(rps * .9),
        timeUnit: '1s',
        duration,
        preAllocatedVUs: 128,
        maxVUs: 512,
        exec: 'neighbor'
    }
} : {
    mixed: {
        executor: 'constant-arrival-rate',
        rate: rps,
        timeUnit: '1s',
        duration,
        preAllocatedVUs: 512,
        maxVUs: 1024,
        exec: 'mixed'
    }
};
const cohort = mode === 'noisy' ? 'neighbor' : 'normal';
const requestThresholds = mode === 'noisy' ? {
    'cohort_requests{cohort:hot}': ['count>=' + Math.floor(rps * .9 * seconds * .999)],
    'cohort_requests{cohort:neighbor}': ['count>=' + Math.floor(rps * .1 * seconds * .999)]
} : {'cohort_requests{cohort:normal}': ['count>=' + Math.floor(rps * seconds * .999)]};
export const options = {
    scenarios, systemTags: ['status', 'method', 'scenario', 'name', 'expected_response'], thresholds: {
        ...requestThresholds,
        ['business_failed{cohort:' + cohort + '}']: ['rate<0.01'],
        ['business_latency{cohort:' + cohort + '}']: ['p(99)<200'],
        dropped_iterations: ['count<' + Math.max(1, rps * seconds * .001)], isolation_violations: ['count==0']
    }
};

function request(t, index, cohort) {
    offered.add(1, {cohort});
    const write = index % 10 === 0;
    if (write) attemptedWrites.add(1, {cohort});
    const detail = !write && index % 10 === 1;
    const path = write || detail ? '/api/v1/projects/' + t.project : '/api/v1/projects';
    const tags = {
        cohort,
        op: write ? 'write' : 'read',
        name: write ? 'PATCH /projects/:id' : detail ? 'GET /projects/:id' : 'GET /projects'
    };
    const params = {
        headers: {
            Authorization: 'Bearer ' + credentials[t.credential],
            'X-Tenant-ID': t.tenant,
            'Content-Type': 'application/json'
        }, tags, timeout: '3s'
    };
    const response = write ? http.patch(__ENV.BASE_URL + path, JSON.stringify({
        name: t.name,
        status: index % 20 === 0 ? 'ACTIVE' : 'OPEN'
    }), params) : http.get(__ENV.BASE_URL + path, params);
    let violation = false;
    if (response.status === 200) {
        let data;
        try {
            data = response.json();
        } catch (e) {
            violation = true;
        }
        if (data) {
            if (detail || write) violation = data.id !== t.project; else violation = !Array.isArray(data.items) || data.items.some(p => p.id !== t.project);
        }
    }
    leaks.add(violation ? 1 : 0);
    failed.add(response.status !== 200 || violation, {cohort, op: tags.op});
    latency.add(response.timings.duration, {cohort, op: tags.op});
    if (response.status === 200 && !violation) {
        success.add(1, {cohort, op: tags.op});
        if (write) writes.add(1, {cohort});
    } else if (response.status === 429) {
        let reason = 'quota';
        if (response.body.includes('Execution capacity busy')) reason = 'admission'; else if (response.body.includes('Principal request rate')) reason = 'principal_rate'; else if (response.body.includes('Subject/selector')) reason = 'selector_rate'; else if (response.body.includes('Known token/selector')) reason = 'preauth_rate';
        throttled.add(1, {cohort, reason});
        (reason === 'admission' ? admission : reason === 'principal_rate' ? principal : reason === 'selector_rate' ? selector : reason === 'preauth_rate' ? preauth : quota).add(1, {cohort});
    } else unexpected.add(1, {cohort, status: String(response.status)});
}

export function mixed() {
    const i = exec.scenario.iterationInTest;
    const t = tenants[i % tenants.length];
    request(t, i + Math.floor(i / tenants.length), 'normal');
}

export function hot() {
    request(tenants[0], exec.scenario.iterationInTest, 'hot');
}

export function neighbor() {
    const i = exec.scenario.iterationInTest;
    request(tenants[1 + i % (tenants.length - 1)], i + Math.floor(i / (tenants.length - 1)), 'neighbor');
}
