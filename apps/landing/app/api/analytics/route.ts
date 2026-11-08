import { createCollectorIngestRoute } from "../../../lib/collector-ingest.ts";
import { parseConversionEvent } from "../../../lib/conversion-analytics.ts";

export const dynamic = "force-dynamic";

export const POST = createCollectorIngestRoute({
  parse: parseConversionEvent,
  collectorPath: "/analytics/events",
  unavailableKind: "analytics_storage_unavailable",
});
