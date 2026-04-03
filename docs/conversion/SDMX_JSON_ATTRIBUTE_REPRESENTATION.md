# SDMX-JSON 2.0 Attribute Representation in Data Messages

## Overview

SDMX-JSON 2.0 data messages allow two ways to represent attribute values in `data.dataSets`:

1. **Indexed** — attribute values in `data.dataSets[].attributes` (and series/observation attributes) are integer
   indices pointing into the component's `values` array in `data.structures`.
2. **Inline** — attribute values are written directly in the data arrays as strings, localized objects (e.g.
   `{"en": "..."}`), or arrays of such values.

Both representations are valid per the SDMX-JSON 2.0 specification. The choice depends on whether the component in the
structure defines a `values` array.

## Specification Reference

From
the [SDMX-JSON Data Message Field Guide](https://github.com/sdmx-twg/sdmx-json/blob/master/data-message/docs/1-sdmx-json-field-guide.md),
section **component**, field **values**:

> *Whenever the `values` field is provided [in the structure's component], the component values in the dataSets will
always contain the corresponding array indexes, otherwise they will contain the values themselves.*

For attributes specifically:

> *Attributes should present their values in the `values` array at least when they are coded, or if they are presented
at dataset, group or series level (in order to avoid repetition). If they are non-coded and presented at observation
level then instead of using the component's `values` array, the attribute values can be directly written into the
dataSets.*

## When Each Representation Is Used

| Component in structure                | In `data.dataSets`                                  |
|---------------------------------------|-----------------------------------------------------|
| `values` provided and non-empty       | Integer indices (0, 1, 2, ...)                      |
| `values` not provided or empty (`[]`) | The actual values (strings, `{"en":"..."}`, arrays) |

## Example: Mixed Representation (IMF WEO Data)

Some providers (e.g. IMF) return data with a mix of both representations in the same `attributes` array:

```json
{
  "data": {
    "dataSets": [{
      "structure": 0,
      "attributes": [
        null,
        [{"en": "The World Economic Outlook (WEO) database contains..."}],
        null,
        0,
        0,
        ["datahelp@imf.org"],
        0,
        [{"en": "Demographics"}, {"en": " Real sector"}, ...],
        0,
        null,
        0,
        [{"en": "IMF staff calculations."}],
        null,
        [{"en": "© International Monetary Fund Copyright..."}],
        [{"en": "International Monetary Fund. World Economic Outlook..."}]
      ],
      "series": { ... }
    }],
    "structures": [{
      "attributes": {
        "dataSet": [
          {"id": "DOI", "values": []},
          {"id": "FULL_DESCRIPTION", "values": []},
          {"id": "AUTHOR", "values": []},
          {"id": "PUBLISHER", "values": [{"id": "IMF"}]},
          {"id": "DEPARTMENT", "values": [{"id": "RES"}]},
          ...
        ]
      }
    }]
  }
}
```

- Slots with `values: []` (e.g. FULL_DESCRIPTION, CONTACT_POINT) → inline values in data (strings, objects, arrays).
- Slots with non-empty `values` (e.g. PUBLISHER, DEPARTMENT) → integer indices in data.

## How the SDMX Proxy Handles This

### Reader (Custom Implementation)

The proxy uses a custom reader (`CustomSdmxJsonDataReaderEngineV2`) that accepts **both** indexed and inline attribute
values. It parses each element in the attributes array by token type:

- `null` / number → treat as index or null
- string → treat as direct value
- object (`{"en": "..."}`) → extract localized text as direct value
- array → parse recursively (numbers = indexed multi-valued, strings/objects = direct multi-valued)

The reader produces `KeyValue` objects (concept + code/values) regardless of the source representation.

### Writer (Unchanged)

The existing writer always outputs **indexed** format. It converts every `KeyValue` to an integer index via
`getReportedIndex()` and writes values into `data.structures`. The proxy output is therefore always indexed — a single,
consistent format for all consumers regardless of the source registry.

### Normalization

This design means the proxy **normalizes** mixed or inline input to indexed output. Consumers always receive data in
indexed form; they do not need to handle inline values.

## References

- [SDMX-JSON Data Message Field Guide 2.1.0](https://github.com/sdmx-twg/sdmx-json/blob/master/data-message/docs/1-sdmx-json-field-guide.md) —
  official specification
- [SDMX-JSON GitHub Repository](https://github.com/sdmx-twg/sdmx-json)
