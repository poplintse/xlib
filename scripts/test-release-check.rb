#!/usr/bin/env ruby
# Exercise failures in a disposable fixture, never by editing component versions.
require "fileutils"
require "open3"
require "tmpdir"
require "yaml"

root = File.expand_path("..", __dir__)
checks = 0
verify = lambda do |name, &block|
  raise "FAIL: #{name}" unless block.call
  checks += 1
  puts "PASS: #{name}"
end

Dir.mktmpdir("xlib-release-check-") do |fixture|
  paths = %w[
    scripts/release-check.sh releases/0.9.11.yaml contracts/openapi.yaml
    docs/architecture/migrator-retirement-plan.json
    apps/android/version.properties apps/ios/XLibReader.xcodeproj/project.pbxproj
    apps/ios/XLibReader/Resources/Info.plist services/backend/package.json
  ]
  paths.each do |path|
    target = File.join(fixture, path)
    FileUtils.mkdir_p(File.dirname(target))
    FileUtils.cp(File.join(root, path), target)
  end
  script = File.join(fixture, "scripts/release-check.sh")
  run = lambda do |environment, *args|
    output, status = Open3.capture2e({"RELEASE_VERSION" => nil}.merge(environment),
                                   "sh", script, *args)
    [status.success?, output]
  end
  verify.call("default manifest matches unchanged component versions") { run.call({})[0] }
  verify.call("environment selects manifest and missing manifest fails") do
    success, output = run.call({"RELEASE_VERSION" => "missing"})
    !success && output.include?("release manifest not found")
  end
  verify.call("explicit argument takes precedence over environment") do
    run.call({"RELEASE_VERSION" => "missing"}, "0.9.11")[0]
  end

  manifest_path = File.join(fixture, "releases/0.9.11.yaml")
  original = File.read(manifest_path)
  [
    ["android", "version_name", "0.0.0", "Android version_name mismatch"],
    ["android", "version_code", -1, "Android version_code mismatch"],
    ["ios", "marketing_version", "0.0.0", "iOS marketing_version mismatch"],
    ["ios", "build_number", -1, "iOS build_number mismatch"],
    ["backend", "version", "0.0.0", "Backend version mismatch"]
  ].each do |component, field, value, expected|
    manifest = YAML.safe_load(original, aliases: false)
    manifest.fetch("components").fetch(component)[field] = value
    File.write(manifest_path, YAML.dump(manifest))
    verify.call("reject #{component}.#{field} mismatch") do
      success, output = run.call({})
      !success && output.include?(expected)
    end
  end
  File.write(manifest_path, original)
  [
    ["contains_legacy_migrator", false, "SQLite migration baseline must contain legacy migrator"],
    ["minimum_direct_from", "0.9.11", "SQLite migration baseline must accept 0.9.0 direct upgrades"],
    ["required_intermediate", "0.9.11", "SQLite migration baseline cannot require itself as an intermediate"]
  ].each do |field, value, expected|
    manifest = YAML.safe_load(original, aliases: false)
    manifest.fetch("upgrade").fetch("local_storage")[field] = value
    File.write(manifest_path, YAML.dump(manifest))
    verify.call("reject invalid local storage upgrade #{field}") do
      success, output = run.call({})
      !success && output.include?(expected)
    end
  end
  File.write(manifest_path, original)
  verify.call("checking does not rewrite the manifest or component versions") do
    success, = run.call({})
    success && paths.all? { |path| File.binread(File.join(root, path)) == File.binread(File.join(fixture, path)) }
  end
end

%w[check check-alpha].each do |target|
  verify.call("make #{target} forwards explicit RELEASE") do
    output, status = Open3.capture2e("make", "-n", target, "RELEASE=fixture", chdir: root)
    status.success? && output.include?('RELEASE_VERSION="fixture"')
  end
end
puts "#{checks} release-check regression checks passed"
