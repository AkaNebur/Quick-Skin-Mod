# Build and Merge with Forgix

Run a full clean build of the Fabric and Forge subprojects, then merge them into a single multi-loader JAR using Forgix.

Execute these steps:
1. Run `./gradlew clean build mergeJars` with a 10-minute timeout
2. Show the build output summary
3. List the generated JAR files in `build/libs/`, `fabric/build/libs/`, and `forge/build/libs/` with sizes and timestamps
4. Confirm that all JARs have been built with the latest changes

This command rebuilds everything from scratch to ensure the merged JAR contains all recent code changes.
