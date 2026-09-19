import type { Category, GarmentMetadata } from "./types";

const shapes: Record<Category, string> = {
  TOP: "M38 42 65 30 85 44 105 30 132 42 157 83 130 99 118 76 120 178 50 178 52 76 40 99 13 83Z",
  DRESS: "M60 36 76 45 94 45 110 36 116 94 145 193 25 193 54 94Z",
  BOTTOM: "M47 43 123 43 117 100 115 193 87 193 83 106 78 193 48 193 44 100Z",
  OUTERWEAR:
    "M44 42 69 30 85 53 101 30 126 42 149 74 137 183 116 183 113 76 111 195 59 195 57 76 54 183 33 183 21 74Z",
  SHOES: "M24 126 52 126 68 99 94 112 102 140 142 157 148 176 23 176Z",
  BAG: "M34 84 136 84 146 177 24 177Z M57 84 57 57 Q85 26 113 57 L113 84 102 84 102 60 Q85 41 68 60 L68 84Z",
  JEWELLERY: "M85 50 A47 47 0 1 0 86 50 L85 61 A36 36 0 1 1 84 61Z M85 148 102 166 85 187 68 166Z",
  ACCESSORY: "M38 50 87 35 109 55 87 82 126 169 93 186 64 105 43 145 17 129 56 76Z",
  OTHER: "M37 62 Q85 35 133 62 L140 176 Q85 194 30 176Z",
};

export function GarmentArt({
  garment,
  hanger = false,
}: {
  garment: Pick<GarmentMetadata, "category" | "primaryColourHex">;
  hanger?: boolean;
}) {
  const hanging = ["TOP", "DRESS", "OUTERWEAR", "BOTTOM"].includes(garment.category);
  return (
    <div className="piece-art" aria-hidden="true">
      <svg
        viewBox="0 0 170 220"
        fill="none"
        preserveAspectRatio={hanger ? "xMidYMin" : "xMidYMid meet"}
      >
        {hanger && hanging && (
          <g stroke="var(--muted)" strokeWidth="1.5">
            <path d="M84 10C84 0 99 0 99 10C99 19 85 15 85 26L135 43H35L85 26" />
            {garment.category === "BOTTOM" && <path d="M49 40V54M120 40V54" strokeWidth="4" />}
          </g>
        )}
        <path
          d={shapes[garment.category]}
          fill={garment.primaryColourHex ?? "#a5a48f"}
          stroke="var(--ink)"
          strokeOpacity=".1"
          fillRule="evenodd"
        />
      </svg>
      <span className="manual-label">Photo not added</span>
    </div>
  );
}
