# leak-detection

[ ![Download](https://api.bintray.com/packages/hmrc/releases/leak-detection/images/download.svg) ](https://bintray.com/hmrc/releases/leak-detection/_latestVersion)

## Overview
Service used to find leaks in git repositories using regular expressions.
It is worth noting here that as the service only runs periodically, leaked credentials might have already been found by unsavoury characters.  Education is the best tool not to leak secrets.
Further reading: https://blog.acolyer.org/2019/04/08/how-bad-can-it-git-characterizing-secret-leakage-in-public-github-repositories/?-characterizing-secret-leakage-in-public-github-repositories/

## Checks performed by Leak Detection Service
Currently, there are two kinds of checks performed by LDS:
* Check for occurrence of a regular expression from the predefined set of rules.
* Check for existence of `repository.yaml` file that contains unique fingerprint of public/private MDTP repository.

### Checking for existence of `repository.yaml` file.
This check is performed if a configuration parameter `alerts.slack.enabledForRepoVisibility` is set to true.
If the check is enabled, whenever commit is made to the repository that doesn't contain `repository.yaml` file or the file doesn't contain valid fingerprint, the alert will be sent.

### Warnings
LDS can optionally also alert against warnings.  To include an alert for a given warning, simply add it to the `alerts.slack.warningsToAlert` list in `/connf/application.conf`.

## Rules
The rules are defined within `/conf/application.conf` under the `allRules` section.  There is a set of `privateRules` which are executed on private repositories, and a set of `publicRules` which are executed on public repositories.

### Regular expression rules
LDS allows to create collection of rules that detect certain types of secrets that might be stored in a GIT repository. Each rule definition consist of set of properties. E.g.
```
 {
      id = "cert_1"
      scope = fileContent
      regex = """-----(BEGIN|END).*?PRIVATE.*?-----"""
      description = "certificates and private keys"
      ignoredFiles = ["^\\/.*phantomjs.*", "^\\/.*xxx.*", "^\\/.*Foo.*", """/\.bar\.yml"""]
      ignoredExtensions = ${allRules.knownBinaryFilesExtensions}
      priority = "low"
      draft = true
    },
```
In such case the rule applies to all non-binary files (with filename extension that is not of ignoredExtensions), which match specified regex.

## Rescanning repositories
Repositories can be scanned in either `normal` mode or `draft` mode.  If a mode is not provided then the rescan will run in `draft` mode by default.

### normal mode
Any warnings or potential leaks will be visible in the MDTP catalogue and alerts will be triggered.

Any rules marked as draft will be ignored.

### draft mode
No alerts will be triggered and the results of draft scans will be stored in a separate collection which can be accessed via the `/admin` endpoints:
* To retrieve all draft reports : GET `/admin/draft`
* To retrieve draft reports related to a particular rule : GET `/admin/draft?rule=:ruleId`
* To retrieve a single report : GET `/admin/draft/:reportId`
* To clear down existing reports : DELETE `/admin/draft`

All rules are checked during a draft run (not just the draft rules).

All rescan endpoints accept the `?mode=:[normal,draft]` query param:
* To rescan a single repositories branch : POST `/admin/rescan/:repository/:branch`
* To rescan multiple repositories (default branch only) : POST `/admin/rescan`

The repositories to scan must be provided as a JSON list e.g.:
```Json
  [
    "repo-a",
    "repo-b",
    "repo-c"
  ]
```
* To rescan all repositories : POST `/admin/rescan/all`

The list of repositories to scan will be retrieved from `teams-and-repositories`

## Amending rules
Applying the draft flag will allow to test any new or modified rules without impacting the live scans.

When modifying existing rules, first copy the current rule, give it a temporary id and mark it as a draft.  This way the existing rule will continue to be checked and the results can be compared.

Remember to clear down any existing draft reports before testing the amendments with the rescan endpoints.

Before the rule is signed off and the draft flag removed, it is recommended that the draft reports are cleared and at least one rescan of all repositories in draft mode is first performed.  This will give an indication of the number of violations that will be identified across HMRC's github account.

When a new or modified rule is introduced (i.e. the draft flag is removed), a rescan of all repositories in normal mode should also be performed on live but alerts should only be sent for the new rule (to avoid re-alerting teams to violations they are already aware of).

This can be done by passing the ruleId of the new rule as a query param `?rule=:ruleId` to the rescan all endpoint e.g.:

`/leak-detection/admin/rescan/all?mode=normal&rule=:ruleId`

This will ensure that teams will be alerted to any violations of the rule and that the offending code will be visible within the MDTP catalogue.    
Without performing a rescan all, any violations within the existing codebase will not be identified until changes are pushed up to the repository - the main issue with this approach is that not all repositories are actively being worked on.

**NOTE**: Please inform PlatSec before running a rescan in live so that they are aware that new Slack notifications will be going out.

## API endpoints
In production, the routes are mounted at both `/` and `/leak-detection`. The examples below use the `/leak-detection` prefix. For local development, the base URL is usually `http://localhost:8855`.

### PlatSec: fetching AWS secret access key leaks
Use `GET /leak-detection/api/leaks?ruleId=aws_secret_access_key` to fetch all current leak records for the `aws_secret_access_key` rule.

Sample request:
```http
GET /leak-detection/api/leaks?ruleId=aws_secret_access_key HTTP/1.1
Accept: application/json
```

Query parameters:

| Name | Required | Type | Description |
| --- | --- | --- | --- |
| `ruleId` | No | String | Rule id. Use `aws_secret_access_key` for AWS secret access key findings. |
| `repository` | No | String | Repository name, for example `my-service`. |
| `branch` | No | String | Branch name. This only applies when `repository` is also supplied. |

Sample response:
```json
[
  {
    "repoName": "my-service",
    "branch": "main",
    "timestamp": "2026-06-24T09:15:30.000Z",
    "reportId": "5b0d1d7e-2e3c-4a7f-91b4-208be8f8cf0a",
    "ruleId": "aws_secret_access_key",
    "description": "AWS secret key",
    "filePath": "/conf/application.conf",
    "scope": "fileContent",
    "lineNumber": 42,
    "urlToSource": "https://github.com/hmrc/my-service/blame/main/conf/application.conf#L42",
    "lineText": "aws_secret_access_key = <aws-secret-access-key-value>",
    "matches": [
      {
        "start": 4,
        "end": 55
      }
    ],
    "priority": "high",
    "isExcluded": false,
    "commitId": "1b2c3d4e5f6a7b8c9d0e"
  }
]
```

Fields needed for this use case:

| Field | Description |
| --- | --- |
| `repoName` | Repository containing the leak. |
| `branch` | Branch where the leak was detected. |
| `filePath` | File containing the leak. |
| `lineNumber` | Line number in the file. |
| `urlToSource` | GitHub blame URL for the detected line. |
| `lineText` | Source line, or a shortened line containing the matching text. This is not redacted by the API. |
| `matches` | Character offsets into `lineText`. Extract `lineText.substring(start, end)` to get the matched fragment. For `aws_secret_access_key`, the actual 40-character value is at the end of that matched fragment. |
| `isExcluded` | Whether the finding was excluded by an inline, file-level, service-defined, or rule-defined exemption. Consumers usually filter to `false` for active unresolved leaks. |
| `commitId` | Commit id used to build `urlToSource`, when blame information is available. |

Handle `lineText` and extracted match values as secrets.

### Common response shapes

#### Leak
```json
{
  "repoName": "my-service",
  "branch": "main",
  "timestamp": "2026-06-24T09:15:30.000Z",
  "reportId": "5b0d1d7e-2e3c-4a7f-91b4-208be8f8cf0a",
  "ruleId": "aws_secret_access_key",
  "description": "AWS secret key",
  "filePath": "/conf/application.conf",
  "scope": "fileContent",
  "lineNumber": 42,
  "urlToSource": "https://github.com/hmrc/my-service/blame/main/conf/application.conf#L42",
  "lineText": "aws_secret_access_key = <aws-secret-access-key-value>",
  "matches": [
    {
      "start": 4,
      "end": 55
    }
  ],
  "priority": "high",
  "isExcluded": false,
  "commitId": "1b2c3d4e5f6a7b8c9d0e"
}
```

#### Report
```json
{
  "_id": "5b0d1d7e-2e3c-4a7f-91b4-208be8f8cf0a",
  "repoName": "my-service",
  "repoUrl": "https://github.com/hmrc/my-service",
  "commitId": "1b2c3d4e5f6a7b8c9d0e",
  "branch": "main",
  "timestamp": "2026-06-24T09:15:30.000Z",
  "author": "github-user",
  "totalLeaks": 1,
  "totalWarnings": 0,
  "rulesViolated": {
    "aws_secret_access_key": 1
  },
  "exclusions": {},
  "unusedExemptions": []
}
```

#### Rule
```json
{
  "id": "aws_secret_access_key",
  "scope": "fileContent",
  "regex": "(SECRET|secret|Secret|ACCESS|access|Access|KEY|key|Key)...",
  "description": "AWS secret key",
  "ignoredFiles": [
    "^\\/.*phantomjs.*",
    "/repository.yaml"
  ],
  "ignoredExtensions": [
    ".zip",
    ".pdf"
  ],
  "ignoredContent": [],
  "priority": "high",
  "draft": false
}
```

#### Repository summary
```json
{
  "repository": "my-service",
  "isArchived": false,
  "firstScannedAt": "2026-06-20T12:00:00Z",
  "lastScannedAt": "2026-06-24T09:15:30Z",
  "warningCount": 0,
  "unresolvedCount": 1,
  "excludedCount": 0,
  "branchSummary": [
    {
      "branch": "main",
      "reportId": "5b0d1d7e-2e3c-4a7f-91b4-208be8f8cf0a",
      "scannedAt": "2026-06-24T09:15:30Z",
      "warningCount": 0,
      "unresolvedCount": 1,
      "excludedCount": 0
    }
  ]
}
```

### GET `/leak-detection/api/repository`
Returns repository names with unresolved leaks.

Query parameters: none.

Sample request:
```http
GET /leak-detection/api/repository HTTP/1.1
Accept: application/json
```

Sample response:
```json
[
  "my-service",
  "another-service"
]
```

### GET `/leak-detection/api/report/:id`
Returns a report by report id.

Path parameters:

| Name | Required | Type | Description |
| --- | --- | --- | --- |
| `id` | Yes | String | Report id. |

Query parameters: none.

Sample request:
```http
GET /leak-detection/api/report/5b0d1d7e-2e3c-4a7f-91b4-208be8f8cf0a HTTP/1.1
Accept: application/json
```

Sample response:
```json
{
  "_id": "5b0d1d7e-2e3c-4a7f-91b4-208be8f8cf0a",
  "repoName": "my-service",
  "repoUrl": "https://github.com/hmrc/my-service",
  "commitId": "1b2c3d4e5f6a7b8c9d0e",
  "branch": "main",
  "timestamp": "2026-06-24T09:15:30.000Z",
  "author": "github-user",
  "totalLeaks": 1,
  "totalWarnings": 0,
  "rulesViolated": {
    "aws_secret_access_key": 1
  },
  "exclusions": {},
  "unusedExemptions": []
}
```

If no report exists, the service returns `404` with `No report found.`

### GET `/leak-detection/api/report/:id/leaks`
Returns leak records belonging to a report.

Path parameters:

| Name | Required | Type | Description |
| --- | --- | --- | --- |
| `id` | Yes | String | Report id. |

Query parameters: none.

Sample request:
```http
GET /leak-detection/api/report/5b0d1d7e-2e3c-4a7f-91b4-208be8f8cf0a/leaks HTTP/1.1
Accept: application/json
```

Sample response:
```json
[
  {
    "repoName": "my-service",
    "branch": "main",
    "timestamp": "2026-06-24T09:15:30.000Z",
    "reportId": "5b0d1d7e-2e3c-4a7f-91b4-208be8f8cf0a",
    "ruleId": "aws_secret_access_key",
    "description": "AWS secret key",
    "filePath": "/conf/application.conf",
    "scope": "fileContent",
    "lineNumber": 42,
    "urlToSource": "https://github.com/hmrc/my-service/blame/main/conf/application.conf#L42",
    "lineText": "aws_secret_access_key = <aws-secret-access-key-value>",
    "matches": [
      {
        "start": 4,
        "end": 55
      }
    ],
    "priority": "high",
    "isExcluded": false,
    "commitId": "1b2c3d4e5f6a7b8c9d0e"
  }
]
```

### GET `/leak-detection/api/report/:id/warnings`
Returns warning records belonging to a report.

Path parameters:

| Name | Required | Type | Description |
| --- | --- | --- | --- |
| `id` | Yes | String | Report id. |

Query parameters: none.

Sample request:
```http
GET /leak-detection/api/report/5b0d1d7e-2e3c-4a7f-91b4-208be8f8cf0a/warnings HTTP/1.1
Accept: application/json
```

Sample response:
```json
[
  {
    "repoName": "my-service",
    "branch": "main",
    "timestamp": "2026-06-24T09:15:30Z",
    "reportId": "5b0d1d7e-2e3c-4a7f-91b4-208be8f8cf0a",
    "message": "The repository.yaml file contains unused exemptions"
  }
]
```

### GET `/leak-detection/api/leaks`
Returns leak records. Any combination of `repository` and `ruleId` can be used. `branch` is only applied when `repository` is also supplied.

Query parameters:

| Name | Required | Type | Description |
| --- | --- | --- | --- |
| `repository` | No | String | Repository name. |
| `branch` | No | String | Branch name. Only applies when `repository` is also supplied. |
| `ruleId` | No | String | Rule id, for example `aws_secret_access_key`. |

Sample request:
```http
GET /leak-detection/api/leaks?repository=my-service&branch=main&ruleId=aws_secret_access_key HTTP/1.1
Accept: application/json
```

Sample response:
```json
[
  {
    "repoName": "my-service",
    "branch": "main",
    "timestamp": "2026-06-24T09:15:30.000Z",
    "reportId": "5b0d1d7e-2e3c-4a7f-91b4-208be8f8cf0a",
    "ruleId": "aws_secret_access_key",
    "description": "AWS secret key",
    "filePath": "/conf/application.conf",
    "scope": "fileContent",
    "lineNumber": 42,
    "urlToSource": "https://github.com/hmrc/my-service/blame/main/conf/application.conf#L42",
    "lineText": "aws_secret_access_key = <aws-secret-access-key-value>",
    "matches": [
      {
        "start": 4,
        "end": 55
      }
    ],
    "priority": "high",
    "isExcluded": false,
    "commitId": "1b2c3d4e5f6a7b8c9d0e"
  }
]
```

### GET `/leak-detection/api/:repository/:branch/report`
Returns the latest report for a repository branch.

Path parameters:

| Name | Required | Type | Description |
| --- | --- | --- | --- |
| `repository` | Yes | String | Repository name. |
| `branch` | Yes | String | Branch name. URL-encode branch names containing `/`, for example `feature%2Fabc`. |

Query parameters: none.

Sample request:
```http
GET /leak-detection/api/my-service/main/report HTTP/1.1
Accept: application/json
```

Sample response:
```json
{
  "_id": "5b0d1d7e-2e3c-4a7f-91b4-208be8f8cf0a",
  "repoName": "my-service",
  "repoUrl": "https://github.com/hmrc/my-service",
  "commitId": "1b2c3d4e5f6a7b8c9d0e",
  "branch": "main",
  "timestamp": "2026-06-24T09:15:30.000Z",
  "author": "github-user",
  "totalLeaks": 1,
  "totalWarnings": 0,
  "rulesViolated": {
    "aws_secret_access_key": 1
  },
  "exclusions": {},
  "unusedExemptions": []
}
```

If no report exists, the service returns `404` with `No report found.`

### GET `/leak-detection/api/rules`
Returns all configured rules, combining private and public rules and removing duplicates.

Query parameters: none.

Sample request:
```http
GET /leak-detection/api/rules HTTP/1.1
Accept: application/json
```

Sample response:
```json
[
  {
    "id": "aws_secret_access_key",
    "scope": "fileContent",
    "regex": "(SECRET|secret|Secret|ACCESS|access|Access|KEY|key|Key)...",
    "description": "AWS secret key",
    "ignoredFiles": [
      "^\\/.*phantomjs.*",
      "/repository.yaml"
    ],
    "ignoredExtensions": [
      ".zip",
      ".pdf"
    ],
    "ignoredContent": [],
    "priority": "high",
    "draft": false
  }
]
```

### GET `/leak-detection/api/rules/summary`
Returns rule-first summaries. Each result contains a rule and the repositories with leaks for that rule.

Query parameters:

| Name | Required | Type | Description |
| --- | --- | --- | --- |
| `ruleId` | No | String | Restrict leaks to one rule id. The response still includes all rules, with empty `leaks` arrays for rules that have no matching leaks. |
| `repository` | No | String | Restrict leaks and warnings to one repository. |
| `team` | No | String | Restrict to repositories owned by a team according to teams-and-repositories. |
| `digitalService` | No | String | Restrict to repositories in a digital service according to teams-and-repositories. |

Sample request:
```http
GET /leak-detection/api/rules/summary?ruleId=aws_secret_access_key HTTP/1.1
Accept: application/json
```

Sample response:
```json
[
  {
    "rule": {
      "id": "aws_secret_access_key",
      "scope": "fileContent",
      "regex": "(SECRET|secret|Secret|ACCESS|access|Access|KEY|key|Key)...",
      "description": "AWS secret key",
      "ignoredFiles": [
        "^\\/.*phantomjs.*",
        "/repository.yaml"
      ],
      "ignoredExtensions": [
        ".zip",
        ".pdf"
      ],
      "ignoredContent": [],
      "priority": "high",
      "draft": false
    },
    "leaks": [
      {
        "repository": "my-service",
        "isArchived": false,
        "firstScannedAt": "2026-06-20T12:00:00Z",
        "lastScannedAt": "2026-06-24T09:15:30Z",
        "warningCount": 0,
        "unresolvedCount": 1,
        "excludedCount": 0,
        "branchSummary": null
      }
    ]
  }
]
```

### GET `/leak-detection/api/repositories/summary`
Returns repository-first summaries.

Query parameters:

| Name | Required | Type | Description |
| --- | --- | --- | --- |
| `ruleId` | No | String | Restrict leak counts to one rule id. |
| `repository` | No | String | Restrict to one repository. |
| `team` | No | String | Restrict to repositories owned by a team according to teams-and-repositories. |
| `digitalService` | No | String | Restrict to repositories in a digital service according to teams-and-repositories. |
| `excludeNonIssues` | No | Boolean | Defaults to `false`. When `true`, repositories with no leaks or warnings are excluded. |
| `includeBranches` | No | Boolean | Defaults to `false`. When `true`, includes per-branch summaries in `branchSummary`. |

Sample request:
```http
GET /leak-detection/api/repositories/summary?ruleId=aws_secret_access_key&includeBranches=true&excludeNonIssues=true HTTP/1.1
Accept: application/json
```

Sample response:
```json
[
  {
    "repository": "my-service",
    "isArchived": false,
    "firstScannedAt": "2026-06-20T12:00:00Z",
    "lastScannedAt": "2026-06-24T09:15:30Z",
    "warningCount": 0,
    "unresolvedCount": 1,
    "excludedCount": 0,
    "branchSummary": [
      {
        "branch": "main",
        "reportId": "5b0d1d7e-2e3c-4a7f-91b4-208be8f8cf0a",
        "scannedAt": "2026-06-24T09:15:30Z",
        "warningCount": 0,
        "unresolvedCount": 1,
        "excludedCount": 0
      }
    ]
  }
]
```

### POST `/leak-detection/validate`
Accepts GitHub push and repository webhook payloads. The service queues push updates, clears records for deleted branches, and clears or rescans records for archived or deleted repositories.

Query parameters: none.

Request body:

| Event type | Required fields |
| --- | --- |
| Push update | `deleted=false`, `repository.name`, `repository.private`, `repository.archived`, `repository.url`, `repository.archive_url`, `pusher.name`, `ref`, `after` |
| Push delete | `deleted=true`, `repository.name`, `repository.url`, `pusher.name`, `ref` |
| Repository event | `repository.name`, `action` |

Sample request:
```http
POST /leak-detection/validate HTTP/1.1
Content-Type: application/json
Accept: application/json

{
  "deleted": false,
  "ref": "refs/heads/main",
  "after": "1b2c3d4e5f6a7b8c9d0e",
  "repository": {
    "name": "my-service",
    "private": true,
    "archived": false,
    "url": "https://github.com/hmrc/my-service",
    "archive_url": "https://api.github.com/repos/hmrc/my-service/{archive_format}{/ref}"
  },
  "pusher": {
    "name": "github-user"
  }
}
```

Sample response:
```json
{
  "details": "Request successfully queued"
}
```

Other successful `details` values include `Tag commit ignored`, `Duplicate request ignored`, `<repo>/<branch> deleted`, `<repo> archived`, `<repo> deleted`, and `Repository events with <action> actions are ignored`.

### Admin endpoints

#### GET `/leak-detection/admin/github-rate-limits`
Returns the GitHub `/rate_limit` response fetched using the configured GitHub token.

Query parameters: none.

Sample request:
```http
GET /leak-detection/admin/github-rate-limits HTTP/1.1
Accept: application/json
```

Sample response:
```json
{
  "resources": {
    "core": {
      "limit": 5000,
      "used": 120,
      "remaining": 4880,
      "reset": 1782295200
    }
  },
  "rate": {
    "limit": 5000,
    "used": 120,
    "remaining": 4880,
    "reset": 1782295200
  }
}
```

#### GET `/leak-detection/admin/stats`
Returns current leak metrics.

Query parameters: none.

Sample request:
```http
GET /leak-detection/admin/stats HTTP/1.1
Accept: application/json
```

Sample response:
```json
{
  "reports.total": 12,
  "reports.unresolved": 12,
  "reports.teams.platform_security.unresolved": 3
}
```

#### GET `/leak-detection/admin/draft`
Returns draft reports.

Query parameters:

| Name | Required | Type | Description |
| --- | --- | --- | --- |
| `rule` | No | String | If absent, returns all draft reports. Use `any` for draft reports with at least one violation. Use a rule id, for example `aws_secret_access_key`, for draft reports with that rule in `rulesViolated` or `exclusions`. Unknown rule ids return `400 Unknown ruleId`. |

Sample request:
```http
GET /leak-detection/admin/draft?rule=aws_secret_access_key HTTP/1.1
Accept: application/json
```

Sample response:
```json
[
  {
    "_id": "5b0d1d7e-2e3c-4a7f-91b4-208be8f8cf0a",
    "repoName": "my-service",
    "repoUrl": "https://github.com/hmrc/my-service",
    "commitId": "1b2c3d4e5f6a7b8c9d0e",
    "branch": "main",
    "timestamp": "2026-06-24T09:15:30.000Z",
    "author": "n/a",
    "totalLeaks": 1,
    "totalWarnings": 0,
    "rulesViolated": {
      "aws_secret_access_key": 1
    },
    "exclusions": {},
    "unusedExemptions": []
  }
]
```

#### GET `/leak-detection/admin/draft/:id`
Returns a draft report by report id.

Path parameters:

| Name | Required | Type | Description |
| --- | --- | --- | --- |
| `id` | Yes | String | Draft report id. |

Query parameters: none.

Sample request:
```http
GET /leak-detection/admin/draft/5b0d1d7e-2e3c-4a7f-91b4-208be8f8cf0a HTTP/1.1
Accept: application/json
```

Sample response:
```json
{
  "_id": "5b0d1d7e-2e3c-4a7f-91b4-208be8f8cf0a",
  "repoName": "my-service",
  "repoUrl": "https://github.com/hmrc/my-service",
  "commitId": "1b2c3d4e5f6a7b8c9d0e",
  "branch": "main",
  "timestamp": "2026-06-24T09:15:30.000Z",
  "author": "n/a",
  "totalLeaks": 1,
  "totalWarnings": 0,
  "rulesViolated": {
    "aws_secret_access_key": 1
  },
  "exclusions": {},
  "unusedExemptions": []
}
```

If no report exists, the service returns `404` with `No report found.`

#### DELETE `/leak-detection/admin/draft`
Deletes all draft reports.

Query parameters: none.

Sample request:
```http
DELETE /leak-detection/admin/draft HTTP/1.1
Accept: text/plain
```

Sample response:
```text
all drafts deleted
```

#### POST `/leak-detection/admin/rescan`
Queues rescans for a supplied list of repository names. Scans default branches only.

Query parameters:

| Name | Required | Type | Description |
| --- | --- | --- | --- |
| `mode` | No | String | `draft` or `normal`. Defaults to `draft`. |
| `rule` | No | String | Optional rule id. When supplied, the generated scan request carries that rule id. |

Request body:

JSON array of repository names.

Sample request:
```http
POST /leak-detection/admin/rescan?mode=normal&rule=aws_secret_access_key HTTP/1.1
Content-Type: application/json
Accept: text/plain

[
  "my-service",
  "another-service"
]
```

Sample response:
```text
202 Accepted with an empty body.
```

Invalid JSON returns `400 Invalid list of repos`.

#### POST `/leak-detection/admin/rescan/:repository/:branch`
Runs a rescan for one repository branch and returns the report.

Path parameters:

| Name | Required | Type | Description |
| --- | --- | --- | --- |
| `repository` | Yes | String | Repository name. |
| `branch` | Yes | String | Branch name. URL-encode branch names containing `/`, for example `feature%2Fabc`. |

Query parameters:

| Name | Required | Type | Description |
| --- | --- | --- | --- |
| `mode` | No | String | `draft` or `normal`. Defaults to `draft`. |
| `rule` | No | String | Optional rule id. |

Sample request:
```http
POST /leak-detection/admin/rescan/my-service/main?mode=draft&rule=aws_secret_access_key HTTP/1.1
Accept: application/json
```

Sample response:
```json
{
  "_id": "5b0d1d7e-2e3c-4a7f-91b4-208be8f8cf0a",
  "repoName": "my-service",
  "repoUrl": "https://github.com/hmrc/my-service",
  "commitId": "1b2c3d4e5f6a7b8c9d0e",
  "branch": "main",
  "timestamp": "2026-06-24T09:15:30.000Z",
  "author": "n/a",
  "totalLeaks": 1,
  "totalWarnings": 0,
  "rulesViolated": {
    "aws_secret_access_key": 1
  },
  "exclusions": {},
  "unusedExemptions": []
}
```

If the repository is not known to teams-and-repositories, the service returns `404` with `rescan could not be performed as 'my-service' is not a known HMRC repository`.

#### POST `/leak-detection/admin/rescan/all`
Queues rescans for all repositories known to teams-and-repositories. Scans default branches only.

Query parameters:

| Name | Required | Type | Description |
| --- | --- | --- | --- |
| `mode` | No | String | `draft` or `normal`. Defaults to `draft`. |
| `rule` | No | String | Optional rule id. |

Sample request:
```http
POST /leak-detection/admin/rescan/all?mode=normal&rule=aws_secret_access_key HTTP/1.1
Accept: text/plain
```

Sample response:
```text
202 Accepted with an empty body.
```

## Testing in a local environment
### Requirements
* Ensure [sbt](https://www.scala-sbt.org/1.x/docs/Setup.html) is installed.
* You will also need a GitHub personal access token: https://github.com/settings/tokens
  * Export the GitHub token with `export GITHUB_TOKEN=abc123abc123abc123abc123abc123abc123abc123abc123`.
* Run `sbt "run -DgithubSecrets.personalAccessToken=bc123abc123abc123abc123abc123abc123abc123abc123"` in the repository.
* MongoDB running locally. No local authorisation required.
  * On Ubuntu (likely all Debian derivatives): `sudo apt-get install mongodb-server && sudo systemctl start mongodb` is sufficient.
* Java requirements:
  * This tool was tested against Java V8 and is known to fail with Java V11. Other versions currently untested.
  * To install Java V8 on Ubuntu, if needed, run `apt install openjdk-8-jdk`
  * If your default Java version does not support this tool, run `sudo update-alternatives --config java` and select a compatible version from the list.

## Running a scan locally
### Pre-requisites
* Ensure [scala-cli](https://scala-cli.virtuslab.org/install) is installed.

You can perform a local scan with:

```bash
./local-scan.sh path-to-repo
# e.g ./local-scan.sh ../my-repo
```

## pre-commit hook
### Pre-requisites
* Ensure [scala-cli](https://scala-cli.virtuslab.org/install) is installed.
* Ensure [pre-commit](https://pre-commit.com) is installed.

It is possible to run a scan automatically every time a commit is made by using the pre-commit framework.

From the root of your repository, run:
```bash
pre-commit install
```

Then, add the following `.pre-commit-config.yaml` to the root of your repository:

```yaml
repos:
-   repo: https://github.com/hmrc/leak-detection
    rev: v0.223.0
    hooks:
    -   id: leak-detection
```

Ensure you are using the latest version by running `pre-commit autoupdate`

You can test that this has been configured correctly with `pre-commit run`

You should see:
```bash
HMRC Leak Detection......................................................Passed
```

## License
This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html")
