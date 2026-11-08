import { createCollectorIngestRoute } from "../../../lib/collector-ingest.ts";
import { parseQualityEvent } from "../../../lib/experience-quality.ts";

export const dynamic = "force-dynamic";

export const POST = createCollectorIngestRoute({
  parse: parseQualityEvent,
  collectorPath: "/experience-quality",
  unavailableKind: "experience_quality_storage_unavailable",
});
