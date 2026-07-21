export type PreRegistrationState = {
  status: 'idle' | 'success' | 'error'
  message?: string
}

export const initialPreRegistrationState: PreRegistrationState = { status: 'idle' }
