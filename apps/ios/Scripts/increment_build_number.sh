#!/bin/sh

set -eu

project_file="${SRCROOT}/XLibReader.xcodeproj/project.pbxproj"
info_plist="${SRCROOT}/XLibReader/Resources/Info.plist"
artifacts_root="$(CDPATH= cd -- "${SRCROOT}/../.." && pwd)/artifacts/ios"

if [ "${XLIB_BUILD_NUMBER_LOCKED:-0}" != "1" ]; then
    export XLIB_BUILD_NUMBER_LOCKED=1
    # Portable cross-process lock: mkdir is atomic on POSIX. Works on both
    # macOS (where this script normally runs via Xcode build phases) and
    # Linux CI runners (where BSD lockf doesn't exist).
    lock_dir="${TMPDIR:-/tmp}/com.xlib.txtreader-build-number.lock"
    rm -rf "$lock_dir"
    if ! mkdir "$lock_dir" 2>/dev/null; then
        echo "could not acquire lock: $lock_dir" >&2
        exit 1
    fi
    trap 'rmdir '"$lock_dir"' 2>/dev/null || rm -rf '"$lock_dir"'' EXIT HUP INT TERM
    exec "$0"
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

highest_artifact_build=0
if [ -d "${artifacts_root}" ]; then
    while IFS= read -r app_bundle; do
        artifact_build="$(plutil -extract CFBundleVersion raw -o - "${app_bundle}/Info.plist")"
        if ! printf '%s\n' "${artifact_build}" | grep -Eq '^[1-9][0-9]*$'; then
            echo "error: invalid CFBundleVersion in ${app_bundle}" >&2
            exit 1
        fi
        if [ "${artifact_build}" -gt "${highest_artifact_build}" ]; then
            highest_artifact_build="${artifact_build}"
        fi
    done <<EOF
$(find "${artifacts_root}" -type d -name '*.app' -print)
EOF
fi

if [ "${current_build}" -lt "${highest_artifact_build}" ]; then
    reconciled_project="${project_file}.build-number-reconcile.$$"
    trap 'rm -f "${reconciled_project}"' EXIT HUP INT TERM
    if ! ruby -e '
      source, current, reconciled, output = ARGV
      text = File.read(source)
      old_value = "CURRENT_PROJECT_VERSION = #{current};"
      new_value = "CURRENT_PROJECT_VERSION = #{reconciled};"
      abort "CURRENT_PROJECT_VERSION entry was not found" unless text.include?(old_value)
      File.write(output, text.gsub(old_value, new_value))
    ' "${project_file}" "${current_build}" "${highest_artifact_build}" "${reconciled_project}"; then
        echo "error: could not reconcile CURRENT_PROJECT_VERSION" >&2
        exit 1
    fi
    mv "${reconciled_project}" "${project_file}"
    trap - EXIT HUP INT TERM
    echo "XLibReader build number reconciled: ${current_build} -> ${highest_artifact_build} (highest artifact: ${highest_artifact_build})"
    current_build="${highest_artifact_build}"
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
