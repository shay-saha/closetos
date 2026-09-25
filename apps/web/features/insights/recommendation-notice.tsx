import type { EmbeddingCoverage } from "./recommendation-types";

export function ComparisonNotice({
  coverage,
  photoOnly = false,
}: {
  coverage: EmbeddingCoverage;
  photoOnly?: boolean;
}) {
  if (coverage.state === "READY" || coverage.state === "NOT_NEEDED") return null;
  return (
    <p className="processing-notice" role="status">
      {coverage.state === "UNAVAILABLE"
        ? photoOnly
          ? "Photo comparisons are temporarily unavailable. Refresh to try again."
          : "Some comparisons are temporarily unavailable. Suggestions use your recorded details and history."
        : `${coverage.indexedCount} of ${coverage.eligibleCount} pieces are prepared for comparisons. Suggestions will update as the remaining pieces are prepared.`}
    </p>
  );
}
export function RecommendationReasons({ reasons }: { reasons: string[] }) {
  return (
    <details className="recommendation-reasons">
      <summary>Why these pieces?</summary>
      <ul>
        {reasons.map((reason) => (
          <li key={reason}>{reason}</li>
        ))}
      </ul>
    </details>
  );
}
