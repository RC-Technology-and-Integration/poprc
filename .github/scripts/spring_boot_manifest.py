import re
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile


def verify_spring_boot_manifest(jar: ZipFile, pom: Path) -> dict:
    parent = ET.parse(pom).getroot().find('{*}parent')
    if parent is None or (
        parent.findtext('{*}groupId'), parent.findtext('{*}artifactId')
    ) != ('org.springframework.boot', 'spring-boot-starter-parent'):
        raise SystemExit(f'{pom}: parent spring-boot-starter-parent ausente.')
    expected = (parent.findtext('{*}version') or '').strip()
    if not expected:
        raise SystemExit(f'{pom}: versão do parent Spring Boot ausente.')

    try:
        manifest = jar.read('META-INF/MANIFEST.MF').decode('utf-8')
    except KeyError:
        raise SystemExit(f'{jar.filename}: META-INF/MANIFEST.MF ausente; esperado Spring-Boot-Version {expected}.') from None

    # Unfold continuation lines and read only the manifest's main section.
    unfolded = re.sub(r'(?:\r\n|\n|\r) ', '', manifest)
    main_section = re.split(r'\r\n\r\n|\n\n|\r\r', unfolded, maxsplit=1)[0]
    versions = []
    for line in main_section.splitlines():
        name, _, value = line.partition(':')
        if name.lower() == 'spring-boot-version':
            versions.append(value.strip())

    if versions != [expected]:
        found = ', '.join(repr(version) for version in versions) if versions else 'ausente'
        raise SystemExit(f'{jar.filename}: Spring-Boot-Version esperado {expected!r}; encontrado {found}.')
    return {'expected': expected, 'actual': versions[0], 'matches': True}
