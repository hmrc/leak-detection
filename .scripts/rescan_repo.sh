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

if [ -z "$GITHUB_TOKEN" ]; then
    echo "Error: please set GITHUB_TOKEN environment variable with a github personal access token"
    exit 1
fi

if [ "$#" -ne 2 ]
then
  usage
  exit 1
fi

REPO_NAME="$1"
REFRESH_PROGRESS_FILE="$2"
echo "Initiating rescan for all branches for repo: $REPO_NAME"

rm -rf /tmp/repos/$REPO_NAME
mkdir -p /tmp/repos
touch /tmp/repos/$REPO_NAME

# Github returns paginated results for branches
PAGES_COUNT=$(curl -s -I -H "Authorization: token $GITHUB_TOKEN" "https://api.github.com/repos/hmrc/$REPO_NAME/branches?per_page=100&page=1" | grep 'Link:' | perl -nle 'print $1 if /&page=(\d+)>; rel="last/' )

if [ -z $PAGES_COUNT ]; then
    PAGES_COUNT=1
fi

for ((i=1; i <= $PAGES_COUNT; i++)); do
    echo "Getting $i / $PAGES_COUNT page of branches..."
    curl -s -H "Authorization: token $GITHUB_TOKEN" -s "https://api.github.com/repos/hmrc/$REPO_NAME/branches?per_page=100&page=$i" |  jq .[] | jq -r '.name' \
        >> "/tmp/repos/$REPO_NAME"
done

IS_PRIVATE=$(curl -s -H "Authorization: token $GITHUB_TOKEN" "https://api.github.com/repos/hmrc/$REPO_NAME" | jq '.private')
REPO_TYPE=private
if [ "$IS_PRIVATE" == "false" ]; then
    REPO_TYPE=public
fi

echo
echo "$REPO_NAME is $REPO_TYPE"
echo

BRANCHES_COUNT=$(cat "/tmp/repos/$REPO_NAME" | wc -l)

i=1
while read branch; do

    if grep -q "^$REPO_NAME/$branch$" $REFRESH_PROGRESS_FILE; then
        echo "skipping $REPO_NAME/$branch as already processed"
        ((i++))
    else
        echo "$i / $BRANCHES_COUNT Scanning branch: $branch of repo: $REPO_NAME"
        LEAK_COUNT=$(curl -s "localhost:8855/admin/validate/$REPO_TYPE/$REPO_NAME/$branch"  | jq '.inspectionResults | length')

        if [ -z $LEAK_COUNT ]; then
            echo
            echo "Calling LDS failed :("
            echo
            exit 1
        fi

        echo "$LEAK_COUNT potential problems found on $branch"
        echo ""
        echo "$REPO_NAME/$branch" >> $REFRESH_PROGRESS_FILE
        ((i++))
    fi

done < "/tmp/repos/$REPO_NAME"




