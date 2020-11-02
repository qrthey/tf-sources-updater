# Terraform sources updater

Terraform sources updater updates version tags for github repository
references used as module sources in terraform files. The following
sketches the algorithm to do so.

1. Recursively finds .tf files under a given root directory, ignoring
   files under .terraform and .git.
2. Finds all module sources that refer to github repositories with a
   version tag.
3. For each unique github repository that was discovered under 2, get
   the repository tags from the github.com API.
4. Replace all tags in all files with the newest version. If the
   content of a file changes during this step, write it to disk.


# Usage

## Caution
This software is fresh, and therefor not time-tested. Ideally, run the
updater against a terraform project that is in a clean git status.
This way, you can easily check the changes performed by the updater
and catch potential errors.

## Access to tags from private repositories
Make sure that you have access to download the tag information from
the github repositories. If an environmental variable `GITHUB_TOKEN`
is present, it is used to authenticate against the github API. You can
create one in the github UI under your account settings -> Developer
settings -> Personal access tokens. When generating a new token,
ensure 'repo' root scope is selected to get API access to private
repositories that your account has access to.

The updater will atm crash with a 404 exception received from githubs
API when you try to fetch tags for a hidden repository that you fail
to authenticate for.

## Running the updater
Use the following shell command.

    $ clojure -X tf-sources/update-references :dir '"/path/to/terraform-stack-root"'

The clojure -X invocation needs parsable args. For the path to the
terraform projects root directory to be readable, clojure must receive
it as a string (surrounded with parenthesis).

The program will print some information about its workings and quit.
Go check the terraform projects git status to see details about
potential changes.
