import { createContext, useEffect, useRef, useState, type CSSProperties, type ReactNode } from "react";

export const MotionContext = createContext(true);

export function useInView<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  const [visible, setVisible] = useState(false);
  useEffect(() => {
    const observer = new IntersectionObserver(
      ([entry]) => setVisible(entry.isIntersecting),
      { rootMargin: "80px" },
    );
    if (ref.current) observer.observe(ref.current);
    return () => observer.disconnect();
  }, []);
  return { ref, visible };
}

export function Chapter({
  children,
  id,
  className = "",
}: {
  children: ReactNode;
  id: string;
  className?: string;
}) {
  const { ref, visible } = useInView<HTMLElement>();
  const [seen, setSeen] = useState(false);
  useEffect(() => {
    if (visible) setSeen(true);
  }, [visible]);
  return (
    <section
      ref={ref}
      id={id}
      className={`chapter ${className}`}
      data-visible={seen}
      data-running={visible}
    >
      {children}
    </section>
  );
}

export function BrandMark() {
  return (
    <svg
      viewBox="0 0 30 30"
      width="27"
      height="27"
      fill="none"
      aria-hidden="true"
    >
      <path
        d="M4 12v6M11 5v20M18 9v12M25 2v26"
        stroke="currentColor"
        strokeWidth="2.4"
        strokeLinecap="round"
      />
    </svg>
  );
}

export function WaveBars({
  active = false,
  count = 37,
}: {
  active?: boolean;
  count?: number;
}) {
  return (
    <span
      className={`wave-bars ${active ? "is-active" : ""}`}
      aria-hidden="true"
    >
      {Array.from({ length: count }, (_, i) => (
        <i
          key={i}
          style={
            {
              "--bar-height": `${8 + Math.abs(Math.sin(i * 1.72) * Math.cos(i * 0.38)) * 29}px`,
              "--bar-delay": `${-i * 0.08}s`,
              "--bar-speed": `${0.4 + (i % 7) * 0.07}s`,
            } as CSSProperties
          }
        />
      ))}
    </span>
  );
}

