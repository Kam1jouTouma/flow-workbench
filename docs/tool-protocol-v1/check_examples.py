"""Check the schema subset used by this v1 kit and selected semantic constraints.

This is a design consistency checker, NOT a complete JSON Schema 2020-12 validator
or the future production workflow executor. Python standard library only.
Run from any location: python check_examples.py
"""
import copy
import json
import re
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parent

def fail(message):
    raise ValueError(message)

def load(path):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                fail('Duplicate JSON key: ' + key)
            result[key] = value
        return result
    def invalid_constant(value):
        fail('Invalid JSON number: ' + value)
    return json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=pairs, parse_constant=invalid_constant)

def is_type(value, kind):
    return {
        'object': lambda: isinstance(value, dict), 'array': lambda: isinstance(value, list),
        'string': lambda: isinstance(value, str), 'boolean': lambda: isinstance(value, bool),
        'number': lambda: isinstance(value, (int, float)) and not isinstance(value, bool),
        'integer': lambda: isinstance(value, (int, float)) and not isinstance(value, bool) and int(value) == value
    }[kind]()

def check(value, schema, base, location='$'):
    if '$ref' in schema:
        filename, _, fragment = schema['$ref'].partition('#')
        target = base.parent / filename if filename else base
        resolved = load(target)
        for key in fragment.lstrip('/').split('/') if fragment else []:
            resolved = resolved[key.replace('~1', '/').replace('~0', '~')]
        return check(value, resolved, target, location)
    kind = schema.get('type')
    if kind and not any(is_type(value, t) for t in ([kind] if isinstance(kind, str) else kind)):
        fail(location + ': invalid type')
    if 'const' in schema and value != schema['const']:
        fail(location + ': invalid constant')
    if 'enum' in schema and value not in schema['enum']:
        fail(location + ': invalid enum')
    if isinstance(value, dict):
        for key in schema.get('required', []):
            if key not in value:
                fail(location + ': missing ' + key)
        properties = schema.get('properties', {})
        for key, child in value.items():
            if key in properties:
                check(child, properties[key], base, location + '.' + key)
            elif schema.get('additionalProperties', True) is False:
                fail(location + ': unknown ' + key)
            elif isinstance(schema.get('additionalProperties'), dict):
                check(child, schema['additionalProperties'], base, location + '.' + key)
    if isinstance(value, list):
        if len(value) < schema.get('minItems', 0) or len(value) > schema.get('maxItems', float('inf')):
            fail(location + ': invalid count')
        if schema.get('uniqueItems') and len({json.dumps(x, sort_keys=True) for x in value}) != len(value):
            fail(location + ': duplicate item')
        for index, child in enumerate(value):
            check(child, schema.get('items', {}), base, location + '[' + str(index) + ']')
    if isinstance(value, str):
        if len(value) < schema.get('minLength', 0) or len(value) > schema.get('maxLength', float('inf')):
            fail(location + ': invalid length')
        if 'pattern' in schema and re.search(schema['pattern'], value) is None:
            fail(location + ': invalid pattern')
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        if value < schema.get('minimum', -float('inf')) or value > schema.get('maximum', float('inf')):
            fail(location + ': outside range')

def structural(name, value):
    path = ROOT / 'schemas' / (name + '.schema.json')
    check(value, load(path), path)

def description_rules(descriptor):
    for group in ('inputs', 'outputs', 'parameters'):
        names = [p['name'] for p in descriptor[group]]
        if len(set(names)) != len(names):
            fail('Duplicate name in ' + group)
    for port in descriptor['inputs'] + descriptor['outputs']:
        if port['cardinality'] == 'SINGLE' and ('minItems' in port or 'maxItems' in port):
            fail('Single port has collection bounds')
        if port.get('minItems', 0) > port.get('maxItems', float('inf')):
            fail('Port bounds reversed')
    for p in descriptor['parameters']:
        if p.get('minimum', -float('inf')) > p.get('maximum', float('inf')) or p.get('minLength', 0) > p.get('maxLength', float('inf')):
            fail('Parameter bounds reversed')
        if any(k in p for k in ('minimum', 'maximum')) and p['type'] not in ('integer', 'number'):
            fail('Numeric bounds on nonnumber')
        if any(k in p for k in ('minLength', 'maxLength')) and p['type'] != 'string':
            fail('String bounds on nonstring')
        widget = p.get('widget')
        if widget in ('text', 'textarea', 'file', 'directory') and p['type'] != 'string':
            fail('String widget on nonstring')
        if widget == 'select' and 'choices' not in p:
            fail('Select widget needs choices')
        rule = {k: p[k] for k in ('type', 'minimum', 'maximum', 'minLength', 'maxLength') if k in p}
        if 'choices' in p:
            for value in p['choices']:
                check(value, rule, ROOT / 'schemas/descriptor.schema.json')
            rule['enum'] = p['choices']
        if 'default' in p:
            check(p['default'], rule, ROOT / 'schemas/descriptor.schema.json')

