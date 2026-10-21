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

Look4Sat awards **confirmed satellite QSOs** for Indonesia (DXCC 327),
using this location precedence:

1. Valid opponent ADIF `STATE` (two-letter province code) is authoritative.
2. Opponent's full six-character `GRIDSQUARE` / `VUCC_GRIDS`: resolve its
   operating location against the **offline 38-province polygons**. A
   conflicting set of fine-grid provinces is not credited.
3. Opponent's `IOTA` group only if the entire group belongs to one modern
   province, as documented by `WaipaIotaLookup`. It is not the operator's
   `MY_IOTA`. Groups covering multiple provinces are deliberately omitted.
4. Older four-character-only map caches: count geography only when the
   **entire coarse locator** consistently falls within one province.
5. As a last resort, use **unambiguous non-portable IIHG callsign allocations**
   (`WaipaCallsignLookup`). IIHG's shared ranges, ambiguous prefix blocks,
   portable calls and callsigns without a matching allocation never receive
   a guessed province from this source.

The IIHG allocation table and island-group reference list are at:
https://iihg.net/portfolio/waipa-award/
https://iihg.net/yb-iota-ref-prefix/
https://www.iota-world.org/islands-on-the-air/iota-groups-islands

This visualization is not an official IIHG eligibility check: the original
IIHG award has separate HF, date and applicant requirements, intentionally
not applied because Look4Sat follows its WAJA/WAPC satellite-QSO model.

**Legacy data:** the existing map store used four-character grid keys and
discarded the remaining locator precision and IOTA. It can immediately
calculate known STATE, unambiguous old coarse-grid and callsign matches,
without re-downloading anything. For exact six-grid and IOTA inference on
historical QSOs, those fields must arrive in a new successful LoTW/Wavelog
sync (incremental only includes updated/new records; a full sync can fetch
older, unchanged ones). Future syncs preserve full six-grid and IOTA values.


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
