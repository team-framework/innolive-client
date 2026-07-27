import Link from 'next/link'

const effectiveDate = '2026년 7월 27일'
const contactEmail = process.env.NEXT_PUBLIC_PRIVACY_CONTACT_EMAIL ?? 'chaeyn@dgsw.hs.kr'

export default function PrivacyPage() {
  return (
    <main className="min-h-screen bg-[#050505] px-6 py-16 text-white md:px-12">
      <article className="mx-auto max-w-3xl space-y-10 text-base leading-8 text-[#e5e5e5]">
        <div><Link href="/" className="text-sm text-[#a3a3a3] underline underline-offset-4">InnoLive로 돌아가기</Link><h1 className="mt-6 text-3xl font-semibold text-white">개인정보처리방침</h1><p className="mt-2 text-sm text-[#a3a3a3]">시행일: {effectiveDate}</p>
        </div>
        <section>
          <h2 className="text-xl font-semibold text-white">1. 수집하는 개인정보</h2>
          <section className="mt-2">
            <h3 className="text-lg text-white">사전 등록 시</h3>
            <p>InnoLive는 사전신청 등록을 위해 이메일 주소를 수집합니다.</p>
          </section>
          <section className="mt-2">
            <h3 className="text-lg text-white">서비스 체험 시</h3>
            <p>InnoLive는 서비스 체험을 위해 사용자가 등록한 얼굴 이미지 원본을 임시로 수집합니다.</p>
          </section>
        </section>
        <section>
          <h2 className="text-xl font-semibold text-white">2. 이용 목적</h2>
          <section className="mt-2">
            <h3 className="text-lg text-white">사전 등록 시</h3>
            <p>수집한 이메일 주소는 InnoLive의 정식 출시 알림 및 사전신청 혜택 안내를 위해서만 이용합니다.</p>
          </section>
          <section className="mt-2">
            <h3 className="text-lg text-white">서비스 체험 시</h3>
            <p>얼굴 이미지 원본은 체험 서비스를 제공하기 위해서만 이용합니다.</p>
          </section>
        </section>
        <section>
          <h2 className="text-xl font-semibold text-white">3. 보유 및 이용 기간</h2>
          <section className="mt-2">
            <h3 className="text-lg text-white">사전 등록 시</h3>
            <p>개인정보는 동의 철회 또는 수집 목적 달성 시까지 보관하며, 목적 달성 후 지체 없이 파기합니다. 법령상 보관 의무가 있는 경우에는 해당 기간 동안 보관할 수 있습니다.</p>
          </section>
          <section className="mt-2">
            <h3 className="text-lg text-white">서비스 체험 시</h3>
            <p>얼굴 이미지 원본은 별도의 파일이나 데이터베이스에 저장하지 않고 열람이 불가능한 임시 메모리에만 보관합니다.</p>
            <p>얼굴 이미지 원본은 매일 00:00 KST에 삭제됩니다.</p>
          </section>
        </section>
        <section>
          <h2 className="text-xl font-semibold text-white">4. 동의 거부 권리</h2>
          <section className="mt-2">
            <h3 className="text-lg text-white">사전 등록 시</h3>
            <p>개인정보 수집·이용에 동의하지 않을 권리가 있습니다. 다만 동의하지 않으면 사전신청 등록을 할 수 없습니다.</p>
          </section>
          <section className="mt-2">
            <h3 className="text-lg text-white">서비스 체험 시</h3>
            <p>얼굴 이미지 원본의 수집·이용에 동의하지 않으면 얼굴 등록 체험 서비스를 이용할 수 없습니다.</p>
          </section>
        </section>
        <section>
          <h2 className="text-xl font-semibold text-white">5. 문의 및 동의 철회</h2>
          <p>개인정보 관련 문의 또는 동의 철회는 <a className="underline underline-offset-4" href={`mailto:${contactEmail}`}>{contactEmail}</a>로 요청할 수 있습니다.</p>
        </section>
      </article>
    </main>
  )
}
