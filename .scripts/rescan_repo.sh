#!/usr/bin/env bash

set -e

usage() {
    echo
    echo "Triggers LDS rescan of all branches for a repository"
    echo
    echo "Please provide the following parameters: "
    echo "1. repo name"
    echo "2. location of a file to store progress"
    echo
}

if [[ -z "$GITHUB_TOKEN" ]]; then
    echo "Error: please set GITHUB_TOKEN environment variable with a github personal access token"
    exit 1
fi

if [[ "$#" -ne 2 ]]; then
  usage
  exit 1
fi

REPO_NAME="$1"
REFRESH_PROGRESS_FILE="$2"
TMP_REPO_DIR="/tmp/repos"
echo "Initiating rescan for all branches for repo: ${REPO_NAME}"

rm -rf "${TMP_REPO_DIR:?"Error: 'TMP_REPO_DIR' variable not set."}/${REPO_NAME}"
mkdir -p "${TMP_REPO_DIR}"
touch "${TMP_REPO_DIR}/${REPO_NAME}"

# Test access to GitHub directly
HTTP_STATUS_CODE_GITHUB=$(curl --silent -i -H "Authorization: token ${GITHUB_TOKEN}" https://api.github.com/user | grep "HTTP/.* 200 OK")
if [[ "${HTTP_STATUS_CODE_GITHUB}" == "" ]]; then
    echo "Test to GitHub failed. HTTP status code is ${HTTP_STATUS_CODE}"
    exit 1
fi

# Github returns paginated results for branches
PAGES_COUNT=$(curl -s -I -H "Authorization: token $GITHUB_TOKEN" "https://api.github.com/repos/hmrc/$REPO_NAME/branches?per_page=100&page=1" | grep 'Link:' | perl -nle 'print $1 if /&page=(\d+)>; rel="last/' )

if [[ -z "${PAGES_COUNT}" ]]; then
    PAGES_COUNT=1
fi

for ((i=1; i <= PAGES_COUNT; i++)); do
    echo "Getting $i / $PAGES_COUNT page of branches..."
    curl -s -H "Authorization: token $GITHUB_TOKEN" -s "https://api.github.com/repos/hmrc/$REPO_NAME/branches?per_page=100&page=$i" |  jq .[] | jq -r '.name' \
        >> "${TMP_REPO_DIR}/${REPO_NAME}"
done

IS_PRIVATE=$(curl -s -H "Authorization: token $GITHUB_TOKEN" "https://api.github.com/repos/hmrc/$REPO_NAME" | jq '.private')
REPO_TYPE=private
if [ "$IS_PRIVATE" == "false" ]; then
    REPO_TYPE=public
fi

# Test access to a repository
read -r TEST_BRANCH < "${TMP_REPO_DIR}/${REPO_NAME}"
HTTP_STATUS_CODE_LDS=$(curl -s "localhost:8855/admin/validate/${REPO_TYPE}/${REPO_NAME}/${TEST_BRANCH}" | jq '.statusCode')
if [[ "${HTTP_STATUS_CODE_LDS}" != "null" ]]; then # It looks like LDS only return status code if there was an issue.
    echo "Potential issue. HTTP status code as returned from GitHub to LDS is ${HTTP_STATUS_CODE_LDS}"
    exit 1
fi

echo
echo "$REPO_NAME is $REPO_TYPE"
echo

BRANCHES_COUNT=$(wc -l "${TMP_REPO_DIR}/${REPO_NAME}")

i=1
while read -r BRANCH; do

    if grep -q "^${REPO_NAME}/${BRANCH}$" "${REFRESH_PROGRESS_FILE}"; then
        echo "skipping ${REPO_NAME}/${BRANCH} as already processed"
        ((i++))
    else
        echo "$i / ${BRANCHES_COUNT} Scanning branch: ${BRANCH} of repo: ${REPO_NAME}"
        LEAK_COUNT=$(curl -s "localhost:8855/admin/validate/${REPO_TYPE}/${REPO_NAME}/${BRANCH}"  | jq '.inspectionResults | length')

        if [[ -z "${LEAK_COUNT}" ]]; then
            echo
            echo "Calling LDS failed :("
            echo
            exit 1
        fi

        echo "${LEAK_COUNT} potential problems found on ${BRANCH}"
        echo ""
        echo "${REPO_NAME}/${BRANCH}" >> "${REFRESH_PROGRESS_FILE}"
        ((i++))
    fi

done < "${TMP_REPO_DIR}/${REPO_NAME}"
