export function PolicyPage({
  title,
  subtitle,
}: {
  title: string;
  subtitle: string;
}) {
  const headingId = title === "이용약관" ? "terms-heading" : "privacy-heading";

  return (
    <main
      id="main"
      data-page="policy"
      className="min-h-dvh bg-background-primary"
    >
      <section
        className="flex w-full flex-col items-center px-[var(--page-gutter)] pb-16 pt-16 lg:pt-24 min-[106.5rem]:pb-[5.25rem] min-[106.5rem]:pt-[9.5rem]"
        aria-labelledby={headingId}
      >
        <div className="flex w-full max-w-[100rem] flex-col items-center gap-[4.125rem]">
          <header className="flex flex-col items-center gap-8 text-center text-text-primary">
            <h1
              id={headingId}
              className="break-keep text-[clamp(2rem,1.2rem+3.2vw,4rem)] font-bold leading-none"
            >
              {title}
            </h1>
            <p className="break-keep text-body-lg font-normal leading-[1.15]">
              {subtitle}
            </p>
          </header>
          <div className="w-full max-w-[100rem] text-body-lg font-normal leading-[1.25] text-text-primary">
            <p>본문 준비 중</p>
          </div>
        </div>
      </section>
    </main>
  );
}
