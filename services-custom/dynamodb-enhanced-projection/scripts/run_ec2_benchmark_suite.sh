#!/usr/bin/env bash
set -euo pipefail

# Runs one reproducible, validated DynamoDB benchmark suite. Source tables must already contain
# CUSTOMER_COUNT customers and ORDERS_PER_CUSTOMER orders for each customer.

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "${SCRIPT_DIR}/../../.." && pwd)

require_env() {
  local name=$1
  if [[ -z ${!name:-} ]]; then
    echo "${name} must be set" >&2
    exit 2
  fi
}

require_env AWS_REGION
require_env INSTANCE_TYPE
require_env BENCHMARK_RUN_ID
require_env BENCHMARK_OUTPUT_ROOT
require_env CUSTOMERS_TABLE
require_env ORDERS_TABLE

export CUSTOMER_COUNT=${CUSTOMER_COUNT:-1000}
export ORDERS_PER_CUSTOMER=${ORDERS_PER_CUSTOMER:-1000}
export BENCHMARK_WARMUP=${BENCHMARK_WARMUP:-3}
export BENCHMARK_ITERATIONS=${BENCHMARK_ITERATIONS:-10}
export DYNAMODB_BILLING_MODE=${DYNAMODB_BILLING_MODE:-PAY_PER_REQUEST}
export BENCHMARK_SCENARIO_CATALOG="${REPO_ROOT}/services-custom/dynamodb-enhanced-projection/benchmark-scenarios.json"

RUN_DIR="${BENCHMARK_OUTPUT_ROOT%/}/${BENCHMARK_RUN_ID}"
if [[ -e ${RUN_DIR} ]]; then
  echo "Refusing to overwrite existing benchmark run directory: ${RUN_DIR}" >&2
  exit 2
fi
mkdir -p "${RUN_DIR}"

