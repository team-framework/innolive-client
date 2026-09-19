const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function isValidEmail(value: string) {
  return emailPattern.test(value.trim());
}

export function isValidSignupPassword(value: string) {
  const length = new TextEncoder().encode(value).length;
  return length >= 8 && length <= 72;
}

export function isVerificationCode(value: string) {
  return /^\d{6}$/.test(value.trim());
}
