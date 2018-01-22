#!/bin/bash

if [ -z "$GITHUB_TOKEN" ]; then
    echo "Error: set GITHUB_TOKEN environment variable with a github personal access token"
else

    PAGES_COUNT=$(curl -I -H "Authorization: token $GITHUB_TOKEN" -s 'https://api.github.com/orgs/hmrc/repos?type=private&per_page=100&page=1' | grep Link | perl -nle 'print $1 if /&page=(\d+)>; rel="last/')

    echo "Getting the list of private repositories in $PAGES_COUNT batches"
    rm /tmp/private-repositories

    for ((i=1; i<=$PAGES_COUNT; i++)); do
        curl -H "Authorization: token $GITHUB_TOKEN" -s "https://api.github.com/orgs/hmrc/repos?type=private&per_page=100&page=$i" | jq .[] | jq -r '.name' >> /tmp/private-repositories
    done

    while read repository; do
      echo "Scanning $repository"
      LEAK_COUNT=$(curl -s "http://localhost:8855/admin/validate/private/$repository/master"  | jq '.inspectionResults | length')
      echo "$LEAK_COUNT potential problems found on $repository"
      echo ""
    done </tmp/private-repositories

fi