SAFE_RUN_ID=${BENCHMARK_RUN_ID//[^A-Za-z0-9_.-]/-}
export PROJECTION_SUMMARY_TABLE=${PROJECTION_SUMMARY_TABLE:-"OrdersByCustomer-${SAFE_RUN_ID}"}
export PROJECTION_SUMMARY_REGION_TABLE=${PROJECTION_SUMMARY_REGION_TABLE:-"OrdersByCustomerRegion-${SAFE_RUN_ID}"}
export PROJECTION_GLOBAL_TABLE=${PROJECTION_GLOBAL_TABLE:-"OrdersGlobal-${SAFE_RUN_ID}"}
export PROJECTION_JOIN_TABLE_INNER=${PROJECTION_JOIN_TABLE_INNER:-"CustomersOrdersJoinInner-${SAFE_RUN_ID}"}
export PROJECTION_JOIN_TABLE_LEFT=${PROJECTION_JOIN_TABLE_LEFT:-"CustomersOrdersJoinLeft-${SAFE_RUN_ID}"}
export PROJECTION_JOIN_TABLE_RIGHT=${PROJECTION_JOIN_TABLE_RIGHT:-"CustomersOrdersJoinRight-${SAFE_RUN_ID}"}
export PROJECTION_JOIN_TABLE_FULL=${PROJECTION_JOIN_TABLE_FULL:-"CustomersOrdersJoinFull-${SAFE_RUN_ID}"}

EQ_CSV="${RUN_DIR}/Enhanced Queries Read Benchmark - ${CUSTOMER_COUNT} Customers x ${ORDERS_PER_CUSTOMER} Orders.csv"
SP_CSV="${RUN_DIR}/Stream Projections Read Benchmark - ${CUSTOMER_COUNT} Customers x ${ORDERS_PER_CUSTOMER} Orders.csv"
MATERIALIZATION_CSV="${RUN_DIR}/Stream Projections Materialization Benchmark - ${CUSTOMER_COUNT} Customers x ${ORDERS_PER_CUSTOMER} Orders.csv"

aws dynamodb describe-table --table-name "${CUSTOMERS_TABLE}" --region "${AWS_REGION}" >/dev/null
aws dynamodb describe-table --table-name "${ORDERS_TABLE}" --region "${AWS_REGION}" >/dev/null
if [[ ${DYNAMODB_BILLING_MODE} != PAY_PER_REQUEST ]]; then
  echo "The validated suite currently supports only DYNAMODB_BILLING_MODE=PAY_PER_REQUEST." >&2
  exit 2
fi

ensure_absent() {
  local table_name=$1
  if aws dynamodb describe-table --table-name "${table_name}" --region "${AWS_REGION}" >/dev/null 2>&1; then
    echo "Projection target ${table_name} already exists. Use a new BENCHMARK_RUN_ID or explicit new target table names." >&2
    exit 2
  fi
}

create_simple_table() {
  local table_name=$1
  shift
  aws dynamodb create-table --table-name "${table_name}" --billing-mode "${DYNAMODB_BILLING_MODE}" \
    --region "${AWS_REGION}" "$@" >/dev/null
  aws dynamodb wait table-exists --table-name "${table_name}" --region "${AWS_REGION}"
}

for table_name in "${PROJECTION_SUMMARY_TABLE}" "${PROJECTION_SUMMARY_REGION_TABLE}" "${PROJECTION_GLOBAL_TABLE}" \
  "${PROJECTION_JOIN_TABLE_INNER}" "${PROJECTION_JOIN_TABLE_LEFT}" "${PROJECTION_JOIN_TABLE_RIGHT}" "${PROJECTION_JOIN_TABLE_FULL}"; do
  ensure_absent "${table_name}"
done

create_simple_table "${PROJECTION_SUMMARY_TABLE}" \
  --attribute-definitions AttributeName=customerId,AttributeType=S AttributeName=gsiPk,AttributeType=S AttributeName=totalAmount,AttributeType=N \
  --key-schema AttributeName=customerId,KeyType=HASH \
  --global-secondary-indexes 'IndexName=byTotalAmount,KeySchema=[{AttributeName=gsiPk,KeyType=HASH},{AttributeName=totalAmount,KeyType=RANGE}],Projection={ProjectionType=ALL}'
create_simple_table "${PROJECTION_SUMMARY_REGION_TABLE}" \
  --attribute-definitions AttributeName=customerId,AttributeType=S AttributeName=region,AttributeType=S \
  --key-schema AttributeName=customerId,KeyType=HASH AttributeName=region,KeyType=RANGE
create_simple_table "${PROJECTION_GLOBAL_TABLE}" \
  --attribute-definitions AttributeName=pk,AttributeType=S --key-schema AttributeName=pk,KeyType=HASH
for table_name in "${PROJECTION_JOIN_TABLE_INNER}" "${PROJECTION_JOIN_TABLE_LEFT}" "${PROJECTION_JOIN_TABLE_RIGHT}" "${PROJECTION_JOIN_TABLE_FULL}"; do
  create_simple_table "${table_name}" \
    --attribute-definitions AttributeName=customerId,AttributeType=S AttributeName=orderId,AttributeType=S \
    --key-schema AttributeName=customerId,KeyType=HASH AttributeName=orderId,KeyType=RANGE
done

cd "${REPO_ROOT}"
mvn -pl services-custom/dynamodb-enhanced,services-custom/dynamodb-enhanced-projection -am \
  -DskipTests -Dmaven.javadoc.skip=true -Dcheckstyle.skip=true -Dspotbugs.skip=true install

export BENCHMARK_OUTPUT_FILE="${EQ_CSV}"
export SEED_BENCHMARK_EXTENSIONS=true
echo "Running Enhanced Queries read benchmark..."
mvn -q -pl services-custom/dynamodb-enhanced exec:java \
  -Dexec.mainClass=software.amazon.awssdk.enhanced.dynamodb.functionaltests.query.EnhancedQueryBenchmarkRunner \
  -Dexec.classpathScope=test

export BENCHMARK_BACKEND=dynamodb
export BENCHMARK_BULK_SEED=true
export BENCHMARK_DDB_WRITE_PARALLELISM=${BENCHMARK_DDB_WRITE_PARALLELISM:-8}
export BENCHMARK_DDB_WRITE_PAUSE_MS=${BENCHMARK_DDB_WRITE_PAUSE_MS:-0}
export BENCHMARK_OUTPUT_FILE="${SP_CSV}"
export BENCHMARK_MATERIALIZATION_OUTPUT_FILE="${MATERIALIZATION_CSV}"
echo "Running Stream Projections materialization and read benchmark..."
mvn -pl services-custom/dynamodb-enhanced-projection exec:java \
  -Dexec.mainClass=software.amazon.awssdk.enhanced.dynamodb.functionaltests.ProjectionBenchmarkRunner \
  -Dexec.classpathScope=test

echo "Generating benchmark comparison reports..."
python3 services-custom/dynamodb-enhanced-projection/scripts/compare_benchmarks.py \
  "${EQ_CSV}" "${SP_CSV}" \
  --out-dir "${RUN_DIR}" \
  --catalog "${BENCHMARK_SCENARIO_CATALOG}" \
  --materialization-csv "${MATERIALIZATION_CSV}"

GIT_REVISION=$(git rev-parse --short HEAD)
printf '{\n  "runId": "%s",\n  "gitRevision": "%s",\n  "region": "%s",\n  "instanceType": "%s",\n  "billingMode": "%s",\n  "customerCount": %s,\n  "ordersPerCustomer": %s,\n  "warmupIterations": %s,\n  "measuredIterations": %s,\n  "resultStatus": "PASS"\n}\n' \
  "${BENCHMARK_RUN_ID}" "${GIT_REVISION}" "${AWS_REGION}" "${INSTANCE_TYPE}" "${DYNAMODB_BILLING_MODE}" \
  "${CUSTOMER_COUNT}" "${ORDERS_PER_CUSTOMER}" "${BENCHMARK_WARMUP}" "${BENCHMARK_ITERATIONS}" \
  > "${RUN_DIR}/Benchmark Run Manifest.json"

echo "Validated benchmark artifacts are in ${RUN_DIR}"
