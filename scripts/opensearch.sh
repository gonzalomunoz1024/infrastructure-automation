#!/usr/bin/env sh
# A local single-node OpenSearch for the indexer, with no Docker: the integ-test distribution from
# Maven Central plus the analysis-common and reindex modules (./gradlew opensearchDist), plain HTTP
# on 127.0.0.1:9200, data and logs under build/. OpenSearch refuses to run as root; run this as
# yourself. With Docker, deploy/compose/docker-compose.yaml starts the image instead.
#
#   scripts/opensearch.sh                 run it in the foreground (Ctrl-C stops it)
#   OPENSEARCH_PORT=9201 scripts/opensearch.sh
#
# Then run the indexer against it:
#   CONSOLE_SEARCH_ENGINE=opensearch CONSOLE_OPENSEARCH_URL=http://localhost:9200 scripts/demo.sh
set -eu

VERSION=2.19.6
PORT="${OPENSEARCH_PORT:-9200}"
cd "$(dirname "$0")/.."
HOME_DIR="build/opensearch/opensearch-$VERSION"

if [ "$(id -u)" = "0" ]; then
  echo "OpenSearch does not run as root; run this script as another user" >&2
  exit 1
fi
if [ ! -x "$HOME_DIR/bin/opensearch" ] || [ ! -d "$HOME_DIR/modules/analysis-common" ]; then
  echo "assembling OpenSearch $VERSION into build/opensearch"
  ./gradlew opensearchDist -q
fi
JAVA_DIR="${OPENSEARCH_JAVA_HOME:-${JAVA_HOME:-}}"
if [ -z "$JAVA_DIR" ]; then
  echo "set JAVA_HOME (Java 21) so OpenSearch finds a JDK" >&2
  exit 1
fi
mkdir -p build/opensearch-data build/opensearch-logs
echo "OpenSearch $VERSION on http://127.0.0.1:$PORT (security off, single node)"
exec env OPENSEARCH_JAVA_HOME="$JAVA_DIR" OPENSEARCH_JAVA_OPTS="${OPENSEARCH_JAVA_OPTS:--Xms512m -Xmx512m}" "$HOME_DIR/bin/opensearch" \
  -Epath.data="$PWD/build/opensearch-data" -Epath.logs="$PWD/build/opensearch-logs" \
  -Ediscovery.type=single-node -Enetwork.host=127.0.0.1 -Ehttp.port="$PORT"
