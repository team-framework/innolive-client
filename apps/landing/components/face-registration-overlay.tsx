import Image from "next/image";

export function FaceRegistrationOverlay() {
  return (
    <div className="flex w-full max-w-[40.4375rem] flex-col overflow-clip rounded-[12px] bg-background-secondary min-[48rem]:h-[45rem] min-[48rem]:flex-row">
      <div className="flex w-full flex-col items-start justify-between gap-8 p-8 min-[48rem]:h-full min-[48rem]:w-[15.125rem] min-[48rem]:shrink-0">
        <div className="flex flex-col items-start gap-2.5">
          <p className="text-sm font-medium text-text-secondary">화면 미리보기</p>
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
      <div
        className="hidden w-px self-stretch bg-text-secondary min-[48rem]:block"
        aria-hidden="true"
      />
      <div className="relative aspect-[405/720] w-full min-[48rem]:h-[45rem] min-[48rem]:w-[25.3125rem] min-[48rem]:shrink-0 min-[48rem]:aspect-auto">
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
        <div className="absolute top-[87.083%] left-[13.086%] flex w-[73.827%] flex-col items-center gap-2 text-center">
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
