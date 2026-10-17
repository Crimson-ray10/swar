import { useEffect, useRef, useState } from "react";
import { ArrowRight, Check, Mic } from "lucide-react";
import { WaveBars } from "./primitives";

type DemoPhase =
  | "ready"
  | "speaking"
  | "understanding"
  | "transmitting"
  | "received";
const phaseLabels: Record<DemoPhase, string> = {
  ready: "READY WHEN YOU ARE",
  speaking: "LISTENING TO THE SAMPLE",
  understanding: "FINDING THE MEANING",
  transmitting: "SENDING A SMALLER MESSAGE",
  received: "MESSAGE RECEIVED",
};

// An explicit sample, never a microphone or network claim. A mobile bridge can
// subscribe to onTransmit when the real device integration is available.
export function PushToTalk({
  onTransmit,
}: {
  onTransmit?: (message: string) => void;
}) {
  const [phase, setPhase] = useState<DemoPhase>("ready");
  const [message, setMessage] = useState("Help at the main gate.");
  const phaseRef = useRef<DemoPhase>("ready");
  const timers = useRef<number[]>([]);
  const transition = (next: DemoPhase) => {
    phaseRef.current = next;
    setPhase(next);
  };
  const clearTimers = () => {
    timers.current.forEach(window.clearTimeout);
    timers.current = [];
  };
  const finish = () => {
    if (phaseRef.current !== "speaking") return;
    clearTimers();
    transition("understanding");
    timers.current.push(
      window.setTimeout(() => transition("transmitting"), 1100),
    );
    timers.current.push(
      window.setTimeout(() => {
        transition("received");
        onTransmit?.(message);
      }, 2700),
    );
  };
  const start = () => {
    if (!["ready", "received"].includes(phaseRef.current)) return;
    clearTimers();
    transition("speaking");
    timers.current.push(window.setTimeout(finish, 6000));
  };
  useEffect(() => () => timers.current.forEach(window.clearTimeout), []);
  const busy = phase === "understanding" || phase === "transmitting";
  return (
    <div className={`ptt-demo phase-${phase}`}>
      <div className="demo-message">
        <label className="eyebrow" htmlFor="sample-message">
          CHOOSE A SAMPLE MESSAGE
        </label>
        <select
          id="sample-message"
          value={message}
          disabled={phase !== "ready" && phase !== "received"}
          onChange={(event) => {
            setMessage(event.target.value);
            transition("ready");
          }}
        >
          <option>Help at the main gate.</option>
          <option>The team is safe.</option>
          <option>Medical assistance needed.</option>
        </select>
      </div>
      <div className="ptt-interaction">
        <div className="ptt-halo" />
        <button
          className="ptt-button"
          disabled={busy}
          aria-label={
            phase === "speaking"
              ? "Release to send sample"
              : "Push to talk: hold to start sample, release to send"
          }
          onPointerDown={(event) => {
            if (event.button !== 0) return;
            event.currentTarget.setPointerCapture(event.pointerId);
            start();
          }}
          onPointerUp={finish}
          onPointerCancel={() => {
            clearTimers();
            transition("ready");
          }}
          onLostPointerCapture={() => {
            if (phaseRef.current === "speaking") {
              clearTimers();
              transition("ready");
            }
          }}
          onKeyDown={(event) => {
            if (event.key === " " || event.key === "Enter") {
              event.preventDefault();
              if (!event.repeat) start();
            }
          }}
          onKeyUp={(event) => {
            if (event.key === " " || event.key === "Enter") {
              event.preventDefault();
              finish();
            }
          }}
          onBlur={() => {
            if (phaseRef.current === "speaking") {
              clearTimers();
              transition("ready");
            }
          }}
          onClick={(event) => {
            if (
              event.detail === 0 &&
              (phaseRef.current === "ready" || phaseRef.current === "received")
            ) {
              start();
              timers.current.push(window.setTimeout(finish, 1200));
            }
          }}
        >
          {phase === "received" ? (
            <Check size={33} strokeWidth={1.3} />
          ) : phase === "speaking" || busy ? (
            <WaveBars active count={15} />
          ) : (
            <Mic size={33} strokeWidth={1.3} />
          )}
        </button>
      </div>
      <div className="demo-status" role="status">
        <span className="eyebrow">
          <span className="status-dot" />
          {phaseLabels[phase]}
        </span>
        <p>
          {phase === "received"
            ? `“${message}”`
            : phase === "speaking"
              ? "Release to send the message."
              : busy
                ? "A little signal goes a long way."
                : "Hold to speak. Release to connect."}
        </p>
      </div>
      <div className="demo-progress" aria-label="Sample transmission stages">
        {["PRESS", "SPEAK", "AI DETECTS", "TRANSMIT", "RECEIVE"].map(
          (label, i) => (
            <span
              key={label}
              className={
                i <=
                [
                  "ready",
                  "speaking",
                  "understanding",
                  "transmitting",
                  "received",
                ].indexOf(phase)
                  ? "complete"
                  : ""
              }
            >
              {label}
              {i < 4 && <ArrowRight size={12} />}
            </span>
          ),
        )}
      </div>
      <p className="demo-disclosure">
        Interactive simulation · No microphone access or live transmission.
      </p>
    </div>
  );
}

