CREATE TABLE incidents (
 id varchar(20) PRIMARY KEY, service varchar(80) NOT NULL, severity varchar(10) NOT NULL,
 summary text NOT NULL, status varchar(20) NOT NULL, evidence jsonb NOT NULL, runbook text NOT NULL
);
INSERT INTO incidents VALUES
('INC-2042','checkout-api','SEV-2','Checkout latency rose after connection pool configuration change','OPEN',
'{"p95LatencyMs":1840,"baselineP95Ms":220,"errorRatePercent":6.8,"poolUtilizationPercent":98,"deployment":"checkout-v2.18.0","change":"JDBC pool reduced from 40 to 8","region":"us-east","source":"seeded enterprise telemetry"}',
'RB-017: Compare the active JDBC pool configuration to the last healthy release. Open a follow-up for the service owner to validate pool sizing in staging. Never change production automatically.'),
('INC-2043','inventory-api','SEV-3','Inventory availability API returns stale stock counts','OPEN',
'{"cacheAgeSeconds":780,"expectedCacheAgeSeconds":60,"errorRatePercent":0.4,"consumerLag":2400,"deployment":"inventory-v1.9.2","change":"Event consumer paused during maintenance","source":"seeded enterprise telemetry"}',
'RB-031: Inspect the inventory event consumer and compare offsets. Open a follow-up to verify consumer health in staging. Do not purge caches or restart production automatically.');
CREATE TABLE runs (
 id uuid PRIMARY KEY, incident_id varchar(20) NOT NULL REFERENCES incidents(id), mode varchar(16) NOT NULL,
 status varchar(24) NOT NULL, prompt text NOT NULL, report text NOT NULL DEFAULT '',
 events jsonb NOT NULL DEFAULT '[]', created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(), approval_at timestamptz
);
CREATE INDEX runs_created ON runs(created_at DESC);
CREATE TABLE followups (
 id uuid PRIMARY KEY, run_id uuid UNIQUE NOT NULL REFERENCES runs(id), incident_id varchar(20) NOT NULL REFERENCES incidents(id),
 summary text NOT NULL, status varchar(20) NOT NULL DEFAULT 'OPEN', created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE gateway_audit (
 id bigserial PRIMARY KEY, request_id uuid NOT NULL, principal varchar(20) NOT NULL,
 method varchar(64) NOT NULL, tool varchar(80) NOT NULL, decision varchar(12) NOT NULL,
 reason varchar(100) NOT NULL, status integer NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX gateway_audit_created ON gateway_audit(created_at DESC);
