# OECD dataflows — dimension types

Source: `https://sdmx.oecd.org/public/rest` (SDMX 2.1 only), queried with
`GET /dataflow/{agency}/{id}/latest?references=descendants&detail=full`,
`Accept: application/vnd.sdmx.structure+xml;version=2.1`.

Retrieved 2026-08-11.

---

## 1. `OECD.ECO.MAD:DSD_EO@DF_EO(latest)`

- Dataflow resolved: `OECD.ECO.MAD:DSD_EO@DF_EO(1.5)`
- Structure: `OECD.ECO.MAD:DSD_EO(1.5)` (isFinal=true)
- Concept scheme: `OECD.ECO.MAD:CS_EO(1.0)`
- No measure dimension, no groups.

### Dimensions (4)

| Pos | ID | Type | Representation |
|-----|----|------|----------------|
| 1 | `REF_AREA` | Dimension, coded | `OECD:CL_AREA(1.0)` |
| 2 | `MEASURE` | Dimension, coded | `OECD.ECO.MAD:CL_MEASURE(1.5)` |
| 3 | `FREQ` | Dimension, coded | `SDMX:CL_FREQ(2.1)` |
| 4 | `TIME_PERIOD` | TimeDimension, uncoded | `TextFormat textType="ObservationalTimePeriod"` |

Key order for SDMX 2.1 data queries: `REF_AREA.MEASURE.FREQ` (time is not part of the key).

### Measure

| ID | Kind | Representation |
|----|------|----------------|
| `OBS_VALUE` | PrimaryMeasure | `TextFormat textType="Double"` |

### Attributes (9)

| ID | Assignment | Representation | Attached to |
|----|------------|----------------|-------------|
| `OBS_STATUS` | Conditional | `SDMX:CL_OBS_STATUS(2.2)` | Observation (`OBS_VALUE`) |
| `UNIT_MEASURE` | Mandatory | `OECD:CL_UNIT_MEASURE(1.12)` | `MEASURE` |
| `UNIT_MULT` | Mandatory | `SDMX:CL_UNIT_MULT(1.1)` | `MEASURE` |
| `CURRENCY` | Conditional | `OECD:CL_CURRENCY(1.0)` | `REF_AREA`, `MEASURE` |
| `BASE_PER` | Conditional | uncoded, `ReportingTimePeriod` | `REF_AREA`, `MEASURE` |
| `METHODOLOGY` | Conditional | `OECD.ECO.MAD:CL_METHODOLOGY(1.0)` | `MEASURE` |
| `DECIMALS` | Conditional | `SDMX:CL_DECIMALS(1.0)` | `REF_AREA`, `MEASURE`, `FREQ` |
| `PRICE_BASE` | Conditional | `OECD:CL_PRICES(1.0)` | `MEASURE` |
| `ADJUSTMENT` | Conditional | `OECD:CL_ADJUSTMENT(1.0)` | `MEASURE` |

---

## 2. `OECD.CFE.EDS:DSD_LA_EXTREME_TEMP_DDOWN@DF_EXTREME_TEMP_DDOWN(latest)`

- Dataflow resolved: `OECD.CFE.EDS:DSD_LA_EXTREME_TEMP_DDOWN@DF_EXTREME_TEMP_DDOWN(1.0)`
- Structure: `OECD.CFE.EDS:DSD_LA_EXTREME_TEMP_DDOWN(1.0)` (isFinal=true)
- Concept scheme: `OECD.CFE.EDS:CS_EXTR_TEMP_LOCAL_AREAS(1.0)`
- No measure dimension, no groups.

### Dimensions (8)

| Pos | ID | Type | Representation |
|-----|----|------|----------------|
| 1 | `DD_ID` | Dimension, **uncoded** | `TextFormat textType="String"` |
| 2 | `REF_AREA` | Dimension, coded | `OECD:CL_REGIONAL(2.0)` |
| 3 | `MEASURE` | Dimension, coded | `OECD.CFE.EDS:CL_MEASURE_LA(1.2)` |
| 4 | `UNIT_MEASURE` | Dimension, coded | `OECD:CL_UNIT_MEASURE(1.23)` |
| 5 | `TERRITORIAL_LEVEL` | Dimension, coded | `OECD.CFE.EDS:CL_TERRITORIAL_LEVEL(1.0)` |
| 6 | `TERRITORIAL_TYPE` | Dimension, coded | `OECD.CFE.EDS:CL_TERRITORIAL_TYPE(1.0)` |
| 7 | `DD_DIM` | Dimension, coded | `OECD:CL_DD_DIM(1.0)` |
| 8 | `TIME_PERIOD` | TimeDimension, uncoded | `TextFormat textType="ObservationalTimePeriod"` |

Key order for SDMX 2.1 data queries:
`DD_ID.REF_AREA.MEASURE.UNIT_MEASURE.TERRITORIAL_LEVEL.TERRITORIAL_TYPE.DD_DIM`

