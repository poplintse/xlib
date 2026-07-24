#!/bin/sh

set -eu

project_file="${SRCROOT}/XLibReader.xcodeproj/project.pbxproj"
info_plist="${SRCROOT}/XLibReader/Resources/Info.plist"

if [ "${XLIB_BUILD_NUMBER_LOCKED:-0}" != "1" ]; then
    export XLIB_BUILD_NUMBER_LOCKED=1
    exec /usr/bin/lockf -k "${TMPDIR:-/tmp}/com.xlib.txtreader-build-number.lock" "$0"
fi

if ! current_build="$(
    ruby -e '
      values = File.read(ARGV.fetch(0)).scan(/CURRENT_PROJECT_VERSION = ([^;]+);/).flatten.uniq
      abort "CURRENT_PROJECT_VERSION is missing or inconsistent" unless values.length == 1
      abort "CURRENT_PROJECT_VERSION must be a positive integer" unless values.first.match?(/\A[1-9][0-9]*\z/)
      puts values.first
    ' "$project_file"
)"; then
    echo "error: could not read CURRENT_PROJECT_VERSION from ${project_file}" >&2
    exit 1
fi

next_build=$((current_build + 1))
temporary_project="${project_file}.build-number.$$"
trap 'rm -f "${temporary_project}"' EXIT

if ! ruby -e '
  source, current, next_build, output = ARGV
  text = File.read(source)
  old_value = "CURRENT_PROJECT_VERSION = #{current};"
  new_value = "CURRENT_PROJECT_VERSION = #{next_build};"
  abort "CURRENT_PROJECT_VERSION entry was not found" unless text.include?(old_value)
  File.write(output, text.gsub(old_value, new_value))
' "$project_file" "$current_build" "$next_build" "$temporary_project"; then
    echo "error: could not increment CURRENT_PROJECT_VERSION" >&2
    exit 1
fi

if ! grep -Fq '<string>$(CURRENT_PROJECT_VERSION)</string>' "${info_plist}"; then
    echo "error: Info.plist must use \$(CURRENT_PROJECT_VERSION)" >&2
    exit 1
fi

mv "${temporary_project}" "${project_file}"
trap - EXIT

echo "XLibReader build number: ${current_build} -> ${next_build}"
