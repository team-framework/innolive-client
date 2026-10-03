export function afterPaint(callback: () => void) {
  // The second frame lets the browser paint the HTML before loading animation code.
  let frame = window.requestAnimationFrame(() => {
    frame = window.requestAnimationFrame(callback);
  });
  return () => window.cancelAnimationFrame(frame);
}

export function observeSectionAnimation(
  section: HTMLElement,
  initialize: (isCancelled: () => boolean) => Promise<() => void>,
) {
  let cancelled = false;
  let cleanup: (() => void) | undefined;
  let cancelPaint: (() => void) | undefined;
  const observer = new IntersectionObserver(([entry]) => {
    if (cancelled || !entry.isIntersecting) return;
    observer.disconnect();
    cancelPaint = afterPaint(() => {
      if (cancelled) return;
      void initialize(() => cancelled).then((dispose) => {
        if (cancelled) dispose();
        else cleanup = dispose;
      }).catch(() => {
        // Native content and scrolling remain available if animation loading fails.
      });
    });
  }, { rootMargin: "400px" });
  observer.observe(section);
  return () => {
    cancelled = true;
    cancelPaint?.();
    observer.disconnect();
    cleanup?.();
  };
}
