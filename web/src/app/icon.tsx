import { ImageResponse } from "next/og";

export const size = { width: 32, height: 32 };
export const contentType = "image/png";

export default function Icon() {
  return new ImageResponse(
    <div
      style={{
        width: "32px",
        height: "32px",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        background: "#0b1733",
        border: "2px solid #60a5fa",
        color: "#60a5fa",
        fontSize: "25px",
        lineHeight: 1,
      }}
    >
      +
    </div>,
    size,
  );
}
