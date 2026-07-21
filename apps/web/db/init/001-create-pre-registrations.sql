CREATE TABLE IF NOT EXISTS pre_registrations (
  id BIGSERIAL PRIMARY KEY,
  email VARCHAR(320) NOT NULL UNIQUE,
  privacy_consent_at TIMESTAMPTZ NOT NULL,
  privacy_policy_version VARCHAR(32) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