def config_rules(descriptor, config):
    known = {p['name']: p for p in descriptor['parameters']}
    if set(config['parameters']) - set(known):
        fail('Unknown config parameter')
    for name, p in known.items():
        present = name in config['parameters'] or 'default' in p
        if p['required'] and not present:
            fail('Missing required parameter')
        if not present:
            continue
        value = config['parameters'][name] if name in config['parameters'] else p['default']
        rule = {k: p[k] for k in ('type', 'minimum', 'maximum', 'minLength', 'maxLength') if k in p}
        if 'choices' in p:
            rule['enum'] = p['choices']
        check(value, rule, ROOT / 'schemas/config.schema.json')
        if p.get('widget') in ('file', 'directory') and not Path(value).is_absolute():
            fail('Config path not absolute')

def port_rules(ports, values, output_directory=None):
    declared = {p['name']: p for p in ports}
    if set(values) - set(declared):
        fail('Unknown port')
    for name, p in declared.items():
        if name not in values:
            if p['required']:
                fail('Missing required port')
            continue
        value = values[name]
        if value['type'] not in p['types']:
            fail('Wrong data type')
        count = len(value['items'])
        if p['cardinality'] == 'SINGLE' and count != 1:
            fail('Single port needs exactly one item')
        if p['cardinality'] == 'COLLECTION' and not p.get('minItems', 0) <= count <= p.get('maxItems', float('inf')):
            fail('Collection size outside bounds')
        for item in value['items']:
            raw = item['path']
            if output_directory is None:
                path = Path(raw)
                if not path.is_absolute():
                    fail('Input path not absolute')
            else:
                if '\\' in raw or ':' in raw or raw.startswith('/') or any(part in ('', '.', '..') for part in raw.split('/')):
                    fail('Unsafe output path')
                path = (output_directory / PurePosixPath(raw)).resolve()
                if not path.is_relative_to(output_directory.resolve()):
                    fail('Output escapes directory')
            if not path.is_file():
                fail('Artifact is not an existing file')
            if value['type'] == 'TEXT':
                content = path.read_text(encoding='utf-8')
                if content.startswith('\ufeff'):
                    fail('Text has BOM')
            if value['type'] == 'JSON':
                load(path)

def expect_rejection(label, operation):
    try:
        operation()
    except ValueError:
        return
    raise AssertionError('Not rejected: ' + label)

def main():
    examples = {name: load(ROOT / 'examples' / (name + '.json')) for name in ('descriptor', 'input', 'config', 'result', 'error')}
    for name, value in examples.items():
        structural(name, value)
    d = examples['descriptor']
    description_rules(d)
    config_rules(d, examples['config'])
    port_rules(d['inputs'], examples['input']['inputs'])
    port_rules(d['outputs'], examples['result']['outputs'], ROOT / 'examples')
    for key in ('runId', 'nodeId'):
        assert examples['input'][key] == examples['result'][key] == examples['error'][key]
    source = (ROOT / 'examples/payloads/source.txt').read_text(encoding='utf-8')
    output = (ROOT / 'examples/payloads/replaced.txt').read_text(encoding='utf-8')
    assert source.replace('Python', 'Java') == output
    bad = copy.deepcopy(d); bad['protocolVersion'] = True
    expect_rejection('boolean protocol version', lambda: structural('descriptor', bad))
    bad = copy.deepcopy(d); bad['inputs'].append(copy.deepcopy(bad['inputs'][0]))
    expect_rejection('duplicate input name', lambda: description_rules(bad))
    bad = copy.deepcopy(d); bad['parameters'][2]['default'] = 'true'
    expect_rejection('wrong default type', lambda: description_rules(bad))
    bad = copy.deepcopy(d); bad['outputs'][0]['minItems'] = 1
    expect_rejection('single collection bound', lambda: description_rules(bad))
    bad = copy.deepcopy(examples['config']); bad['parameters']['unknown'] = 1
    expect_rejection('unknown config', lambda: config_rules(d, bad))
    bad = copy.deepcopy(examples['config']); bad['parameters']['find'] = ''
    expect_rejection('empty search', lambda: config_rules(d, bad))
    bad = copy.deepcopy(examples['result']); bad['outputs']['text']['items'] = []
    expect_rejection('empty single result', lambda: port_rules(d['outputs'], bad['outputs'], ROOT / 'examples'))
    bad = copy.deepcopy(examples['result']); bad['outputs']['text']['items'][0]['path'] = '../outside.txt'
    expect_rejection('output traversal', lambda: port_rules(d['outputs'], bad['outputs'], ROOT / 'examples'))
    bad = copy.deepcopy(examples['result']); bad['outputs']['text']['type'] = 'FILE'
    expect_rejection('incompatible result', lambda: port_rules(d['outputs'], bad['outputs'], ROOT / 'examples'))
    bad = copy.deepcopy(examples['input']); bad['inputs']['text']['items'][0]['path'] = 'relative.txt'
    expect_rejection('relative input path', lambda: port_rules(d['inputs'], bad['inputs']))
    print('PASS: 5 complete examples; supported schema subset, IDs, parameter defaults, cardinality, file paths and payload content; 10 invalid cases rejected.')
    print('This checker is not a complete JSON Schema 2020-12 implementation or CLI execution test.')

if __name__ == '__main__':
    main()
