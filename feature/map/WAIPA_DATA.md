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
2. Opponent's full six- or eight-character `GRIDSQUARE` / `VUCC_GRIDS`: resolve its
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

**Legacy data:** old map caches indexed QSOs by four-character VUCC grids
and truncated the source's finer locators. They also omitted the opposite
station's IOTA. This was an optimization for VUCC-only counting, not a
statement that six/eight-character Maidenhead positions or IOTA are unneeded.
The old app cannot reconstruct discarded values from existing caches.

**New lossless data policy (as delivered by the remote service):**
- Keep original full 4/6/8-character `GRIDSQUARE`, `VUCC_GRIDS`,
  `MY_GRIDSQUARE` and `MY_VUCC_GRIDS` separately from four-character
  map/VUCC grouping keys. Never overwrite the source locators with the
  derived square index.
- Keep the opposite station's `IOTA` separately from own-station
  `MY_IOTA`.
- Retain these values when parsing LoTW/Wavelog, when writing the map's
  persisted confirmed-QSO cache, when bridging LoTW confirmations into
  the local QSO logbook, and through QSO edit/merge/import/export.
- The logbook Room DB migrates additively from schema v5 to v6,
  preserving existing records without deleting or recreating the database.

A user upgrading from an old version can immediately recalculate known
STATE, unique coarse-grid or callsign matches without downloading. A fresh
server sync restores precision for records included in its response.
Incremental sync cannot recover unchanged historical QSOs: a **single full
resync** is needed to populate lost fine-grids/IOTA on those records if the
upstream server still holds them. Subsequent syncs do not lose them again.
The app never fabricates locators or IOTA that the server did not provide.


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
