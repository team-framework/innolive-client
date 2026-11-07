import assert from "node:assert/strict";
import test from "node:test";
import ko from "../messages/ko.json" with { type: "json" };
import {
  hasErrors,
  isValidEmail,
  isValidSignupPassword,
  isVerificationCode,
  validateLogin,
  validateSignupCredentials,
  validateVerificationCode,
} from "./auth-validation.ts";

const messages = ko.auth.validation;

test("인증 입력값 검증", () => {
  assert.equal(isValidEmail(" user@example.com "), true);
  assert.equal(isValidEmail("not-an-email"), false);
  assert.equal(isValidSignupPassword("12345678"), true);
  assert.equal(isValidSignupPassword("1234567"), false);
  assert.equal(isValidSignupPassword("가".repeat(25)), false);
  assert.equal(isVerificationCode("123456"), true);
  assert.equal(isVerificationCode("12345a"), false);
});

test("로그인 폼 필드별 오류 문구", () => {
  assert.deepEqual(validateLogin({ email: "  ", password: "" }, messages), {
    email: messages.emailRequired,
    password: messages.passwordRequired,
  });
  assert.deepEqual(validateLogin({ email: "user@", password: "x" }, messages), {
    email: messages.emailInvalid,
  });
  const valid = validateLogin({ email: " user@example.com ", password: "1" }, messages);
  assert.deepEqual(valid, {});
  assert.equal(hasErrors(valid), false);
});

test("회원가입 자격 증명 필드별 오류 문구", () => {
  const empty = validateSignupCredentials(
    { email: "", password: "", confirm: "", agreed: false },
    messages,
  );
  assert.deepEqual(empty, {
    email: messages.emailRequired,
    password: messages.passwordRequired,
    confirm: messages.confirmRequired,
    agreed: messages.termsRequired,
  });
  assert.equal(hasErrors(empty), true);

  assert.deepEqual(
    validateSignupCredentials(
      { email: "bad", password: "1234567", confirm: "12345678", agreed: true },
      messages,
    ),
    {
      email: messages.emailInvalid,
      password: messages.passwordLength,
      confirm: messages.confirmMismatch,
    },
  );

  assert.deepEqual(
    validateSignupCredentials(
      { email: "user@example.com", password: "12345678", confirm: "12345678", agreed: true },
      messages,
    ),
    {},
  );
});

test("인증 코드 오류 문구", () => {
  assert.deepEqual(validateVerificationCode("12345", messages), {
    verificationCode: messages.codeRequired,
  });
  assert.deepEqual(validateVerificationCode("123456", messages), {});
});
