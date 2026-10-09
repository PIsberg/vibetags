"""Check that a pom carries the elements Maven Central requires of a published component.

Central rejects a deployment whose pom lacks any of them, and it says so only after the upload,
which publish.yml makes after the release is tagged (#949). The list is Central's, read from
https://central.sonatype.org/publish/requirements/ on 2026-10-09: the coordinates, a name, a
description, a project url, at least one license, at least one developer, and the SCM
connection and url.

Usage: check-central-pom.py <pom> <label> <groupId> <artifactId> <version>
Prints one line per problem, prefixed with <label>, and exits 1 if there is any (a pom that
cannot be read or parsed is one), 0 if there is none, and 2 on a usage error.
"""

import sys
import xml.etree.ElementTree as ET


def local(tag):
    return tag.rsplit('}', 1)[-1]


def child(element, name):
    if element is None:
        return None
    for c in element:
        if local(c.tag) == name:
            return c
    return None


def text(element, *path):
    for name in path:
        element = child(element, name)
    return (element.text or '').strip() if element is not None else ''


def main(argv):
    if len(argv) != 6:
        print('usage: check-central-pom.py <pom> <label> <groupId> <artifactId> <version>',
              file=sys.stderr)
        return 2
    pom, label, group, artifact, version = argv[1:]
    try:
        with open(pom, 'rb') as f:
            content = f.read()
        # No pom needs a DOCTYPE, and every entity-expansion trick needs one; refuse it rather than
        # depend on the parser's defences (defusedxml is not in the standard library).
        if b'<!DOCTYPE' in content.upper():
            print(f'{label}: declares a DOCTYPE, which no Maven pom needs')
            return 1
        project = ET.fromstring(content)
    except (OSError, ET.ParseError) as e:
        print(f'{label}: cannot be parsed as XML: {e}')
        return 1

    problems = []
    for name, expected in (('groupId', group), ('artifactId', artifact), ('version', version)):
        actual = text(project, name)
        if actual != expected:
            problems.append(f'{name} is {actual or "missing"}, not {expected}')
    if version.endswith('-SNAPSHOT'):
        problems.append(f'version {version} is a snapshot, which Central does not publish')
    for name in ('name', 'description', 'url'):
        if not text(project, name):
            problems.append(f'no <{name}>')
    if not text(project, 'licenses', 'license', 'name'):
        problems.append('no <licenses><license><name>')
    developer = child(child(project, 'developers'), 'developer')
    if developer is None or not (text(developer, 'name') or text(developer, 'id')):
        problems.append('no <developers><developer> with a <name> or <id>')
    for name in ('connection', 'url'):
        if not text(project, 'scm', name):
            problems.append(f'no <scm><{name}>')

    for problem in problems:
        print(f'{label}: {problem}')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
