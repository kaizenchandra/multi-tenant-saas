# Deployment guide

Use README's Maven/Compose commands for the tested local path. Dockerfile builds from source in Java21 Maven then copies
only the executable jar into a non-root JRE image. When dependency downloads are unavailable but `mvn verify` has
already succeeded, `docker build -f docker/Dockerfile.prebuilt -t saas:local .` assembles that prebuilt artifact. Do not
substitute a stale or unverified jar.

Kubernetes installation requires an existing secure PostgreSQL/Redis/Kafka/OIDC deployment; manifests do not provision
managed cloud dependencies. Create saas-secrets with DB_PASSWORD, real TLS trust settings and Redis/Kafka authentication
properties, configure saas-config placeholder endpoints and image, execute owner-only Flyway migration separately, then
apply k8s/platform.yaml with runtime Flyway disabled. Never mount migration-owner credentials into production
application pods. Set browser redirect origins/CSP and IdP client configuration to the deployed TLS host. The frontend
reads its configured IdP URL through /ui-config.

Add an authenticated TLS gateway, request body/rate/admission constraints, restrictive network paths and metrics service
identity. Validate rolling schema compatibility and restore before rollout. PDB/HPA/securityContext/read-only
filesystem/probes/graceful shutdown are supplied, but cluster admission/CNI behavior and managed failover must be tested
in your environment. Only the explicitly isolated local OrbStack deployment below has been executed.

The repository-root .github/workflows/multi-tenant-saas.yml is wired for this monorepo; the nested workflow is a
portable template. Infrastructure observability examples and operational failure/replay/restore procedures are in
runbook.md.

## Verified local Kubernetes deployment

OrbStack shares built Docker images with its single-node Kubernetes engine
([official documentation](https://docs.orbstack.dev/kubernetes/)). This project's development manifest uses
imagePullPolicy Never and dependencies in its own Compose stack. It is distinct from the secure production template.

```sh
docker compose up --build -d
python3 scripts/local-dependencies.py
kubectl --context orbstack apply -f k8s/local-orbstack.yaml
kubectl --context orbstack -n saas-isolation-dev rollout status deployment/saas --timeout=120s
BASE_URL=http://localhost:30088 python3 scripts/demo.py
python3 scripts/k8s-smoke.py
python3 scripts/kafka-failure.py
```

Compose UI/API is localhost8088; local Kubernetes API/UI is localhost30088. Demo realm permits both callback origins and
pins subject IDs. Local Kubernetes API replicas disable workers/messaging; Compose handles durable work. After
rebuilding a mutable local image, explicitly rollout restart deployment/saas in this namespace. Do not apply this
development manifest to a production context. Use the production profile and environment-specific TLS/ACL/secret
configuration for production.

Both manifests now provide a private management service on 8081, a five-second preStop drain,45-second termination grace
and20-second Spring shutdown phases. The drain gives endpoint-routing updates time before JVM shutdown; this is a local
measured configuration, not a guarantee against forced kill/node loss.
Reference: [Kubernetes pod termination](https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/#pod-termination).

For the measured local mixed-load profile, apply
`kubectl --context orbstack -n saas-isolation-dev patch deployment saas --type=strategic --patch-file=k8s/local-load-patch.yaml`
after the base manifest. Refresh `local-dependencies.py` after recreating PostgreSQL/Redis containers; it discovers only
this project's labeled containers. Service `saas` exposes port80 (NodePort30088), targeting pod8080; private metrics
remain8081. Wait for old pods to finish draining before load tests. The local database uses 192 connections and a
measured 1ms group-commit delay, with synchronous durable WAL enabled. Two API pods plus this Compose worker/API share
one physical host with the load client; results are not independent-hardware capacity measurements.
