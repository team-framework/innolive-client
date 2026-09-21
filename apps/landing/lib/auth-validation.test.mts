import assert from "node:assert/strict";
import test from "node:test";
import {
  isValidEmail,
  isValidSignupPassword,
  isVerificationCode,
} from "./auth-validation.ts";

test("인증 입력값 검증", () => {
  assert.equal(isValidEmail(" user@example.com "), true);
  assert.equal(isValidEmail("not-an-email"), false);
  assert.equal(isValidSignupPassword("12345678"), true);
  assert.equal(isValidSignupPassword("1234567"), false);
  assert.equal(isValidSignupPassword("가".repeat(25)), false);
  assert.equal(isVerificationCode("123456"), true);
  assert.equal(isVerificationCode("12345a"), false);
});
