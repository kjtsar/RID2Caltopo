# LAANC Airspace Awareness Design

## Goal

R2C should warn operators when the current 1 statute mile operating area is inside or near controlled airspace that requires FAA airspace authorization, so users do not need to cross-check AutoPylot or OpenSky before launch.

## Scope

This milestone is read-only airspace awareness. R2C will not request LAANC authorizations or act as a UAS Service Supplier. It will query FAA public UAS Facility Map data and surface clear operator guidance when controlled airspace intersects the operating area.

## Data Source

Use FAA UDDS UAS Facility Map data:

`https://services6.arcgis.com/ssFJjBXIUyZDrSYZ/arcgis/rest/services/FAA_UAS_FacilityMap_Data/FeatureServer/0/query`

The query should use the current device point with a 1 statute mile distance buffer and request the fields:

`OBJECTID,CEILING,UNIT,APT1_FAAID,APT1_ICAO,APT1_NAME,APT1_LAANC,AIRSPACE_1,AIRSPACE_2,AIRSPACE_3,AIRSPACE_4,AIRSPACE_5`

Confirmed regression fixture: `39.47816, -118.78456` returns Fallon NAS, Class D, LAANC enabled, and a 400 ft ceiling.

## Operating Radius

Replace the operator-facing 1 NM pilot bubble with a shared BVLOS waiver radius of 1 statute mile. The internal nautical-mile value is `0.868976 NM`. The map ring, NOTAM intersection policy, and FAA airspace query should use this shared value so UI wording and safety logic match the waiver.

## User Experience

When FAA UAS Facility Map data indicates controlled airspace at the operating location, R2C should show a caution status such as:

`Airspace: LAANC required - Fallon NAS Class D up to 400 ft`

If the FAA source is unavailable, R2C should report that controlled-airspace status is unavailable and avoid implying the airspace is clear. NOTAM clear and LAANC-required are separate facts; controlled-airspace caution should take priority over a green NOTAM chip.

## Implementation Boundaries

- Add a small airspace package for data models, FAA response parsing, status policy, and network lookup.
- Keep NOTAM parsing intact, but replace hard-coded `1 NM operating area` constants and strings with shared 1 statute mile radius helpers.
- Keep network code behind repository boundaries so unit tests can cover parsing/policy without live FAA calls.

## Verification

- Unit-test the 1 statute mile to nautical-mile conversion and display label.
- Unit-test FAA UAS Facility Map parsing using the Fallon fixture.
- Unit-test status policy so Fallon Class D produces a caution/LAANC-required state.
- Unit-test NOTAM policy/ring behavior consumes the shared 1 statute mile radius.
- Run focused unit tests first, then the existing release gate if time allows.
