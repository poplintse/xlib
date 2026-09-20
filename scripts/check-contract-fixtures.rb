require_relative 'contract-schema'
root = File.expand_path('../contracts/fixtures', __dir__)
validator = ContractSchema.new
wire = JSON.parse(File.read(File.join(root, 'wire.json')))
wire.each { |schema, value| validator.validate(schema, value) }
invalid = JSON.parse(File.read(File.join(root, 'invalid-wire.json')))
invalid.each do |item|
  rejected = false
  begin
    validator.validate(item.fetch('schema'), item.fetch('value'))
  rescue RuntimeError
    rejected = true
  end
  abort "invalid sample accepted: #{item.fetch('id')}" unless rejected
end
# Exercise non-fixture boundaries without duplicating production validation logic.
candidate = wire.fetch('ProgressSyncRequest').fetch('items').first
negative = [
  ['ProgressSyncRequest', {'items' => Array.new(101) { candidate }}],
  ['ProgressSyncRequest', {'items' => [candidate.merge('readAtMs' => 253402300800000)]}],
  ['ProgressSyncResponse', wire.fetch('ProgressSyncResponse').merge('serverTimeMs' => nil)],
  ['StartSyncRequest', wire.fetch('StartSyncRequest').merge('email' => 'not-email')],
  ['StartSyncRequest', wire.fetch('StartSyncRequest').merge('device' => wire.fetch('StartSyncRequest').fetch('device').merge('deviceId' => 'bad-id'))]
]
negative.each do |schema, value|
  rejected = false
  begin
    validator.validate(schema, value)
  rescue RuntimeError
    rejected = true
  end
  abort "boundary accepted for #{schema}" unless rejected
end
# Verify keyword and format additions cannot silently bypass validation.
['oneOf', 'exclusiveMinimum'].each do |keyword|
  begin
    validator.audit({keyword => []})
    abort "unsupported keyword accepted: #{keyword}"
  rescue RuntimeError
  end
end
puts "Contract fixtures: #{wire.length} valid, #{invalid.length} invalid; schema assertions checked"
