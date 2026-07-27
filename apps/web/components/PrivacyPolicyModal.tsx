import {useEffect} from "react";
import Link from "next/link";

interface PrivacyPolicyModalProps {
  isOpen: boolean,
  onClose: () => void,
}

function PrivacyPolicyModal({isOpen, onClose}: PrivacyPolicyModalProps) {
  useEffect(() => {
    if (!isOpen) {
      return;
    }

  }, [isOpen])

  if (!isOpen) {
    return null
  }

  const onDisagreed = () => {
    localStorage.setItem("facePrivacyPolicy", "false");
    onClose()
  }

  const onAgreed = () => {
    localStorage.setItem("facePrivacyPolicy", "true");
    onClose()
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/65 px-6 backdrop-blur-sm" role="presentation">
      <section className="flex flex-col gap-10 rounded-2xl overflow-y-auto bg-black px-20 py-15 text-center shadow-[0_20px_42px_rgba(0,0,0,0.55)]" role="dialog">
        <article className="mx-auto max-w-3xl space-y-10 text-base leading-8 text-[#e5e5e5]">
          <h1 className="mt-6 text-3xl font-semibold text-white">개인정보 수집 동의</h1>
          <section>
            <h2 className="text-xl font-semibold text-white">1. 수집하는 개인정보</h2>
              <p>InnoLive는 서비스 체험을 위해 사용자가 등록한 얼굴 이미지 원본을 임시로 수집합니다.</p>
          </section>
          <section>
            <h2 className="text-xl font-semibold text-white">2. 이용 목적</h2>
            <p>얼굴 이미지 원본은 체험 서비스를 제공하기 위해서만 이용합니다.</p>
          </section>
          <section>
            <h2 className="text-xl font-semibold text-white">3. 보유 및 이용 기간</h2>
            <p>얼굴 이미지 원본은 별도의 파일이나 데이터베이스에 저장하지 않고 열람이 불가능한 임시 메모리에만 보관합니다.</p>
            <p>얼굴 이미지 원본은 매일 00:00 KST에 삭제됩니다.</p>
          </section>
          <section>
            <h2 className="text-xl font-semibold text-white">4. 동의 거부 권리</h2>
            <p>얼굴 이미지 원본의 수집·이용에 동의하지 않으면 얼굴 등록 체험 서비스를 이용할 수 없습니다.</p>
          </section>
        </article>
        <div className="flex justify-center gap-5">
          <button onClick={onAgreed} className="cursor-pointer rounded-lg bg-white px-[clamp(15px,1.04vw,20px)] py-[clamp(6px,0.42vw,8px)] text-[clamp(15px,1.04vw,20px)] text-black transition duration-200 hover:-translate-y-0.5 hover:bg-[#d9d9d9] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white">
            동의합니다.
          </button>
          <button onClick={onDisagreed} className="cursor-pointer rounded-lg bg-black px-[clamp(15px,1.04vw,20px)] py-[clamp(6px,0.42vw,8px)] text-[clamp(15px,1.04vw,20px)] text-white transition duration-200 hover:-translate-y-0.5 hover:bg-[#2d2d2d] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white">
            동의하지 않습니다.
          </button>
        </div>
      </section>
    </div>
  )
}

export default PrivacyPolicyModal