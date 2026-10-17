import React from "react";
import ReactDOM from "react-dom/client";
import { PixelTransceiver } from "./components/PixelTransceiver";

function TransceiverRoot() {
  return (
    <PixelTransceiver
      onBackToMain={() => {
        window.location.href = "/";
      }}
    />
  );
}

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <TransceiverRoot />
  </React.StrictMode>
);
