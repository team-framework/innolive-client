import { database } from './database'

const privacyPolicyVersion = '2026-07-21'

export async function registerPreRegistration(email: string) {
  await database.query(
    `INSERT INTO pre_registrations (email, privacy_consent_at, privacy_policy_version)
     VALUES ($1, NOW(), $2)
     ON CONFLICT (email) DO NOTHING`,
    [email, privacyPolicyVersion],
  )
}
