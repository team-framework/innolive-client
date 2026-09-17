import Image from "next/image";

export function FaceRegistrationOverlay() {
  return (
    <div className="flex max-h-[45rem] w-full max-w-[40.4375rem] overflow-clip rounded-[12px] bg-background-secondary">
      <div className="flex min-h-0 w-[15.125rem] shrink-0 flex-col items-start justify-between gap-8 overflow-clip p-8">
        <div className="flex flex-col items-start gap-2.5">
          <p className="text-[2.25rem] font-medium leading-none text-text-primary">
            얼굴 등록
          </p>
          <p className="text-2xl font-normal leading-[1.2] text-text-secondary">
            내 얼굴만 선명하게
          </p>
        </div>
        <p className="text-center text-xs leading-[1.3] text-text-secondary">
          *관련 약관이 적용됩니다.
        </p>
      </div>
      <div className="w-px self-stretch bg-text-secondary" aria-hidden="true" />
      <div className="relative h-[45rem] w-[25.3125rem] max-w-full shrink-0 overflow-clip">
        <Image
          src="/try-out/face-preview.jpg"
          alt=""
          width={641}
          height={962}
          className="absolute inset-0 size-full object-cover"
        />
        <Image
          src="/try-out/face-guide.svg"
          alt=""
          width={405}
          height={720}
          unoptimized
          className="absolute inset-0 size-full"
        />
        <div className="absolute top-[39.1875rem] left-[3.3125rem] flex w-[18.6875rem] flex-col items-center gap-2 text-center">
          <p className="w-full text-xl font-semibold leading-none text-text-reversed">
            얼굴이 등록되었습니다.
          </p>
          <p className="w-full text-base font-medium leading-[1.3] text-[#fbfbfb]">
            얼굴을 원 안에 위치시킨 후, 잠시 기다려 주세요.
          </p>
        </div>
      </div>
    </div>
  );
}
