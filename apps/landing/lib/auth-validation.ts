import type { Messages } from "@/lib/messages";

type ValidationMessages = Messages["auth"]["validation"];
type FieldErrors<Field extends string> = Partial<Record<Field, string>>;

export type LoginErrors = FieldErrors<"email" | "password">;
export type SignupCredentialErrors = FieldErrors<
  "email" | "password" | "confirm" | "agreed"
>;
export type VerificationErrors = FieldErrors<"verificationCode">;

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

export function hasErrors(errors: FieldErrors<string>) {
  return Object.keys(errors).length > 0;
}

function emailError(email: string, messages: ValidationMessages) {
  if (!email.trim()) return messages.emailRequired;
  if (!isValidEmail(email)) return messages.emailInvalid;
  return undefined;
}

export function validateLogin(
  input: { email: string; password: string },
  messages: ValidationMessages,
): LoginErrors {
  const errors: LoginErrors = {};
  const email = emailError(input.email, messages);
  if (email) errors.email = email;
  if (!input.password) errors.password = messages.passwordRequired;
  return errors;
}

export function validateSignupCredentials(
  input: { email: string; password: string; confirm: string; agreed: boolean },
  messages: ValidationMessages,
): SignupCredentialErrors {
  const errors: SignupCredentialErrors = {};
  const email = emailError(input.email, messages);
  if (email) errors.email = email;
  if (!input.password) {
    errors.password = messages.passwordRequired;
  } else if (!isValidSignupPassword(input.password)) {
    errors.password = messages.passwordLength;
  }
  if (!input.confirm) {
    errors.confirm = messages.confirmRequired;
  } else if (input.confirm !== input.password) {
    errors.confirm = messages.confirmMismatch;
  }
  if (!input.agreed) errors.agreed = messages.termsRequired;
  return errors;
}

export function validateVerificationCode(
  code: string,
  messages: ValidationMessages,
): VerificationErrors {
  return isVerificationCode(code) ? {} : { verificationCode: messages.codeRequired };
}
