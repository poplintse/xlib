# Test-only validator for the explicitly supported OpenAPI 3.0 Schema subset.
# Unknown assertions fail closed; this is not a general OpenAPI implementation.
require 'yaml'
require 'json'

class ContractSchema
  KEYS = %w[$ref type format nullable required properties additionalProperties items minItems maxItems minLength maxLength minimum maximum pattern enum allOf description readOnly].freeze
  def initialize
    @document = YAML.safe_load(File.read(File.expand_path('../contracts/openapi.yaml', __dir__)), aliases: false)
    @document.fetch('components').fetch('schemas').each_value { |schema| audit(schema) }
  end

  def audit(schema)
    unknown = schema.keys - KEYS
    raise "unsupported schema keywords: #{unknown}" unless unknown.empty?
    if schema['format'] && !%w[int64 double uuid email].include?(schema['format'])
      raise "unsupported format: #{schema['format']}"
    end
    if schema['type'] && !%w[object array string integer number boolean].include?(schema['type'])
      raise "unsupported type: #{schema['type']}"
    end
    if schema.key?('additionalProperties') && ![true, false].include?(schema['additionalProperties'])
      raise 'schema-valued additionalProperties is not supported'
    end
    schema.fetch('properties', {}).each_value { |child| audit(child) }
    audit(schema['items']) if schema['items']
    schema.fetch('allOf', []).each { |child| audit(child) }
  end

  def validate(name, value)
    check(@document.fetch('components').fetch('schemas').fetch(name), value, name)
    true
  end

  def check(schema, value, path)
    if schema['$ref']
      ref = schema.fetch('$ref')
      raise "unsupported ref #{ref}" unless ref.start_with?('#/components/schemas/')
      return check(@document.fetch('components').fetch('schemas').fetch(ref.split('/').last), value, path)
    end
    return if value.nil? && schema['nullable']
    schema.fetch('allOf', []).each { |part| check(part, value, path) }
    type = schema['type']
    valid = case type
    when 'object' then value.is_a?(Hash)
    when 'array' then value.is_a?(Array)
    when 'string' then value.is_a?(String)
    when 'integer' then value.is_a?(Integer)
    when 'number' then value.is_a?(Numeric) && value.finite?
    when 'boolean' then value == true || value == false
    when nil then true
    else raise "unsupported type #{type}"
    end
    raise "#{path}: expected #{type}" unless valid
    raise "#{path}: enum" if schema['enum'] && !schema['enum'].include?(value)
    if type == 'object'
      missing = schema.fetch('required', []) - value.keys
      raise "#{path}: missing #{missing}" unless missing.empty?
      props = schema.fetch('properties', {})
      raise "#{path}: extra fields" if schema['additionalProperties'] == false && !(value.keys - props.keys).empty?
      props.each { |key, child| check(child, value[key], "#{path}.#{key}") if value.key?(key) }
    elsif type == 'array'
      raise "#{path}: minItems" if schema['minItems'] && value.length < schema['minItems']
      raise "#{path}: maxItems" if schema['maxItems'] && value.length > schema['maxItems']
      value.each_with_index { |item, i| check(schema.fetch('items'), item, "#{path}[#{i}]") }
    elsif type == 'string'
      raise "#{path}: minLength" if schema['minLength'] && value.length < schema['minLength']
      raise "#{path}: maxLength" if schema['maxLength'] && value.length > schema['maxLength']
      raise "#{path}: pattern" if schema['pattern'] && !Regexp.new(schema['pattern']).match?(value)
      formats = {'uuid' => /\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\z/i, 'email' => /\A[^\s@]+@[^\s@]+\.[^\s@]+\z/}
      raise "#{path}: format" if formats[schema['format']] && !formats[schema['format']].match?(value)
    elsif %w[integer number].include?(type)
      raise "#{path}: minimum" if schema['minimum'] && value < schema['minimum']
      raise "#{path}: maximum" if schema['maximum'] && value > schema['maximum']
      raise "#{path}: int64" if schema['format'] == 'int64' && !(-(2**63)..(2**63 - 1)).cover?(value)
    end
  end
end

if $PROGRAM_NAME == __FILE__
  input = JSON.parse($stdin.read)
  ContractSchema.new.validate(input.fetch('schema'), input.fetch('value'))
end
