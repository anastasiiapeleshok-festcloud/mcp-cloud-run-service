#!/usr/bin/env bash
# Run this YOURSELF (not me) — it creates real, billed GCP resources under your account.
# Fill in PROJECT_ID below first.
set -euo pipefail

PROJECT_ID="gcp-srv-nprd"        # <-- fill in
REGION="europe-west4"
REPO_NAME="mcp-sandbox-service"
SERVICE_NAME="mcp-sandbox-service"
IMAGE="${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPO_NAME}/sandbox-service:v1"

echo "== 0. Auth + project (skip if already done) =="
gcloud auth login
gcloud config set project "${PROJECT_ID}"

echo "== 1. Enable required APIs (one-time) =="
gcloud services enable run.googleapis.com artifactregistry.googleapis.com

echo "== 2. Create a DOCKER-format Artifact Registry repo (one-time) =="
gcloud artifacts repositories create "${REPO_NAME}" \
  --repository-format=docker --location="${REGION}" \
  --description="MCP sandbox-service images" || echo "(already exists, continuing)"

echo "== 3. Configure docker to auth against Artifact Registry =="
gcloud auth configure-docker "${REGION}-docker.pkg.dev"

echo "== 4. Build and push the sandbox-service image =="
cd sandbox-service
docker build -t "${IMAGE}" .
docker push "${IMAGE}"
cd ..

echo "== 5. Deploy the Cloud Run SERVICE =="
# --concurrency=1        : one session's MCP subprocess per container, ever — the whole
#                           point of this variant (see engine's SandboxSessionManager doc).
# --session-affinity     : Cloud Run sets a cookie and best-effort-routes a session's
#                           later calls back to the SAME instance — this is what lets a
#                           second tool call in one turn reuse the first call's MCP
#                           subprocess instead of spawning a new one.
# --execution-environment=gen2 : needed for full subprocess/syscall support (npx spawn).
# --timeout               : per-REQUEST budget. /session/start can take up to ~60s on a
#                           cold instance (cold start + npx resolving the package with no
#                           local cache) — see SandboxSessionManager's START_TIMEOUT.
# --min-instances=0 + EXIT_AFTER_SESSION=true : ACTIVE — always-cold mode. Matches the
#                           Job-parity isolation guarantee ("one sandbox = one invocation"):
#                           the instance self-exits after every /session/close, so the
#                           NEXT session always cold-starts fresh — no idle billing, but
#                           no warm-reuse either. This is the config being measured for the
#                           "not keeping warm" row in the report.
#                           To go back to warm-reuse: set --min-instances=1 and
#                           EXIT_AFTER_SESSION=false (see the commented block below).
# --no-allow-unauthenticated : Engine must call this with a Google ID token (see engine's
#                           README step 2b). Swap to --allow-unauthenticated for the very
#                           first smoke test if you want one less moving part up front.
gcloud run deploy "${SERVICE_NAME}" \
  --image="${IMAGE}" \
  --region="${REGION}" \
  --memory=512Mi --cpu=1 \
  --concurrency=1 \
  --session-affinity \
  --execution-environment=gen2 \
  --timeout=120s \
  --min-instances=0 \
  --max-instances=10 \
  --set-env-vars=EXIT_AFTER_SESSION=true \
  --allow-unauthenticated

  # --- WARM-REUSE: trades the Job-parity isolation guarantee for eliminating cold ---
  # --- starts on sequential sessions — see chat history for the isolation trade-off ---
  # --min-instances=1 \
  # --max-instances=10 \
  # --set-env-vars=EXIT_AFTER_SESSION=false \
  # --allow-unauthenticated

SERVICE_URL=$(gcloud run services describe "${SERVICE_NAME}" --region="${REGION}" --format='value(status.url)')
echo "== Done. Service URL: ${SERVICE_URL} =="
echo "Use this as SANDBOX_SERVICE_URL when running Engine."
echo
echo "If you deployed with --no-allow-unauthenticated (the default above), grant your own"
echo "Application Default Credentials principal permission to call it:"
echo "  gcloud run services add-iam-policy-binding ${SERVICE_NAME} --region=${REGION} \\"
echo "    --member=\"user:\$(gcloud config get-value account)\" --role=\"roles/run.invoker\""
echo "...then set sandbox.require-auth-token=true (or SANDBOX_REQUIRE_AUTH=true) for Engine."
