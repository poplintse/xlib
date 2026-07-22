#!/bin/sh

set -eu

project_file="${SRCROOT}/XLibReader.xcodeproj/project.pbxproj"
info_plist="${SRCROOT}/XLibReader/Resources/Info.plist"

if [ "${XLIB_BUILD_NUMBER_LOCKED:-0}" != "1" ]; then
    export XLIB_BUILD_NUMBER_LOCKED=1
    exec /usr/bin/lockf -k "${TMPDIR:-/tmp}/com.xlib.txtreader-build-number.lock" "$0"
fi

current_build=$(
    sed -n 's/^[[:space:]]*CURRENT_PROJECT_VERSION = \([0-9][0-9]*\);/\1/p' "${project_file}" |
        head -n 1
)

if [ -z "${current_build}" ]; then
    echo "error: CURRENT_PROJECT_VERSION was not found in ${project_file}" >&2
    exit 1
fi

next_build=$((current_build + 1))
temporary_project="${project_file}.build-number.$$"
temporary_plist="${info_plist}.build-number.$$"
trap 'rm -f "${temporary_project}" "${temporary_plist}"' EXIT

sed "s/CURRENT_PROJECT_VERSION = ${current_build};/CURRENT_PROJECT_VERSION = ${next_build};/g" \
    "${project_file}" > "${temporary_project}"
cp "${info_plist}" "${temporary_plist}"
/usr/libexec/PlistBuddy -c "Set :CFBundleVersion ${next_build}" "${temporary_plist}"

mv "${temporary_project}" "${project_file}"
mv "${temporary_plist}" "${info_plist}"
trap - EXIT

echo "XLibReader build number: ${current_build} -> ${next_build}"
