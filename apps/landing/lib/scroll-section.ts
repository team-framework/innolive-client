import { ScrollSmoother } from "gsap/ScrollSmoother";
import { ScrollTrigger } from "gsap/ScrollTrigger";

const WHEEL_QUIET_MS = 260;
const STEP_DISTANCE = 40;
let activeSection: HTMLElement | undefined;
const sections = new Map<HTMLElement, {
  bounds: () => { start: number; end: number };
  intercept: (delta: number, event: Event, fresh: boolean) => void;
}>();

type ScrollSectionOptions = {
  section: HTMLElement;
  start: () => string;
  lastStep: number;
  show: (step: number) => void;
  animate: (from: number, to: number, done: () => void) => void;
};

/** Consume the arrival gesture, then advance once per new wheel, swipe, or key press. */
export function createScrollSection({
  section, start, lastStep, show, animate,
}: ScrollSectionOptions) {
  let active = false;
  let step = 0;
  let busy = false;
  let consumed = true;
  let distance = 0;
  let lastWheel = 0;
  let touchY: number | undefined;
  let touchDistance = 0;
  let refreshFrame = 0;
  let touchLocked = false;

  const scrollTo = (top: number) => {
    const smoother = ScrollSmoother.get();
    if (smoother) smoother.scrollTop(top);
    else window.scrollTo({ top, behavior: "instant" });
  };
  const updateState = () => {
    section.dataset.scrollStep = String(step);
    section.dataset.scrollLocked = String(active);
    section.dataset.scrollAnimating = String(busy);
  };
  const release = () => {
    active = false;
    busy = false;
    touchLocked = false;
    if (activeSection === section) activeSection = undefined;
    updateState();
  };
  const trigger = ScrollTrigger.create({
    trigger: section,
    start,
    end: "+=2",
    pin: true,
    invalidateOnRefresh: true,
    onRefresh: () => {
      cancelAnimationFrame(refreshFrame);
      refreshFrame = requestAnimationFrame(() => {
        if (active) scrollTo(trigger.start + 1);
      });
    },
  });
  show(step);
  updateState();

  const hasCloserSection = (delta: number, y: number) =>
    Array.from(sections.entries()).some(([other, controller]) => {
      if (other === section) return false;
      const bounds = controller.bounds();
      return delta > 0
        ? bounds.start > y && bounds.start < trigger.start
        : bounds.end < y && bounds.end > trigger.end;
    });

  const intercept = (delta: number, event: Event, fresh: boolean) => {
    if (document.documentElement.dataset.introComplete !== "true") return;
    if (activeSection && activeSection !== section) return;
    const y = ScrollSmoother.get()?.scrollTop() ?? window.scrollY;
    if (active && !touchLocked && Math.abs(y - (trigger.start + 1)) > 3) release();
    if (!active) {
      const enteringDown = delta > 0 && y <= trigger.start && Math.max(y, window.scrollY) + delta >= trigger.start;
      const enteringUp = delta < 0 && y >= trigger.end && Math.min(y, window.scrollY) + delta <= trigger.end;
      if (!enteringDown && !enteringUp && !(y >= trigger.start && y <= trigger.end)) return;
      if (hasCloserSection(delta, y)) return;
      active = true;
      touchLocked = event.type === "touchmove";
      activeSection = section;
      consumed = true;
      distance = 0;
      step = enteringUp ? lastStep : 0;
      show(step);
      scrollTo(trigger.start + 1);
      updateState();
    } else {
      if (fresh) {
        // A gesture begun during an animation stays consumed after it finishes.
        consumed = busy;
        distance = 0;
      }
      distance += delta;
      if (!consumed && !busy && Math.abs(distance) >= STEP_DISTANCE) {
        consumed = true;
        const next = step + Math.sign(distance);
        if (next < 0 || next > lastStep) {
          release();
          scrollTo(next < 0 ? trigger.start - 1 : trigger.end + 1);
          // A large reverse input must stop at the preceding section as well.
          for (const [other, controller] of sections) {
            if (other !== section) controller.intercept(delta, event, fresh);
            if (event.defaultPrevented) break;
          }
          // Let this same input move the document after the final completed step.
          return;
        }
        const previous = step;
        step = next;
        busy = true;
        updateState();
        animate(previous, step, () => {
          busy = false;
          updateState();
        });
      }
    }
    event.preventDefault();
    event.stopImmediatePropagation();
  };
  const onWheel = (event: WheelEvent) => {
    if (event.ctrlKey || Math.abs(event.deltaX) > Math.abs(event.deltaY)) return;
    const now = performance.now();
    const fresh = now - lastWheel > WHEEL_QUIET_MS;
    lastWheel = now;
    const delta = event.deltaY * (event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? innerHeight : 1);
    if (delta) intercept(delta, event, fresh);
  };
  const onTouchStart = (event: TouchEvent) => {
    touchY = event.touches.length === 1 ? event.touches[0].clientY : undefined;
    touchDistance = 0;
    if (active) { consumed = busy; distance = 0; }
  };
  const onTouchMove = (event: TouchEvent) => {
    if (touchY === undefined || event.touches.length !== 1) return;
    const delta = touchY - event.touches[0].clientY;
    touchY = event.touches[0].clientY;
    touchDistance += delta;
    if (Math.abs(touchDistance) < 8 || (activeSection && activeSection !== section)) return;
    const y = ScrollSmoother.get()?.scrollTop() ?? window.scrollY;
    const approaching = !activeSection && !hasCloserSection(delta, y) && (
      (delta > 0 && y < trigger.start && trigger.start - y < innerHeight) ||
      (delta < 0 && y > trigger.end && y - trigger.end < innerHeight)
    );
    intercept(delta, event, false);
    if (approaching && !event.defaultPrevented) {
      // Own the swipe before the browser starts an uncancellable native pan.
      event.preventDefault();
      event.stopImmediatePropagation();
      scrollTo(y + delta);
    }
  };
  const onScroll = () => {
    // Cancel native touch momentum that began farther away from the section.
    if (active && touchLocked && Math.abs(window.scrollY - (trigger.start + 1)) > 2) {
      scrollTo(trigger.start + 1);
    }
  };
  const onKey = (event: KeyboardEvent) => {
    if (["Tab", "Home", "End", "Escape"].includes(event.key)) {
      release();
      return;
    }
    if (event.defaultPrevented || event.ctrlKey || event.altKey || event.metaKey ||
      (event.target instanceof HTMLElement && event.target.closest("a, button, input, select, textarea, [contenteditable]"))) return;
    const down = ["ArrowDown", "PageDown"].includes(event.key) || (event.key === " " && !event.shiftKey);
    const up = ["ArrowUp", "PageUp"].includes(event.key) || (event.key === " " && event.shiftKey);
    if (down || up) intercept(down ? 80 : -80, event, !event.repeat);
  };

  sections.set(section, { bounds: () => trigger, intercept });
  window.addEventListener("wheel", onWheel, { capture: true, passive: false });
  window.addEventListener("touchstart", onTouchStart, { passive: true });
  window.addEventListener("touchmove", onTouchMove, { capture: true, passive: false });
  window.addEventListener("keydown", onKey, true);
  window.addEventListener("scroll", onScroll, { passive: true });
  window.addEventListener("hashchange", release);
  return () => {
    release();
    cancelAnimationFrame(refreshFrame);
    window.removeEventListener("wheel", onWheel, true);
    window.removeEventListener("touchstart", onTouchStart);
    window.removeEventListener("touchmove", onTouchMove, true);
    window.removeEventListener("keydown", onKey, true);
    window.removeEventListener("scroll", onScroll);
    window.removeEventListener("hashchange", release);
    sections.delete(section);
    trigger.kill();
    delete section.dataset.scrollStep;
    delete section.dataset.scrollLocked;
    delete section.dataset.scrollAnimating;
  };
}
