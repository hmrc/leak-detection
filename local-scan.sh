#!/bin/sh
ARTIFACTORY_BASE=${ARTIFACTORY_URL:-https://artefacts.tax.service.gov.uk/artifactory}
REPOSITORY=${ARTIFACTORY_BASE}/hmrc-releases
LATEST=$(curl -s "$ARTIFACTORY_BASE/api/search/latestVersion?g=uk.gov.hmrc&a=leak-detection_3")

echo "Using leak-detection: $LATEST"

DIR=$(dirname "$0")

scala-cli --repository $REPOSITORY --dependency "uk.gov.hmrc::leak-detection:$LATEST" -deprecation "${DIR}/local/LocalScan.scala" -- $@
