# WAIPA province overlay data

The application embeds `src/main/assets/awards/waipa.json` to show all 38
current Indonesian provinces in the map's award overlay.

## Award region keys

The 38 `code` values correspond to the current Indonesian administrative
provinces. Papua Barat Daya (Southwest Papua, `PD`) is distinct from
Papua Barat (West Papua, `PB`). The IIHG WAIPA award has published a
37-province list, but this app overlay intentionally uses all 38 current
provinces:

https://iihg.net/portfolio/waipa-award/

The `code` field is compared against confirmed satellite QSOs'
`STATE` fields, subject to DXCC 327 (Indonesia). This visualization is
not an official IIHG eligibility check: the original IIHG award has separate
HF, date and applicant requirements, which are intentionally not applied
because Look4Sat's WAIPA chip follows its WAJA/WAPC satellite-QSO model.

## Boundary data attribution

Derived from **Indonesia GeoJSON & TopoJSON Maps (38 Provinces)**,
maintained by denyherianto:

https://github.com/denyherianto/indonesia-geojson-topojson-maps-with-38-provinces

Source file:
`GeoJSON/indonesia-38-provinces.geojson`

According to that project's `DATA_LICENSE.md`, the boundary dataset is
licensed under **CC BY 4.0**:

https://creativecommons.org/licenses/by/4.0/

**Changes made for Look4Sat:**
- Converted the supplied 38-province GeoJSON into the award overlay's
  `regions` + `rings` format.
- Kept Papua Barat Daya (Southwest Papua, `PD`) separate from Papua
  Barat (West Papua, `PB`) for independent fill, hit-testing and counts.
- Omitted interior hole rings, removed duplicated closing vertices,
  and rounded longitude/latitude points to four decimals to reduce
  asset size for offline Android use.
- Added Indonesian province codes, Chinese and Indonesian/English labels,
  and map label anchors.

The boundary geometry is a simplified cartographic aid, not a legal
administrative boundary source. The source dataset's upstream license
and provenance should be checked before other reuse.
