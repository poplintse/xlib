#!/usr/bin/env ruby

require "yaml"

root = File.expand_path("..", __dir__)
contract_path = File.join(root, "contracts/openapi.yaml")
contract = YAML.safe_load(File.read(contract_path), aliases: false)
abort "OpenAPI 3.x document required" unless contract["openapi"].to_s.start_with?("3.")

declared = contract.fetch("paths").flat_map do |path, operations|
  operations.keys.grep(/\A(get|post|put|patch|delete)\z/).map { |method| [method, path] }
end.sort

source = Dir[File.join(root, "services/backend/src/*.ts")]
  .map { |file| File.read(file) }
  .join("\n")
implemented = source
  .scan(/app\.(get|post|put|patch|delete)\(\s*["']([^"']+)["']/m)
  .map do |method, path|
    [method, path.gsub(/:([A-Za-z][A-Za-z0-9_]*)/, '{\1}')]
  end
  .sort

missing = implemented - declared
extra = declared - implemented
abort "contract is missing backend routes: #{missing.inspect}" unless missing.empty?
abort "contract declares unimplemented routes: #{extra.inspect}" unless extra.empty?

puts "OpenAPI contract covers #{declared.length} routes"
