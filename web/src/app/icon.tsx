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
        background: "#172126",
        border: "2px solid #69d8d1",
        color: "#69d8d1",
        fontSize: "25px",
        lineHeight: 1,
      }}
    >
      +
    </div>,
    size,
  );
}
