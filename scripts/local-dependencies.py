#!/usr/bin/env python3
"""OrbStack only: private Service/EndpointSlice routes to this project's Compose dependencies.
Refresh after dependency container recreation; never pin host-port hairpin addresses.
"""
import ipaddress
import json
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1];
namespace = 'saas-isolation-dev';
items = [{'apiVersion': 'v1', 'kind': 'Namespace',
          'metadata': {'name': namespace, 'labels': {'app.kubernetes.io/part-of': 'multi-tenant-saas'}}}]
for dependency, port in [('postgres', 5432), ('redis', 6379)]:
    identifier = subprocess.check_output(
        ['docker', 'compose', '-f', str(root / 'docker-compose.yml'), 'ps', '-q', dependency], text=True).strip();
    assert identifier, 'Dependency not running: ' + dependency
    info = json.loads(subprocess.check_output(['docker', 'inspect', identifier]))[0]
    assert info['State']['Running'] and info['Config']['Labels']['com.docker.compose.service'] == dependency
    assert info['Config']['Labels']['com.docker.compose.project'] == 'multi-tenant-saas'
    addresses = [v['IPAddress'] for v in info['NetworkSettings']['Networks'].values() if v['IPAddress']]
    assert len(addresses) == 1 and ipaddress.ip_address(addresses[0]).is_private
    name = 'saas-' + dependency
    items.append({'apiVersion': 'v1', 'kind': 'Service', 'metadata': {'name': name, 'namespace': namespace},
                  'spec': {'ports': [{'name': 'tcp', 'port': port, 'targetPort': port}]}})
    items.append({'apiVersion': 'discovery.k8s.io/v1', 'kind': 'EndpointSlice',
                  'metadata': {'name': name + '-compose', 'namespace': namespace,
                               'labels': {'kubernetes.io/service-name': name,
                                          'endpointslice.kubernetes.io/managed-by': 'local-saas-dependencies'}},
                  'addressType': 'IPv4', 'ports': [{'name': 'tcp', 'protocol': 'TCP', 'port': port}],
                  'endpoints': [{'addresses': addresses, 'conditions': {'ready': True}}]})
manifest = json.dumps({'apiVersion': 'v1', 'kind': 'List', 'items': items})
subprocess.run(['kubectl', '--context', 'orbstack', '-n', namespace, 'apply', '-f', '-'], input=manifest, text=True,
               check=True)
(root / 'benchmarks/local-dependencies.json').write_text(manifest + '\n')