### Measure

| ID | Kind | Representation |
|----|------|----------------|
| `OBS_VALUE` | PrimaryMeasure | `TextFormat textType="Double"` |

### Attributes (4)

| ID | Assignment | Representation | Attached to |
|----|------------|----------------|-------------|
| `FREQ` | Conditional | `SDMX:CL_FREQ(2.1)` | Observation (`OBS_VALUE`) |
| `OBS_STATUS` | Mandatory | `SDMX:CL_OBS_STATUS(2.2)` | Observation (`OBS_VALUE`) |
| `DECIMALS` | Conditional | `SDMX:CL_DECIMALS(1.0)` | all 7 non-time dimensions |
| `LOCAL_AREA_NAME` | Mandatory | uncoded, `String` | `DD_ID`, `REF_AREA` |

---

## 3. `OECD.SDD.TPS:DSD_SDBSBD_ISIC4@DF_BD_API(latest)`

- Dataflow resolved: `OECD.SDD.TPS:DSD_SDBSBD_ISIC4@DF_BD_API(1.0)`
- Structure: `OECD.SDD.TPS:DSD_SDBSBD_ISIC4(1.0)` (isFinal=true)
- Concept scheme: `OECD.SDD.TPS:CS_BD(1.0)`
- No measure dimension, no groups.

### Dimensions (10)

| Pos | ID | Type | Representation |
|-----|----|------|----------------|
| 1 | `FREQ` | Dimension, coded | `SDMX:CL_FREQ(2.1)` |
| 2 | `REF_AREA` | Dimension, coded | `OECD:CL_REGIONAL(2.5)` |
| 3 | `MEASURE` | Dimension, coded | `OECD.SDD.TPS:CL_MEASURE_BD(1.0)` |
| 4 | `ACTIVITY` | Dimension, coded | `OECD:CL_ACTIVITY_ISIC4(1.5)` |
| 5 | `SIZE_CLASS` | Dimension, coded | `OECD:CL_SIZECLASS(1.4)` |
| 6 | `AGE` | Dimension, coded | `OECD:CL_AGE(1.7)` |
| 7 | `ENT_TYPE` | Dimension, coded | `OECD.SDD.TPS:CL_ENT_TYPE(1.0)` |
| 8 | `BUSINESS_STAGE` | Dimension, coded | `OECD.SDD.TPS:CL_BUSINESS_STAGE(1.0)` |
| 9 | `UNIT_MEASURE` | Dimension, coded | `OECD:CL_UNIT_MEASURE(1.31)` |
| 10 | `TIME_PERIOD` | TimeDimension, uncoded | `TextFormat textType="ObservationalTimePeriod"` |

All 9 non-time dimensions are coded. Key order for SDMX 2.1 data queries:
`FREQ.REF_AREA.MEASURE.ACTIVITY.SIZE_CLASS.AGE.ENT_TYPE.BUSINESS_STAGE.UNIT_MEASURE`

### Measure

| ID | Kind | Representation |
|----|------|----------------|
| `OBS_VALUE` | PrimaryMeasure | `TextFormat textType="Float"` (not `Double` — differs from the other three) |

### Attributes (4)

| ID | Assignment | Representation | Attached to |
|----|------------|----------------|-------------|
| `DECIMALS` | Conditional | `SDMX:CL_DECIMALS(1.0)` | Observation (`OBS_VALUE`) |
| `OBS_STATUS` | Mandatory | `SDMX:CL_OBS_STATUS(2.3)` | Observation (`OBS_VALUE`) |
| `UNIT_MULT` | Mandatory | `SDMX:CL_UNIT_MULT(1.1)` | Observation (`OBS_VALUE`) |
| `VAR` | Mandatory | none declared (see below) | `MEASURE`, `AGE`, `BUSINESS_STAGE`, `UNIT_MEASURE` |

`VAR` has no `LocalRepresentation` on the attribute *and* no `CoreRepresentation` on concept
`CS_BD:VAR` ("Variable") — so its representation is entirely undeclared. Consumers that expect every
component to resolve to either a codelist or a text format will get null here.

---

## 4. `OECD.CFE.EDS:DSD_LA_DEMO_POP_AGE_DDOWN@DF_POP_AGE_DDOWN(latest)`

- Dataflow resolved: `OECD.CFE.EDS:DSD_LA_DEMO_POP_AGE_DDOWN@DF_POP_AGE_DDOWN(1.1)`
- Structure: `OECD.CFE.EDS:DSD_LA_DEMO_POP_AGE_DDOWN(1.0)` (isFinal=true) — the dataflow and its DSD are on
  **different versions** here, unlike the other three where both matched.
- Concept scheme: `OECD.CFE.EDS:CS_POP_AGE_LOCAL_AREAS(1.0)`
- No measure dimension, no groups.

