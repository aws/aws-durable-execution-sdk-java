# Release Process

Releases use two GitHub Actions workflows with a manual approval boundary between
them:

- [`Prepare Maven Release`](.github/workflows/release_maven.yml) creates the
  release version pull request.
- [`Publish Maven Release`](.github/workflows/publish_maven.yml) publishes from a
  manually created GitHub release.

## 1. Prepare the release pull request

1. Open **Actions**, select **Prepare Maven Release**, and choose **Run
   workflow** from the default branch.
2. Enter the release version without a leading `v`, for example `2.1.1`.
3. Wait for the workflow to validate the release and open a pull request that
   updates all Maven POMs to the release version.
4. Review, approve, and merge the release version pull request.

The preparation workflow validates that releases containing `BREAKING` changes
increment the major version. It commits the version change to
`release/v<version>` and stops after opening the pull request. It does not
publish artifacts or create a GitHub release.

## 2. Create the GitHub release

After the release version pull request is merged:

1. Open **Releases** and choose **Draft a new release**.
2. Create the tag `v<version>`, for example `v2.1.1`, from the release version
   pull request's merge commit on the default branch.
3. Set the title to `Release v<version>` and generate or enter the release
   notes.
4. Mark the release as a prerelease when applicable.
5. Publish the release.

Publishing the GitHub release emits the `release.published` event. This starts
the Maven publication workflow and the release notification workflow.

## 3. Verify publication

The publication workflow:

1. Verifies that the tag is a semantic version, points to a commit on the
   default branch, and matches the Maven version in the tagged POM.
2. Builds, signs, and uploads the SDK, filesystem SerDes extension, testing
   library, and OpenTelemetry plugin to Sonatype Central Portal.
3. Uploads the four JARs to the existing GitHub release.
4. Opens a pull request for the next development version. A final release
   increments the patch version, so `2.1.1` produces `2.1.2-SNAPSHOT`. A
   prerelease keeps the same base version, so `2.1.1-rc1` produces
   `2.1.1-SNAPSHOT` until the final `2.1.1` release.

After **Publish Maven Release** succeeds:

1. Open [Publishing Deployments](https://central.sonatype.com/publishing/deployments)
   in Sonatype Central Portal.
2. Find the deployments for the release version and verify that they contain
   the expected SDK, filesystem SerDes extension, testing library, and
   OpenTelemetry plugin artifacts.
3. Click **Publish** for each deployment and wait for publication to complete.
   The workflow uses `autoPublish=false`, so this manual action is required.
4. Confirm that the GitHub release contains the expected JARs and that the
   artifacts are available in Maven Central.
5. Review, approve, and merge the next development version pull request.
