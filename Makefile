SHELL := /bin/sh

.PHONY: help bootstrap check check-alpha test-backend test-apple-shared \
	build-android-debug build-ios-debug build-macos-debug prepare-release release-check

RELEASE ?= 0.9.0

help:
	@printf '%s\n' \
		'make bootstrap             Check tools and install locked backend dependencies' \
		'make check                 Run local contract, backend, shared, Android and iOS checks' \
		'make check-alpha           Run the full Alpha verification suite' \
		'make test-backend          Lint, typecheck, test and build the backend' \
		'make test-apple-shared     Test the shared Swift package' \
		'make build-android-debug   Build a reproducible Android Debug APK' \
		'make build-ios-debug       Build a reproducible iOS Simulator app' \
		'make build-macos-debug     Build macOS when a real target exists' \
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
	./scripts/build-android-debug.sh

build-ios-debug:
	./scripts/build-ios-debug.sh

build-macos-debug:
	./scripts/build-macos-debug.sh

prepare-release:
	./scripts/prepare-release.sh "$(RELEASE)"

release-check:
	./scripts/release-check.sh "$(RELEASE)"