### Dimensions (10)

| Pos | ID | Type | Representation |
|-----|----|------|----------------|
| 1 | `DD_ID` | Dimension, **uncoded** | `TextFormat textType="String"` |
| 2 | `REF_AREA` | Dimension, coded | `OECD:CL_REGIONAL(2.0)` |
| 3 | `MEASURE` | Dimension, coded | `OECD.CFE.EDS:CL_MEASURE(1.0)` |
| 4 | `UNIT_MEASURE` | Dimension, coded | `OECD:CL_UNIT_MEASURE(1.7)` |
| 5 | `AGE` | Dimension, coded | `OECD:CL_AGE(1.2)` |
| 6 | `SEX` | Dimension, coded | `OECD:CL_SEX(1.0)` |
| 7 | `TERRITORIAL_LEVEL` | Dimension, coded | `OECD.CFE.EDS:CL_TERRITORIAL_LEVEL(1.0)` |
| 8 | `TERRITORIAL_TYPE` | Dimension, coded | `OECD.CFE.EDS:CL_TERRITORIAL_TYPE(1.0)` |
| 9 | `DD_DIM` | Dimension, coded | `OECD:CL_DD_DIM(1.0)` |
| 10 | `TIME_PERIOD` | TimeDimension, uncoded | `TextFormat textType="ObservationalTimePeriod"` |

Key order for SDMX 2.1 data queries:
`DD_ID.REF_AREA.MEASURE.UNIT_MEASURE.AGE.SEX.TERRITORIAL_LEVEL.TERRITORIAL_TYPE.DD_DIM`

This is the same shape as `DSD_LA_EXTREME_TEMP_DDOWN` (section 2) with `AGE` and `SEX` inserted at
positions 5-6, pushing the territorial/drill-down dimensions from 5-7 to 7-9.

### Measure

| ID | Kind | Representation |
|----|------|----------------|
| `OBS_VALUE` | PrimaryMeasure | `TextFormat textType="Double"` |

### Attributes (5)

| ID | Assignment | Representation | Attached to |
|----|------------|----------------|-------------|
| `FREQ` | Conditional | `SDMX:CL_FREQ(2.1)` | Observation (`OBS_VALUE`) |
| `LOCAL_AREA_NAME` | Mandatory | uncoded, `String` | `DD_ID`, `REF_AREA` |
| `OBS_STATUS` | Mandatory | `SDMX:CL_OBS_STATUS(2.2)` | Observation (`OBS_VALUE`) |
| `UNIT_MULT` | Mandatory | `SDMX:CL_UNIT_MULT(1.2)` | `UNIT_MEASURE` |
| `DECIMALS` | Conditional | `SDMX:CL_DECIMALS(1.0)` | all 9 non-time dimensions |

---

## Notable differences across the four

- **`FREQ` placement varies by DSD.** Dimension 3 in `DSD_EO`, dimension 1 in `DSD_SDBSBD_ISIC4`, and an
  observation-level *attribute* in both `_DDOWN` DSDs. Nothing may assume `FREQ` is a dimension, nor that
  it sits at a fixed position.
- **`UNIT_MEASURE` placement varies too.** A dimension in `DSD_LA_EXTREME_TEMP_DDOWN` (pos 4),
  `DSD_LA_DEMO_POP_AGE_DDOWN` (pos 4) and `DSD_SDBSBD_ISIC4` (pos 9), but a `MEASURE`-attached attribute
  in `DSD_EO`.
- **Uncoded dimensions exist.** `DD_ID` (`textType="String"`) in both `_DDOWN` DSDs has no codelist, so its
  values can only be discovered from data or availability responses, not from the DSD.
- **Undeclared representation exists.** `VAR` in `DSD_SDBSBD_ISIC4` has neither local nor core
  representation.
- **Measure text type is not uniform:** `Double` in `DSD_EO` and both `_DDOWN` DSDs, `Float` in
  `DSD_SDBSBD_ISIC4`.
- **Dataflow version need not equal DSD version.** `DF_POP_AGE_DDOWN` is 1.1 against DSD 1.0; the other
  three match. Resolving `latest` on the dataflow says nothing about the structure's version.
- **The same codelist ID appears at different versions across dataflows** — e.g. `OECD:CL_REGIONAL` at 2.0
  (temp, pop-age) vs 2.5 (BD API); `OECD:CL_UNIT_MEASURE` at 1.7 / 1.12 / 1.23 / 1.31; `OECD:CL_AGE` at 1.2
  vs 1.7. Codelist caching must key on version, not just ID.
- **`MEASURE` is an ordinary coded dimension in all four**, never an SDMX `MeasureDimension`; the
  observation value is always the single `OBS_VALUE` primary measure.
- **Dimension count ranges 4 -> 8 -> 10 -> 10**, and all four are flat DSDs: no `GroupDimensionDescriptor`.
