# XLib repository instructions

## Scope

- `apps/android` owns the Android application and its Gradle wrapper.
- `apps/ios` owns the iOS application.
- `apps/macos` remains planned until a real build target is introduced.
- `packages/apple-shared` contains only UI-independent Swift code usable by both Apple platforms.
- `services/backend` owns the sync API implementation and database migrations.
- `contracts/openapi.yaml` is the public HTTP contract.

## Commands

- Use root `make` targets or the corresponding script in `scripts/`.
- Run `make check` for normal changes and `make check-alpha` before an Alpha release.
- Builds must be reproducible and must not mutate version files.

## Versions and releases

- Android component versions live in `apps/android/version.properties`.
- iOS component versions live in Xcode build settings and `Info.plist`.
- Backend version lives in `services/backend/package.json`.
- Monorepo releases are declared in `releases/<version>.yaml`.
- Do not publish, deploy, tag, or modify signing material without explicit approval.

## Contracts and secrets

- Update `contracts/openapi.yaml`, backend behavior, clients, tests, and `docs/API.md` together when the API changes.
- Keep real values out of `.env.example`; never commit `.env`, tokens, database URLs with credentials, signing files, or private domains.
