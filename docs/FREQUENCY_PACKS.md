# Frequency packs and USER.json

The SRC page uses a user-selected Android Files directory as the frequency source. Files remain visible to normal file-management tools and can be backed up, copied, edited, imported, exported, or removed without hidden application storage.

## Directory model

The directory contains two kinds of JSON files:

- `USER.json` — MemPuck-managed user records, overrides, and deletion markers
- Frequency packs — read-only source files created by the user, another tool, or a curated pack publisher

The generated `mempuck-frequency-template.json` is a template and is not loaded as a pack.

## Load precedence

MemPuck loads files in this order:

1. `USER.json`
2. Remaining `.json` pack files in alphabetical filename order

Only one memory can be active at a normalized frequency.

- A frequency already provided by `USER.json` is ignored when later encountered in a pack.
- Among packs, the first alphabetical file containing a frequency wins.
- Duplicate counts are shown on SRC so conflicting packs can be identified.

## Pack files remain pristine

Editing a pack-provided memory does not modify its source pack. MemPuck writes the edited record as an override in `USER.json`.

Consequences:

- Removing the pack does not remove a frequency that has been edited into `USER.json`.
- Deleting a pack-provided memory creates a deletion marker in `USER.json`, so the unchanged pack record does not reappear.
- Deleting a pack file removes its unmodified, non-overridden entries after the directory is refreshed.
- `USER.json` can be exported but is protected from deletion on the SRC page.

## SRC controls

- `SET PATH` — choose or change the frequency directory
- `TEMPLATE` — create `mempuck-frequency-template.json`
- `IMPORT PACK` — validate and copy an external pack into the selected directory
- `REFRESH` — reread the selected directory
- `EXPORT` — save a copy of a listed source file
- `DELETE` — remove a pack or template file; not available for `USER.json`

If an imported filename already exists, MemPuck creates a unique numbered filename rather than overwriting it.

## Pack schema

Schema name: `mempuck-frequency-pack`

Schema version: `1`

```json
{
  "schema": "mempuck-frequency-pack",
  "version": 1,
  "name": "US CB CHANNELS",
  "description": "Forty United States CB channels",
  "memories": [
    {
      "frequencyHz": 26965000,
      "mode": "AM",
      "name": "CB CH 01",
      "tags": "#CB",
      "notes": "",
      "favorite": false,
      "skip": false
    }
  ]
}
```

Each memory supports:

| Field | Type | Rules |
|---|---|---|
| `frequencyHz` | integer | Frequency in hertz; normalized to receiver resolution |
| `mode` | string | `LSB`, `USB`, `CW`, `AM`, or `FM` |
| `name` | string | Required, non-empty display name |
| `tags` | string | Whitespace- or comma-separated tokens |
| `notes` | string | Optional description |
| `favorite` | boolean | Initial favorite state |
| `skip` | boolean | Excluded from scan execution, not from manual selection |

Invalid records are ignored. A pack is rejected when its schema name or version is unsupported.

## Frequency validation

- 150 kHz–30 MHz accepts LSB, USB, CW, or AM.
- 64–108 MHz accepts FM only.
- 30–64 MHz is unsupported.
- FM frequencies are normalized to a 10 kHz grid.

## Tag normalization

Tags are split on whitespace or commas, uppercased, deduplicated, and stored with exactly one leading `#`.

```text
cb #local, ##night
```

becomes:

```text
#CB #LOCAL #NIGHT
```

`FAV` is a separate filter toggle and not an ordinary tag. A literal `#FAV` is therefore not added to the normal cloud.

## Creating a pack with an external assistant

1. Open SRC and select `TEMPLATE`.
2. Locate `mempuck-frequency-template.json` in the selected directory.
3. Upload a copy to the assistant or tool of your choice.
4. Describe the desired frequencies, modes, names, tags, notes, favorite state, and skip state.
5. Save the completed file under a descriptive `.json` name.
6. Put it in the selected directory or use `IMPORT PACK`.
7. Review the result in LIST before relying on it in the field.

Example request:

```text
Create a MemPuck frequency pack containing the 40 US CB channels. Tag every
entry #CB and name each entry CB CH 01 through CB CH 40. Use AM, favorite false,
and skip false.
```

Frequency packs should still be reviewed against an authoritative frequency source before use.
