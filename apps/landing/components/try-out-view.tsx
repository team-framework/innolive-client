"use client";

import { useId, useState } from "react";
import { Button } from "@/components/button";
import { Dialog } from "@/components/dialog";
import { FaceRegistrationOverlay } from "@/components/face-registration-overlay";
import { WaitingOverlay } from "@/components/waiting-overlay";
import { SequentialHeroHeading } from "@/components/sequential-hero-heading";
import { useLocale } from "@/components/locale-provider";
import { useIsLogined } from "@/hooks/use-is-logined";

type Preview = "guest" | "active" | "member";
type Overlay = "none" | "face" | "waiting";

export function TryOutView() {
  const { href, messages } = useLocale();
  const { isLogined, isLoading } = useIsLogined();
  const [preview, setPreview] = useState<Preview>("guest");
  const [overlay, setOverlay] = useState<Overlay>("none");
  const [notice, setNotice] = useState<string | null>(null);
  const noticeId = useId();

  const closeOverlay = () => setOverlay("none");

  const unavailable = () => {
    setNotice(messages.tryOut.unavailable);
  };

  return (
    <section
      className="flex w-full flex-col items-center px-[var(--page-gutter)] pb-16 pt-16 lg:pt-24 min-[106.5rem]:pb-[5.25rem] min-[106.5rem]:pt-[9.5rem]"
      aria-labelledby="try-out-heading"
    >
      <div className="flex w-full max-w-[100rem] flex-col items-center gap-[4.125rem]">
        <div className="flex flex-col items-center gap-8 text-center text-text-primary">
          <SequentialHeroHeading
            segments={[
              {
                text: messages.tryOut.heading,
                className:
                  "break-keep text-[clamp(2rem,1.2rem+3.2vw,4rem)] font-bold leading-none",
              },
            ]}
            ariaLabel={messages.tryOut.ariaLabel}
          />
          <p className="break-keep text-body-lg font-normal">
            {messages.tryOut.body}
          </p>
        </div>

        <div className="flex w-full flex-col items-center gap-[0.5625rem]">
          {preview === "active" ? (
            <div className="flex w-full flex-col gap-2.5 lg:flex-row">
              <div className="aspect-[795/447] min-h-[14.0625rem] w-full rounded-[12px] bg-background-secondary lg:max-h-[27.95rem]" />
              <div className="aspect-[795/447] min-h-[14.0625rem] w-full rounded-[12px] bg-background-secondary lg:max-h-[27.95rem]" />
            </div>
          ) : (
            <div className="relative flex aspect-[16/9] w-full items-center justify-center overflow-clip rounded-[12px] bg-background-secondary">
              {preview === "guest" ? (
                <p className="px-4 text-center text-[clamp(1.5rem,1rem+2vw,3rem)] font-normal leading-none text-text-primary">
                  {messages.tryOut.cameraPermission}
                </p>
              ) : null}
            </div>
          )}

          <div className="flex w-full max-w-[32.3125rem] flex-col items-center">
            <div className="flex flex-wrap items-start justify-center gap-x-3 gap-y-4 p-2.5">
              {preview === "guest" ? (
                <>
                  <Button
                    href={href("/try-out/experience")}
                    showChevron={false}
                    className="w-[14.375rem] max-w-[14.375rem]"
                    disabled={isLoading}
                    aria-describedby={notice ? noticeId : undefined}
                  >
                    {isLoading
                      ? messages.tryOut.checking
                      : isLogined
                        ? messages.tryOut.start
                        : messages.tryOut.startGuest}
                  </Button>
                  {!isLogined && !isLoading ? (
                    <Button
                      variant="secondary"
                      href={href("/login")}
                      showChevron={false}
                      className="w-[14.375rem] max-w-[14.375rem]"
                    >
                      {messages.tryOut.loginOrSignup}
                    </Button>
                  ) : null}
                </>
              ) : null}
              {preview === "active" ? (
                <>
                  <Button
                    showChevron={false}
                    className="w-[8.6875rem] max-w-[8.6875rem]"
                    onClick={() => {
                      setPreview("guest");
                      setNotice(null);
                    }}
                  >
                    {messages.tryOut.stop}
                  </Button>
                  <Button
                    variant="secondary"
                    showChevron={false}
                    className="w-[8.6875rem] max-w-[8.6875rem]"
                    onClick={() => setOverlay("face")}
                  >
                    {messages.tryOut.registerFace}
                  </Button>
                </>
              ) : null}
              {preview === "member" ? (
                <Button
                  showChevron={false}
                  className="w-[13rem] max-w-[13rem]"
                  onClick={unavailable}
                  aria-describedby={notice ? noticeId : undefined}
                >
                  {messages.tryOut.memberCta}
                </Button>
              ) : null}
            </div>
            {!isLogined ? (
              <p className="w-full text-center text-xl font-medium text-text-secondary">
                {messages.tryOut.tip}
              </p>
            ) : null}
            <p className="w-full text-center text-xl font-medium text-text-secondary">
              {messages.tryOut.networkNote}
            </p>
            {notice ? (
              <p
                id={noticeId}
                role="status"
                className="mt-2 w-full text-center text-base font-medium text-text-primary"
              >
                {notice}
              </p>
            ) : null}
          </div>
        </div>
      </div>

      <Dialog
        open={overlay === "waiting"}
        onClose={closeOverlay}
        label={messages.tryOut.waitingPreview}
      >
        <WaitingOverlay />
      </Dialog>
      <Dialog
        open={overlay === "face"}
        onClose={closeOverlay}
        label={messages.tryOut.facePreview}
      >
        <FaceRegistrationOverlay />
      </Dialog>
    </section>
  );
}
