#!/usr/bin/env python3
import xml.etree.ElementTree as ET

# Define the CycloneDX namespace
namespace = {'cyclonedx': 'http://cyclonedx.org/schema/bom/1.4'}

# Parse the SBOM XML file
tree = ET.parse('target/bom.xml')
root = tree.getroot()

# Open the output file
with open('THIRD_PARTY', 'w') as f:
    f.write("Legal Notices Attribution File\n\n")

    # Iterate over each component in the SBOM
    for component in root.findall(".//cyclonedx:component", namespace):
        name = component.find("./cyclonedx:name", namespace).text
        version = component.find("./cyclonedx:version", namespace).text
        license_elements = component.findall("./cyclonedx:licenses//cyclonedx:id", namespace)
        license_id = ','.join(l.text for l in license_elements) if license_elements else "No License Found"
        license_url_elements = component.findall("./cyclonedx:licenses//cyclonedx:url", namespace)
        license_url = ','.join(l.text for l in license_url_elements) if license_url_elements else "No License URL"
        copyright_element = component.find("./cyclonedx:publisher", namespace)
        copyright = copyright_element.text if copyright_element is not None else "No Copyright"

        # Write the component information to the attribution file
        f.write(f"Package: {name}\n")
        f.write(f"Version: {version}\n")
        f.write(f"License: {license_id}\n")
        f.write(f"License URL: {license_url}\n")
        f.write(f"Copyright: {copyright}\n")
        f.write("\n-\n\n")
