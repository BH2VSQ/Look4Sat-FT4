# WAIPA province overlay data

The application embeds `src/main/assets/awards/waipa.json` to show 37
Indonesian provinces in the map's award overlay.

## Award region keys

The 37 `code` values correspond to the official **IIHG Worked All
Indonesian Provinces Award** province list:

https://iihg.net/portfolio/waipa-award/

The `code` field is compared against confirmed satellite QSOs'
`STATE` fields, subject to DXCC 327 (Indonesia). This visualization is
not an official eligibility check: the original IIHG award has separate
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
- Combined the polygons of Papua Barat Daya (Southwest Papua, modern
  province code `PD`) with Papua Barat (West Papua, WAIPA code `PB`)
  to match the IIHG **37-province** award list.
- Omitted interior hole rings, removed duplicated closing vertices,
  and rounded longitude/latitude points to four decimals to reduce
  asset size for offline Android use.
- Added official WAIPA codes, Chinese and Indonesian/English labels,
  and map label anchors.

The boundary geometry is a simplified cartographic aid, not a legal
administrative boundary source. The source dataset's upstream license
and provenance should be checked before other reuse.
