export type PreRegistrationState = {
  status: 'idle' | 'success' | 'error'
  messageKey?: 'success' | 'invalidEmail' | 'consentRequired' | 'failure'
}

export const initialPreRegistrationState: PreRegistrationState = { status: 'idle' }
