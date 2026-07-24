#!/bin/sh
set -eu

root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
release="${1:-0.9.0}"
manifest="$root/releases/$release.yaml"

if [ ! -f "$manifest" ]; then
    echo "release manifest not found: releases/$release.yaml" >&2
    exit 1
fi

ruby - "$root" "$release" <<'RUBY'
require "json"
require "yaml"

root, expected_release = ARGV
manifest_path = File.join(root, "releases", "#{expected_release}.yaml")
manifest = YAML.safe_load(File.read(manifest_path), aliases: false)

abort "release field does not match filename" unless manifest["release"].to_s == expected_release

contract_path = File.join(root, manifest.dig("contract", "file").to_s)
abort "contract file is missing" unless File.file?(contract_path)
contract = YAML.safe_load(File.read(contract_path), aliases: false)
abort "OpenAPI document is invalid" unless contract["openapi"].to_s.start_with?("3.")

version_properties = File.read(File.join(root, "apps/android/version.properties"))
android_name = version_properties[/^versionName=(.+)$/, 1]
android_code = version_properties[/^versionCode=(\d+)$/, 1]&.to_i
abort "Android version_name mismatch" unless manifest.dig("components", "android", "version_name").to_s == android_name
abort "Android version_code mismatch" unless manifest.dig("components", "android", "version_code").to_i == android_code

project = File.read(File.join(root, "apps/ios/XLibReader.xcodeproj/project.pbxproj"))
ios_marketing = project.scan(/MARKETING_VERSION = ([^;]+);/).flatten.uniq
ios_build = project.scan(/CURRENT_PROJECT_VERSION = ([^;]+);/).flatten.uniq
abort "iOS MARKETING_VERSION is inconsistent" unless ios_marketing.length == 1
abort "iOS CURRENT_PROJECT_VERSION is inconsistent" unless ios_build.length == 1
abort "iOS marketing_version mismatch" unless manifest.dig("components", "ios", "marketing_version").to_s == ios_marketing.first
abort "iOS build_number mismatch" unless manifest.dig("components", "ios", "build_number").to_s == ios_build.first
info_plist = File.read(File.join(root, "apps/ios/XLibReader/Resources/Info.plist"))
abort "iOS Info.plist must use MARKETING_VERSION" unless info_plist.include?("<string>$(MARKETING_VERSION)</string>")
abort "iOS Info.plist must use CURRENT_PROJECT_VERSION" unless info_plist.include?("<string>$(CURRENT_PROJECT_VERSION)</string>")

backend = JSON.parse(File.read(File.join(root, "services/backend/package.json")))
abort "Backend version mismatch" unless manifest.dig("components", "backend", "version").to_s == backend["version"]

macos_status = manifest.dig("components", "macos", "status")
abort "macOS must remain planned until a project exists" unless macos_status == "planned"

puts "release #{expected_release} is internally consistent"
RUBY
