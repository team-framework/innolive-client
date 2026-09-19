"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { Button } from "@/components/button";
import { TextField } from "@/components/text-field";
import { authErrorMessage, requestAuth, saveSession } from "@/lib/auth-client";
import { utf8ByteLength } from "@/lib/auth-config";

const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function LoginForm() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [errors, setErrors] = useState<{ email?: string; password?: string }>(
    {},
  );
  const [notice, setNotice] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const onSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const next: { email?: string; password?: string } = {};
    const trimmedEmail = email.trim();
    if (!trimmedEmail) {
      next.email = "이메일을 입력해 주세요";
    } else if (!emailPattern.test(trimmedEmail)) {
      next.email = "올바른 이메일 주소를 입력해 주세요";
    }
    if (!password) {
      next.password = "비밀번호를 입력해 주세요";
    } else if (utf8ByteLength(password) < 8 || utf8ByteLength(password) > 72) {
      next.password = "비밀번호는 UTF-8 기준 8~72바이트여야 합니다";
    }
    setErrors(next);
    setNotice(null);
    if (Object.keys(next).length > 0) return;

    setIsSubmitting(true);
    try {
      const response = await requestAuth("/auth/sign-in", {
        method: "POST",
        body: JSON.stringify({ email: trimmedEmail, password }),
      });
      await saveSession(await response.json());
      router.push("/");
      router.refresh();
    } catch (error) {
      setNotice(authErrorMessage(error));
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <form
      className="flex w-full max-w-[32rem] flex-col items-center gap-7"
      noValidate
      onSubmit={onSubmit}
    >
      <h1 className="text-[clamp(1.75rem,1.2rem+1.5vw,2.75rem)] font-semibold leading-[1.3] text-text-primary">
        로그인
      </h1>
      <div className="flex w-full flex-col items-start gap-2">
        <TextField
          icon="mail"
          label="이메일"
          type="email"
          autoComplete="email"
          value={email}
          error={errors.email}
          onChange={(event) => setEmail(event.target.value)}
        />
        <TextField
          icon="lock"
          label="비밀번호"
          autoComplete="current-password"
          revealable
          value={password}
          error={errors.password}
          onChange={(event) => setPassword(event.target.value)}
        />
        <div className="flex w-full justify-end px-2">
          <button
            type="button"
            className="text-base font-normal leading-[1.3] text-text-secondary underline [text-underline-position:from-font]"
            onClick={() => setNotice("비밀번호 찾기 기능을 준비 중입니다")}
          >
            비밀번호 찾기
          </button>
        </div>
      </div>
      <div className="flex w-full flex-col items-stretch gap-2">
        <Button
          type="submit"
          variant="secondary"
          showChevron={false}
          className="w-full max-w-none"
          disabled={isSubmitting}
        >
          {isSubmitting ? "로그인 중" : "로그인"}
        </Button>
        <Button
          href="/signup"
          showChevron={false}
          className="w-full max-w-none"
          disabled={isSubmitting}
        >
          회원가입으로 이동
        </Button>
      </div>
      {notice ? (
        <p role="status" aria-live="polite" className="w-full text-center text-base text-text-primary">
          {notice}
        </p>
      ) : null}
    </form>
  );
}
