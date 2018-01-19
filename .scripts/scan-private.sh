#!/bin/bash

if [ -z "$GITHUB_TOKEN" ]; then
    echo "Error: set GITHUB_TOKEN environment variable with a github personal access token"
else

    curl -H "Authorization: token $GITHUB_TOKEN" -s 'https://api.github.com/orgs/hmrc/repos?type=private&per_page=100' | jq .[] | jq -r '.name' > /tmp/private-repositories
    curl -H "Authorization: token $GITHUB_TOKEN" -s 'https://api.github.com/orgs/hmrc/repos?type=private&per_page=100&page=2' | jq .[] | jq -r '.name' >> /tmp/private-repositories
    curl -H "Authorization: token $GITHUB_TOKEN" -s 'https://api.github.com/orgs/hmrc/repos?type=private&per_page=100&page=3' | jq .[] | jq -r '.name' >> /tmp/private-repositories
    curl -H "Authorization: token $GITHUB_TOKEN" -s 'https://api.github.com/orgs/hmrc/repos?type=private&per_page=100&page=4' | jq .[] | jq -r '.name' >> /tmp/private-repositories

    while read repository; do
      echo ""
      echo ""
      echo "Scanning $repository"
      curl -s -o /dev/null -w "%{http_code}" "http://localhost:8855/admin/validate/private/$repository/master"

    done </tmp/private-repositories

fi
