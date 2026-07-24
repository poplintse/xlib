# Android-specific instructions

- Use Java 17, Android Gradle Plugin 8.7.3, compile/target SDK 35, and min SDK 23 unless an approved migration changes them.
- Run commands from `apps/android` with the checked-in Gradle wrapper.
- `version.properties` is the only Android version source. Build and test tasks must not edit it.
- Release signing uses ignored local files only; never add a keystore or signing credentials to Git.
