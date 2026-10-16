# CMDI schema fixtures

Unmodified public schemas downloaded on 2026-10-14. Both profiles declare
`Header/MdCreationDate` as `xs:date`. The stylesheet regression validates complete
generated records against each selected profile, preloading the XML namespace
schema locally with network access disabled. Cases cover SQL and ISO timestamps,
date-only values and Java `Date.toString()` output for all twelve months.
Malformed Java-like inputs must not be normalized into schema-valid dates.

| File | Source | SHA-256 |
| --- | --- | --- |
| `p_1403526079380.xsd` | https://catalog.clarin.eu/ds/ComponentRegistry/rest/registry/profiles/clarin.eu:cr1:p_1403526079380/xsd | `c9d40b82d5ea05617192ea9000f28cf8605ed180c5329e633709337bb97b7c8a` |
| `p_1349361150622.xsd` | https://catalog.clarin.eu/ds/ComponentRegistry/rest/registry/profiles/clarin.eu:cr1:p_1349361150622/xsd | `e51e8e5fc416f177df7de45eb136c028a10f1b3d8f7f04240b0134157b5d62b1` |
| `xml.xsd` | https://www.w3.org/2001/xml.xsd | `61960fb3131e38022caad5360e2f33a3382578ab3c80cd58bd74320ede61b20c` |

Keep the downloaded schema content and upstream notices intact when refreshing.
The XOAI input in `../cmdi-item.xml` is synthetic; uploaded-metadata retrieval is
mocked, while the production crosswalk and XML extension functions execute normally.
