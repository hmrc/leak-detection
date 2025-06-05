#!/bin/sh
REPOSITORY=${ARTIFACTORY_URL:-https://artefacts.tax.service.gov.uk/artifactory}/hmrc-releases
scala-cli --repository $REPOSITORY -deprecation local/LocalScan.scala -- $@
