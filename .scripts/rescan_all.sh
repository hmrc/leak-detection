#!/usr/bin/env bash

set -e

usage() {
    echo
    echo "Triggers LDS rescan of multiple repositories."
    echo
    echo "Please provide the following parameters: "
    echo " 1. location of a text file with repos to scan (each repo on a separate line)"
    echo " 2. location of a file to store progress"
    echo
}

if [ "$#" -ne 2 ]
then
  usage
  exit 1
fi

REPOS="$1"
REFRESH_PROGRESS_FILE="$2"

echo $REPOS
echo $REFRESH_PROGRESS_FILE

echo
echo
echo "About to start processing all repos in: $REPOS"
echo "Progress will be saved in: $REFRESH_PROGRESS_FILE"
echo
echo

REPOS_COUNT=$(cat "$REPOS" | wc -l)

i=1

while read repo; do
  echo
  echo
  echo
  echo
  echo
  echo
  echo "$i / $REPOS_COUNT Triggering a scan for all branches of repo: $repo"
  ./rescan_repo.sh $repo $REFRESH_PROGRESS_FILE
  ((i++))
done < $REPOS

