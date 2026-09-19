export function WaitingOverlay() {
  return (
    <div className="flex w-full max-w-[22.9375rem] flex-col items-center gap-3 rounded-[12px] bg-[#f5f5f5] px-6 py-6 text-center">
      <p className="text-sm font-medium text-text-secondary">화면 미리보기</p>
      <div className="flex flex-col items-center gap-0.5 text-text-primary">
        <p className="text-2xl font-semibold leading-none">접속 대기 인원</p>
        <p className="text-[3.25rem] font-bold leading-none">10명</p>
      </div>
      <p className="break-keep text-lg font-medium leading-[1.3] text-text-secondary">
        예상 대기 시간: 3분
      </p>
      <p className="break-keep text-base font-medium leading-[1.3] text-text-secondary">
        서버의 과부화를 방지하기 위해 잠시 기다려 주세요.
      </p>
    </div>
  );
}
