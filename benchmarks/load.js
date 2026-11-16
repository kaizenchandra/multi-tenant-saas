import http from 'k6/http';
import exec from 'k6/execution';
import {check} from 'k6';
import {Counter} from 'k6/metrics';

const leaks = new Counter('tenant_isolation_violations');
const throttled = new Counter('rate_limited_responses');
const admission = new Counter('admission_rejections');
const quota = new Counter('quota_rejections');
const authorized = new Counter('authorized_responses');
const unexpected = new Counter('unexpected_responses');
const tenants = JSON.parse(__ENV.TENANTS || '[]');
export const options = {
    scenarios: {
        api: {
            executor: 'constant-arrival-rate',
            rate: Number(__ENV.RPS || 1000),
            timeUnit: '1s',
            duration: __ENV.DURATION || '30s',
            preAllocatedVUs: Number(__ENV.PREALLOCATED_VUS || 256),
            maxVUs: Number(__ENV.MAX_VUS || 1000)
        }
    },
    thresholds: {
        tenant_isolation_violations: ['count==0'],
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(99)<200'],
        dropped_iterations: ['count<' + Math.max(1, Number(__ENV.RPS || 1000) * parseFloat(__ENV.DURATION || '30s') * 0.001)]
    }
};
export default function () {
    if (!tenants.length) throw Error('TENANTS must contain tenant/token/forbiddenId entries');
    const t = tenants[exec.scenario.iterationInTest % tenants.length];
    const headers = {Authorization: 'Bearer ' + t.token, 'X-Tenant-ID': t.tenant};
    const r = http.get((__ENV.BASE_URL || 'http://localhost:8088') + '/api/v1/projects', {headers});
    if (r.status === 429) {
        throttled.add(1);
        if (r.body.includes("Execution capacity busy")) admission.add(1); else quota.add(1);
    } else if (r.status === 200) authorized.add(1); else unexpected.add(1);
    check(r, {'authorized list': r.status === 200});
    if (t.forbiddenId) {
        const x = http.get((__ENV.BASE_URL || 'http://localhost:8088') + '/api/v1/projects/' + t.forbiddenId, {headers});
        if (x.status !== 404 && x.status !== 403 && x.status !== 429) leaks.add(1);
    }
}
