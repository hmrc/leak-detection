# leak-detection

[![Build Status](https://travis-ci.org/hmrc/leak-detection.svg)](https://travis-ci.org/hmrc/leak-detection) [ ![Download](https://api.bintray.com/packages/hmrc/releases/leak-detection/images/download.svg) ](https://bintray.com/hmrc/releases/leak-detection/_latestVersion)

## Overview
Service used to find leaks in git repositories using regular expressions.
It is worth noting here that as the service only runs periodically, leaked credentials might have already been found by unsavoury characters.  Education is the best tool not to leak secrets.
Further reading: https://blog.acolyer.org/2019/04/08/how-bad-can-it-git-characterizing-secret-leakage-in-public-github-repositories/?-characterizing-secret-leakage-in-public-github-repositories/

## Checks performed by Leak Detection Service
Currently there are two kinds of checks performed by LDS:
* Check for occurrence of a regular expression from the predefined set
* Check for existence of `repository.yaml` file that contains unique fingerprint of public/private MDTP repository.

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
    },
```
In such case the rule applies to all non-binary files (with filename extension that is not of ingoredExtensions), which match specified regex. 


### Checking for existence of `repository.yaml` file.
This check is performed if a configuration parameter `alerts.slack.enabledForRepoVisibility` is set to true.
If the check is enabled, whenever commit is made to the repository that doesn't contain `repository.yaml` file or the file doesn't contain valid fingerprint, the alert will be sent.

## Testing in a local environment
### Requirements
* Ensure [sbt](https://www.scala-sbt.org/0.13/docs/Setup.html) is installed.
* You will also need a GitHub personal access token: https://github.com/settings/tokens
    * Export the GitHub token with `export GITHUB_TOKEN=abc123abc123abc123abc123abc123abc123abc123abc123`.
* Run `sbt "run -DgithubSecrets.personalAccessToken=bc123abc123abc123abc123abc123abc123abc123abc123"` in the repository.
* MongoDB running locally. No local authorisation required.
    * On Ubuntu (likely all Debian derivatives): `sudo apt-get install mongodb-server && sudo systemctl start mongodb` is sufficient.
* Java requirements:
    * This tool was tested against Java V8 and is known to fail with Java V11. Other versions currently untested.
    * To install Java V8 on Ubuntu, if needed, run `apt install openjdk-8-jdk`
    * If your default Java version does not support this tool, run `sudo update-alternatives --config java` and select a compatible version from the list.

### Rules
* In `/conf/application.conf`, modify the `allRules` section with whatever regular expressions you want.

### Scan a single branch example
* Ensure you are in the `.scripts` directory: `cd .scripts`
* Run: `./rescan_repo.sh leak-detection scan-progress-file`
    * Where `leak-detection` is the repository you wish to scan and `scan-progress-file` is the file that saves the progress of the scan.

### Scanning all branches in all repositories
* Ensure you are in the `.scripts` directory: `cd .scripts`
* Create a plain text file with all repositories to scan, one repository per line.
    * Example file `leak_test_list`:
```
leak-detection
cds-file-upload-frontend
```
* Ensure you are in the `.scripts` directory: `cd .scripts`
* Run: `./rescan_all.sh leak_test_list scan-progress-file` 
    * Where `leak_test_list` is the name of the file with the list of repositories to scan and `scan-progress-file` is the file that saves the progress of the scan.

## License
This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html")
