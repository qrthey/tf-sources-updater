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
This software can override terraform files on disk. Ideally, run the
updater against a terraform project that is under source control and
in a clean state. This way, you can easily check the changes performed
by the updater and catch potential errors, before comitting some of
the proposed changes.

A newer release of a module might not be backwards compatible and
might require other changes in the repository using the module. It is
advised to review the changelog of the modules that are updated to a
newer version. Especially when a proposed change updates the major
version of a reference. The tool can be run in a mode where no
upgrades outside of the current major version are performed. See more
below under 'Running the updater'.

## Access to tags from private repositories
Make sure that you have access to download the tag information from
the github repositories. If an environmental variable `GITHUB_TOKEN`
is present, it is used to authenticate against the github API. You can
create one in the github UI under your account settings -> Developer
settings -> Personal access tokens. When generating a new token,
ensure 'repo' root scope is selected to get API access to private
repositories that your account has access to.

If the tool receives an error from the github API it will report to
standard out which account/repository failed, after which execution
stops.

## Listing current module references
This operation is save and does not changes files. It also doesn't
fetch tags from github, but just lists all of the github urls found in
the relevant terraform files for the target project.

    clojure -X tf-sources/list-current-sources :dir '"/path/to/terraform-stack-root"'

To also see the files that reference the module sources, run the
command with the :include-file-paths true option, which defaults to
false.

    clojure -X tf-sources/list-current-sources :dir '"/path/to/terraform-stack-root"' :include-file-paths true

Likewise, there is also a :include-proposed-updates flag which will
query github for available tags and show possible updates.

    clojure -X tf-sources/list-current-sources :dir '"/path/to/terraform-stack-root"' :include-proposed-updates true

Both :include-file-paths and :include-proposed-updates can be passed
together.

## Running the updater
Use the following shell command to update all referenced github urls
with a tag to the latest tag for the repository.

    $ clojure -X tf-sources/update-references :dir '"/path/to/terraform-stack-root"'

The clojure -X invocation needs parsable args. For the path to the
terraform projects root directory to be readable, clojure must receive
it as a string (surrounded with parenthesis).

The program will print some information about its workings, for
example the paths to files that were writen with updates, and quit. If
the target terraform project is under source control, you can now
inspect proposed changes and commit the desired ones.

An optional :strategy switch can be passed. Its possible values are
:highest-semver, which is the default value if no :strategy is
specified, and :highest-semver-current-major. The latter meaning that
upgrading of tags will only be done within their current major
version. For example:

    $ clojure -X tf-sources/update-references :dir '"/path/to/terraform-stack-root"' :strategy :highest-semver-current-major
