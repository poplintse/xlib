#!/bin/sh
set -eu

component="${1:-}"
requested="${2:-}"
root="${3:-$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)}"

ruby -rjson -ropen3 - "$component" "$requested" "$root" <<'RUBY'
component, requested, root = ARGV
version_pattern = /\A[0-9]+\.[0-9]+\.[0-9]+(?:[+-][0-9A-Za-z.-]+)?\z/
abort "invalid VERSION: #{requested}" unless requested.empty? || version_pattern.match?(requested)

def write_atomic(path, contents)
  temporary = "#{path}.tmp.#{$$}"
  mode = File.stat(path).mode & 0o777
  File.write(temporary, contents)
  File.chmod(mode, temporary)
  File.rename(temporary, path)
ensure
  File.delete(temporary) if temporary && File.exist?(temporary)
end

case component
when "android"
  path = File.join(root, "apps/android/version.properties")
  text = File.read(path)
  names = text.scan(/^versionName=(.+)$/).flatten
  codes = text.scan(/^versionCode=(\d+)$/).flatten
  abort "version.properties must contain exactly one versionName and versionCode" unless names.length == 1 && codes.length == 1
  current_version = names.first
  current_code = Integer(codes.first, 10)
  abort "invalid Android versionName" unless version_pattern.match?(current_version)
  abort "invalid Android versionCode" unless current_code.positive?
  version = requested.empty? ? current_version : requested
  existing_codes = Dir.glob(File.join(root, "artifacts/android/*/xlib-*.apk")).each_with_object([]) do |artifact, values|
    match = File.basename(artifact).match(/-(\d+)\.apk\z/)
    values << Integer(match[1], 10) if match
  end
  next_code = ([current_code] + existing_codes).max + 1
  text.sub!(/^versionName=.+$/, "versionName=#{version}")
  text.sub!(/^versionCode=\d+$/, "versionCode=#{next_code}")
  write_atomic(path, text)
  puts "#{version}:#{current_code}:#{next_code}"
when "ios"
  path = File.join(root, "apps/ios/XLibReader.xcodeproj/project.pbxproj")
  text = File.read(path)
  versions = text.scan(/MARKETING_VERSION = ([^;]+);/).flatten.uniq
  builds = text.scan(/CURRENT_PROJECT_VERSION = ([^;]+);/).flatten.uniq
  abort "iOS MARKETING_VERSION or CURRENT_PROJECT_VERSION is missing or inconsistent" unless versions.length == 1 && builds.length == 1
  current_version = versions.first
  current_build = Integer(builds.first, 10)
  abort "invalid iOS version" unless version_pattern.match?(current_version)
  abort "invalid iOS build number" unless current_build.positive?
  version = requested.empty? ? current_version : requested
  existing_builds = Dir.glob(File.join(root, "artifacts/ios/*/*.app/Info.plist")).each_with_object([]) do |plist, values|
    output, status = Open3.capture2("/usr/bin/plutil", "-extract", "CFBundleVersion", "raw", "-o", "-", plist)
    values << Integer(output.strip, 10) if status.success? && output.strip.match?(/\A[1-9][0-9]*\z/)
  end
  next_build = ([current_build] + existing_builds).max + 1
  version_count = text.scan(/MARKETING_VERSION = [^;]+;/).length
  build_count = text.scan(/CURRENT_PROJECT_VERSION = [^;]+;/).length
  abort "iOS version settings are missing" if version_count.zero? || build_count.zero?
  text.gsub!(/MARKETING_VERSION = [^;]+;/, "MARKETING_VERSION = #{version};")
  text.gsub!(/CURRENT_PROJECT_VERSION = [^;]+;/, "CURRENT_PROJECT_VERSION = #{next_build};")
  write_atomic(path, text)
  puts "#{version}:#{current_build}:#{next_build}"
when "backend"
  path = File.join(root, "services/backend/package.json")
  package = JSON.parse(File.read(path))
  current_version = package.fetch("version")
  abort "invalid backend version" unless version_pattern.match?(current_version)
  version = requested.empty? ? current_version : requested
  if version != current_version
    text = File.read(path)
    matches = text.scan(/^[ \t]*"version"\s*:\s*"[^"]+"[,]?$/)
    abort "package.json must contain exactly one top-level version" unless matches.length == 1
    replacement = matches.first.sub(/"[^"]+"(?=[,]?\z)/, "\"#{version}\"")
    text.sub!(matches.first, replacement)
    write_atomic(path, text)
  end
  puts version
else
  abort "usage: $0 <android|ios|backend> [VERSION] [repository-root]"
end
RUBY
