#!/usr/bin/env ruby

lock_path = ARGV.shift
abort "usage: #{$PROGRAM_NAME} LOCK_FILE COMMAND [ARG ...]" if lock_path.nil? || ARGV.empty?

File.open(lock_path, File::RDWR | File::CREAT, 0o644) do |lock|
  lock.flock(File::LOCK_EX)
  system(*ARGV)
  status = $?
  exit(status.exitstatus || 1)
end
