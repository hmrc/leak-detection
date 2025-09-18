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

When a new or modified rule is introduced (i.e. the draft flag is removed), a rescan of all repositories in normal mode should also be performed on live.  This will ensure that teams will be alerted to any violations of the rule and that the offending code will be visible within the MDTP catalogue.    
Without performing a rescan all, any violations within the existing codebase will not be identified until changes are pushed up to the repository - the main issue with this approach is that not all repositories are actively being worked on.

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
