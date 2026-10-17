import React from "react";
import ReactDOM from "react-dom/client";
import { lazy, Suspense } from "react";
import { Experience } from "./experience/Experience";
import "./index.css";

const Studio = lazy(() => import("./App"));
const isStudio =
  new URLSearchParams(window.location.search).get("view") === "studio" ||
  ["#studio", "#receiver"].includes(window.location.hash);

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    {isStudio ? (
      <Suspense
        fallback={
          <p className="loading-studio">Opening the transmission studio…</p>
        }
      >
        <Studio />
      </Suspense>
    ) : (
      <Experience />
    )}
  </React.StrictMode>,
);
