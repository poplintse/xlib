SHELL := /bin/sh

.PHONY: help bootstrap check check-alpha test-backend test-apple-shared \
	build-android-debug build-android-release \
	build-ios-debug build-ios-release \
	build-macos-debug build-macos-release \
	build-backend-release prepare-release release-check

RELEASE ?= 0.9.0

help:
	@printf '%s\n' \
		'make bootstrap             Check tools and install locked backend dependencies' \
		'make check                 Run local contract, backend, shared, Android and iOS checks' \
		'make check-alpha           Run the full Alpha verification suite' \
		'make test-backend          Lint, typecheck, test and build the backend' \
		'make test-apple-shared     Test the shared Swift package' \
		'make build-android-debug   Build an Android Debug APK (optional VERSION)' \
		'make build-android-release Build an Android Release APK (optional VERSION)' \
		'make build-ios-debug       Build an iOS Simulator Debug app (optional VERSION)' \
		'make build-ios-release     Build a signed iOS Release app (optional VERSION)' \
		'make build-macos-debug     Build macOS when a real target exists' \
		'make build-macos-release   Build macOS Release when a real target exists (optional VERSION)' \
		'make build-backend-release Build a backend release bundle (optional VERSION)' \
		'make release-check         Validate releases/$(RELEASE).yaml'

bootstrap:
	./scripts/bootstrap.sh

check:
	./scripts/check-local.sh

check-alpha:
	./scripts/check-alpha.sh

test-backend:
	./scripts/test-backend.sh

test-apple-shared:
	swift test --package-path packages/apple-shared

build-android-debug:
	@VERSION="$(VERSION)" ./scripts/build-android-debug.sh

build-android-release:
	@VERSION="$(VERSION)" ./scripts/build-android-release.sh

build-ios-debug:
	@VERSION="$(VERSION)" ./scripts/build-ios-debug.sh

build-ios-release:
	@VERSION="$(VERSION)" ./scripts/build-ios-release.sh

build-macos-debug:
	@VERSION="$(VERSION)" ./scripts/build-macos-debug.sh

build-macos-release:
	@VERSION="$(VERSION)" ./scripts/build-macos-release.sh

build-backend-release:
	@VERSION="$(VERSION)" ./scripts/build-backend-release.sh

prepare-release:
	./scripts/prepare-release.sh "$(RELEASE)"

release-check:
	./scripts/release-check.sh "$(RELEASE)"
