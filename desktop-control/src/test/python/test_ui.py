"""The user's test presentation policy, restricted to disposable build profiles."""
from pathlib import Path
import xml.etree.ElementTree as ET


def configure_test_ui(profile):
    root=Path(__file__).resolve().parents[4]/"desktop-control/build"
    profile=Path(profile).resolve()
    allowed=[root/"fixtures",root/"smoke",root/"package-check"]
    if not any(profile.is_relative_to(path.resolve()) and profile!=path.resolve() for path in allowed):
        raise ValueError("Test UI setup must never edit a formal playthrough or personal profile")
    profile.mkdir(parents=True,exist_ok=True)
    target=profile/"settings.xml"
    if target.is_symlink():raise ValueError("Test preferences cannot be a symbolic link")
    document=ET.parse(target).getroot() if target.exists() else ET.Element("properties")
    if document.tag!="properties":raise ValueError("Unexpected test preference format")
    existing={entry.get("key"):entry for entry in document.findall("entry")}
    desired={"language":"zh","fullscreen":"false"}
    changed=False
    for key,value in desired.items():
        entry=existing.get(key)
        if entry is None:entry=ET.SubElement(document,"entry",{"key":key})
        if entry.text!=value:entry.text=value;changed=True
    if changed:
        header=b'<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE properties SYSTEM "http://java.sun.com/dtd/properties.dtd">\n'
        target.write_bytes(header+ET.tostring(document,encoding="utf-8"))
